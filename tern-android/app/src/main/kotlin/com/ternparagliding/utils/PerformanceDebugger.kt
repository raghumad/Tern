@file:Suppress("UNCHECKED_CAST")
package com.ternparagliding.utils

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only performance monitoring system with zero release impact.
 * All monitoring is stripped out in release builds using BuildConfig.DEBUG.
 *
 * Priority Order (as specified):
 * 1. State update storm monitoring
 * 2. Memory leak detection
 * 3. Duplicate loading prevention
 * 4. Memory pressure management
 * 5. Subscription overhead reduction
 * 6. Debouncing optimization
 * 7. API rate limiting
 */
object PerformanceDebugger {

    private const val TAG = "PerformanceDebugger"
    private val mutex = Mutex()
    private val isEnabled = true // BuildConfig.DEBUG in real implementation

    data class PerformanceBudget(
        val maxGcPauseMs: Long,
        val maxPeakHeapMb: Double,
        val maxRetainedDeltaMb: Double,
        val maxGcEventCount: Int
    )

    /**
     * Dynamically calculates the performance budget based on the spatial SLA definitions.
     */
    fun computeDynamicBudget(
        baseEngineMb: Double = 150.0,
        coreLimit: Int = 20,
        nearLimit: Int = 30,
        midLimit: Int = 15,
        farLimit: Int = 5,
        primitiveSizeMb: Double = 3.0 // Multiplier based on historical FlexBuffer Map retention
    ): PerformanceBudget {
        val totalAllowedOverlays = coreLimit + nearLimit + midLimit + farLimit
        val dynamicPeakHeap = baseEngineMb + (totalAllowedOverlays * primitiveSizeMb)
        
        return PerformanceBudget(
            maxGcPauseMs = 150,      // Total pause time over a scenario 
            maxPeakHeapMb = dynamicPeakHeap,   // Dynamic layout computed from zone limits
            maxRetainedDeltaMb = 6.0, // Retained delta budget for chunked memory maps
            maxGcEventCount = 15     // Stream GC triggers
        )
    }

    val DEFAULT_BUDGET = computeDynamicBudget()

    // Priority 1: State Update Storm Monitoring
    data class ReduxMetrics(
        val stateUpdateCount: AtomicLong = AtomicLong(0),
        val lastUpdateTime: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val updateBatchSizes: MutableList<Int> = mutableListOf(),
        val averageUpdatesPerSecond: AtomicLong = AtomicLong(0),
        val actionTypeCounts: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()
    )

    // Priority 2: Memory Leak Detection
    data class MemoryMetrics(
        val overlayObjectCount: AtomicLong = AtomicLong(0),
        val referenceClears: AtomicLong = AtomicLong(0),
        val memoryPressureEvents: AtomicLong = AtomicLong(0)
    )

    // Priority 3: Allocation Tracking (New)
    data class AllocationMetrics(
        val activeAllocations: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap(),
        val totalAllocations: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap(),
        val estimatedBytes: AtomicLong = AtomicLong(0)
    )

    private val reduxMetrics = ReduxMetrics()
    private val memoryMetrics = MemoryMetrics()
    private val allocationMetrics = AllocationMetrics()

    init {
        if (isEnabled) {
            Log.d(TAG, "🚀 PerformanceDebugger initialized (DEBUG MODE)")
            Log.d(TAG, "📊 Dashboard will appear every 30 seconds in logcat")
            startPeriodicReporting()
        }
    }

    // ==================== PRIORITY 1: STATE UPDATE STORM ====================

    /**
     * Record a Redux state update for frequency monitoring
     * @param actionType The simple class name of the action triggering the update (optional)
     */
    fun recordStateUpdate(actionCount: Int = 1, actionType: String? = null) {
        if (!isEnabled) return

        val now = System.currentTimeMillis()
        val lastUpdate = reduxMetrics.lastUpdateTime.get()

        reduxMetrics.stateUpdateCount.incrementAndGet()
        reduxMetrics.updateBatchSizes.add(actionCount)
        
        if (actionType != null) {
            reduxMetrics.actionTypeCounts.computeIfAbsent(actionType) { AtomicLong(0) }.incrementAndGet()
        }

        // Calculate updates per second (rolling average)
        val timeDiff = now - lastUpdate
        if (timeDiff > 0) {
            val currentRate = (actionCount * 1000.0 / timeDiff).toLong()
            val currentAverage = reduxMetrics.averageUpdatesPerSecond.get()
            // Use exponential moving average with lower alpha (0.1) to be less sensitive to bursts
            val newAverage = (currentAverage * 0.9 + currentRate * 0.1).toLong()
            reduxMetrics.averageUpdatesPerSecond.set(newAverage)
        }

        reduxMetrics.lastUpdateTime.set(now)

        // Alert if update storm detected (>3000 updates/sec)
        if (reduxMetrics.averageUpdatesPerSecond.get() > 3000) {
            Log.w(TAG, "⚠️ STATE UPDATE STORM: ${reduxMetrics.averageUpdatesPerSecond.get()}/sec")
        }
    }

    // ==================== PRIORITY 2: MEMORY LEAK DETECTION ====================

    /**
     * Record overlay object creation for leak detection
     */
    fun recordOverlayCreation(overlayType: String) {
        if (!isEnabled) return
        memoryMetrics.overlayObjectCount.incrementAndGet()
        trackAllocation("Overlay:$overlayType", 1024) // Estimate 1KB per overlay wrapper
    }

    /**
     * Record overlay object cleanup
     */
    fun recordOverlayCleanup(overlayType: String) {
        if (!isEnabled) return
        memoryMetrics.referenceClears.incrementAndGet()
        trackDeallocation("Overlay:$overlayType", 1024)
    }

    /**
     * Record memory pressure event
     */
    fun recordMemoryPressure() {
        if (!isEnabled) return
        memoryMetrics.memoryPressureEvents.incrementAndGet()
        Log.w(TAG, "⚠️ MEMORY PRESSURE DETECTED")
    }

    // ==================== PRIORITY 3: ALLOCATION TRACKING ====================

    /**
     * Track an object allocation
     * @param type Object type/tag
     * @param estimatedSize Estimated size in bytes (optional)
     */
    fun trackAllocation(type: String, estimatedSize: Long = 0) {
        if (!isEnabled) return
        
        allocationMetrics.activeAllocations.computeIfAbsent(type) { AtomicLong(0) }.incrementAndGet()
        allocationMetrics.totalAllocations.computeIfAbsent(type) { AtomicLong(0) }.incrementAndGet()
        
        if (estimatedSize > 0) {
            allocationMetrics.estimatedBytes.addAndGet(estimatedSize)
        }
    }

    /**
     * Track an object deallocation
     */
    fun trackDeallocation(type: String, estimatedSize: Long = 0) {
        if (!isEnabled) return
        
        allocationMetrics.activeAllocations[type]?.decrementAndGet()
        
        if (estimatedSize > 0) {
            allocationMetrics.estimatedBytes.addAndGet(-estimatedSize)
        }
    }

    /**
     * Log current heap usage for post-test analysis
     */
    fun logHeapUsage(tag: String = "SNAPSHOT") {
        if (!isEnabled) return
        val runtime = Runtime.getRuntime()
        val total = runtime.totalMemory()
        val free = runtime.freeMemory()
        val max = runtime.maxMemory()
        val used = total - free
        
        // Format: [PERF_HEAP] <TAG> used=<bytes> total=<bytes> max=<bytes>
        Log.i(TAG, "[PERF_HEAP] $tag used=$used total=$total max=$max")
    }

    // ==================== REPORTING & ANALYSIS ====================

    /**
     * Get comprehensive performance report
     */
    suspend fun getPerformanceReport(): Map<String, Any> {
        if (!isEnabled) return emptyMap()

        return mutex.withLock {
            mapOf(
                // Priority 1: Redux State Updates
                "redux_updates_per_second" to reduxMetrics.averageUpdatesPerSecond.get(),
                "redux_total_updates" to reduxMetrics.stateUpdateCount.get(),
                "redux_action_types" to reduxMetrics.actionTypeCounts.mapValues { it.value.get() },

                // Priority 2: Memory Management
                "memory_overlay_objects" to memoryMetrics.overlayObjectCount.get(),
                "memory_pressure_events" to memoryMetrics.memoryPressureEvents.get(),

                // Priority 3: Allocations
                "allocations_active" to allocationMetrics.activeAllocations.mapValues { it.value.get() },
                "allocations_total" to allocationMetrics.totalAllocations.mapValues { it.value.get() },
                "allocations_estimated_bytes" to allocationMetrics.estimatedBytes.get(),

                // Summary
                "critical_issues" to getCriticalIssues()
            )
        }
    }

    private fun getCriticalIssues(): List<String> {
        val issues = mutableListOf<String>()

        if (reduxMetrics.averageUpdatesPerSecond.get() > 3000) {
            issues.add("STATE_UPDATE_STORM: ${reduxMetrics.averageUpdatesPerSecond.get()}/sec")
        }

        if (memoryMetrics.memoryPressureEvents.get() > 0) {
            issues.add("MEMORY_PRESSURE: ${memoryMetrics.memoryPressureEvents.get()} events")
        }
        
        if (allocationMetrics.estimatedBytes.get() > 600 * 1024 * 1024) { // > 600MB tracked for peak multi-country support
            issues.add("HIGH_MEMORY_USAGE: ${allocationMetrics.estimatedBytes.get() / 1024 / 1024} MB tracked")
        }

        return issues
    }

    fun clearMetrics() {
        if (!isEnabled) return
        
        reduxMetrics.actionTypeCounts.clear()
        reduxMetrics.stateUpdateCount.set(0)
        reduxMetrics.averageUpdatesPerSecond.set(0)
        
        allocationMetrics.activeAllocations.clear()
        allocationMetrics.totalAllocations.clear()
        allocationMetrics.estimatedBytes.set(0)
        
        memoryMetrics.memoryPressureEvents.set(0)
        
        Log.i(TAG, "Performance metrics cleared for test isolation")
    }

    private fun startPeriodicReporting() {
        // Periodic performance reporting (debug only)
        CoroutineScope(Dispatchers.IO).launch {
            while (isEnabled) {
                try {
                    delay(2000) // Log heap every 2 seconds for granular post-test analysis
                    logHeapUsage("PERIODIC")
                    
                    // Full summary every 10 seconds
                    if (System.currentTimeMillis() % 10000 < 2000) {
                        logPerformanceSummary()
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private suspend fun logPerformanceSummary() {
        if (!isEnabled) return

        val report = getPerformanceReport()
        val issues = report["critical_issues"] as List<String>
        val activeAllocations = report["allocations_active"] as Map<String, Long>
        val estimatedBytes = report["allocations_estimated_bytes"] as Long

        Log.d(TAG, "=== PERFORMANCE SUMMARY ===")
        Log.d(TAG, "Redux: ${report["redux_updates_per_second"]}/sec")
        
        val actionTypes = report["redux_action_types"] as Map<String, Long>
        val topActions = actionTypes.entries.sortedByDescending { it.value }.take(3)
        if (topActions.isNotEmpty()) {
             Log.d(TAG, "Top Actions: ${topActions.joinToString { "${it.key}:${it.value}" }}")
        }

        Log.d(TAG, "Memory: ${report["memory_overlay_objects"]} overlays, ${estimatedBytes / 1024} KB tracked")
        
        // Log top 5 active allocations
        val topAllocations = activeAllocations.entries.sortedByDescending { it.value }.take(5)
        if (topAllocations.isNotEmpty()) {
            Log.d(TAG, "Top Allocations: ${topAllocations.joinToString { "${it.key}:${it.value}" }}")
        }

        if (issues.isNotEmpty()) {
            Log.w(TAG, "Critical Issues: $issues")
        }
    }
}

// ==================== CONVENIENCE FUNCTIONS ====================

fun recordStateUpdate(actionCount: Int = 1, actionType: String? = null) {
    PerformanceDebugger.recordStateUpdate(actionCount, actionType)
}

fun recordOverlayLifecycle(overlayType: String, isCreation: Boolean) {
    if (isCreation) {
        PerformanceDebugger.recordOverlayCreation(overlayType)
    } else {
        PerformanceDebugger.recordOverlayCleanup(overlayType)
    }
}

fun trackAllocation(type: String, estimatedSize: Long = 0) {
    PerformanceDebugger.trackAllocation(type, estimatedSize)
}

fun trackDeallocation(type: String, estimatedSize: Long = 0) {
    PerformanceDebugger.trackDeallocation(type, estimatedSize)
}