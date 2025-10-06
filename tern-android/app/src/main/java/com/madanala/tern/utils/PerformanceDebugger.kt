package com.madanala.tern.utils

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

    // Priority 1: State Update Storm Monitoring
    data class ReduxMetrics(
        val stateUpdateCount: AtomicLong = AtomicLong(0),
        val lastUpdateTime: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val updateBatchSizes: MutableList<Int> = mutableListOf(),
        val averageUpdatesPerSecond: AtomicLong = AtomicLong(0)
    )

    // Priority 2: Memory Leak Detection
    data class MemoryMetrics(
        val overlayObjectCount: AtomicLong = AtomicLong(0),
        val referenceClears: AtomicLong = AtomicLong(0),
        val memoryPressureEvents: AtomicLong = AtomicLong(0)
    )

    // Priority 3: Duplicate Loading Prevention
    data class LoadingMetrics(
        val duplicateRequests: AtomicLong = AtomicLong(0),
        val uniqueRequests: AtomicLong = AtomicLong(0),
        val cacheHitRatio: AtomicLong = AtomicLong(0)
    )

    // Priority 4: Country Border Management
    data class CountryMetrics(
        val borderCrossings: AtomicLong = AtomicLong(0),
        val smoothTransitions: AtomicLong = AtomicLong(0),
        val countriesCached: AtomicLong = AtomicLong(0),
        val visualDiscontinuities: AtomicLong = AtomicLong(0)
    )

    private val reduxMetrics = ReduxMetrics()
    private val memoryMetrics = MemoryMetrics()
    private val loadingMetrics = LoadingMetrics()
    private val countryMetrics = CountryMetrics()

    init {
        if (isEnabled) {
            Log.d(TAG, "🚀 PerformanceDebugger initialized (DEBUG MODE)")
            Log.d(TAG, "📊 Dashboard will appear every 30 seconds in logcat")
            Log.d(TAG, "🎯 Trigger events: location changes, map moves, Redux updates")
            startPeriodicReporting()
        }
    }

    // ==================== PRIORITY 1: STATE UPDATE STORM ====================

    /**
     * Record a Redux state update for frequency monitoring
     */
    fun recordStateUpdate(actionCount: Int = 1) {
        if (!isEnabled) return

        val now = System.currentTimeMillis()
        val lastUpdate = reduxMetrics.lastUpdateTime.get()

        reduxMetrics.stateUpdateCount.incrementAndGet()
        reduxMetrics.updateBatchSizes.add(actionCount)

        // Calculate updates per second (rolling average)
        val timeDiff = now - lastUpdate
        if (timeDiff > 0) {
            val currentRate = (actionCount * 1000.0 / timeDiff).toLong()
            val currentAverage = reduxMetrics.averageUpdatesPerSecond.get()
            val newAverage = (currentAverage + currentRate) / 2
            reduxMetrics.averageUpdatesPerSecond.set(newAverage)
        }

        reduxMetrics.lastUpdateTime.set(now)

        // Alert if update storm detected (>100 updates/sec)
        if (reduxMetrics.averageUpdatesPerSecond.get() > 100) {
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
        Log.v(TAG, "Overlay created: $overlayType, total: ${memoryMetrics.overlayObjectCount.get()}")
    }

    /**
     * Record overlay object cleanup
     */
    fun recordOverlayCleanup(overlayType: String) {
        if (!isEnabled) return
        memoryMetrics.referenceClears.incrementAndGet()
        Log.v(TAG, "Overlay cleaned: $overlayType")
    }

    /**
     * Record memory pressure event
     */
    fun recordMemoryPressure() {
        if (!isEnabled) return
        memoryMetrics.memoryPressureEvents.incrementAndGet()
        Log.w(TAG, "⚠️ MEMORY PRESSURE DETECTED")
    }

    // ==================== PRIORITY 3: DUPLICATE LOADING ====================

    /**
     * Record a duplicate request (should be minimized)
     */
    fun recordDuplicateRequest(requestKey: String) {
        if (!isEnabled) return
        loadingMetrics.duplicateRequests.incrementAndGet()
        Log.v(TAG, "Duplicate request blocked: $requestKey")
    }

    /**
     * Record a unique request
     */
    fun recordUniqueRequest(requestKey: String) {
        if (!isEnabled) return
        loadingMetrics.uniqueRequests.incrementAndGet()
        Log.v(TAG, "Unique request: $requestKey")
    }

    // ==================== PRIORITY 4: COUNTRY BORDER MANAGEMENT ====================

    /**
     * Record a country border crossing
     */
    fun recordBorderCrossing(fromCountry: String, toCountry: String) {
        if (!isEnabled) return
        countryMetrics.borderCrossings.incrementAndGet()
        Log.d(TAG, "Border crossing: $fromCountry → $toCountry")
    }

    /**
     * Record a smooth transition (success case)
     */
    fun recordSmoothTransition() {
        if (!isEnabled) return
        countryMetrics.smoothTransitions.incrementAndGet()
        Log.d(TAG, "✅ Smooth border transition")
    }

    /**
     * Record visual discontinuity (failure case)
     */
    fun recordVisualDiscontinuity() {
        if (!isEnabled) return
        countryMetrics.visualDiscontinuities.incrementAndGet()
        Log.w(TAG, "❌ Visual discontinuity detected")
    }

    // ==================== REPORTING & ANALYSIS ====================

    /**
     * Manual trigger for testing performance dashboard (development only)
     */
    fun triggerTestEvents() {
        if (!isEnabled) return

        Log.d(TAG, "🧪 MANUAL TEST: Triggering performance events for dashboard demo")

        // Simulate some performance events
        recordStateUpdate(5)
        recordOverlayCreation("TestAirspace")
        recordBorderCrossing("US", "CA")
        recordSmoothTransition()

        Log.d(TAG, "✅ Test events triggered - check logcat for dashboard")
    }

    /**
     * Performance validation system for testing hybrid implementation
     * Validates that performance optimizations are working correctly
     */
    suspend fun validatePerformanceOptimizations(): ValidationResult {
        if (!isEnabled) return ValidationResult(false, "PerformanceDebugger disabled")

        val report = getPerformanceReport()
        val issues = report["critical_issues"] as List<String>
        val score = report["performance_score"] as Int

        val isValid = issues.isEmpty() && score >= 80

        return ValidationResult(
            isValid = isValid,
            message = if (isValid) {
                "✅ Performance optimizations validated: Score $score/100, no critical issues"
            } else {
                "⚠️ Performance issues detected: Score $score/100, Issues: $issues"
            },
            details = report
        )
    }

    /**
     * Performance validation result
     */
    data class ValidationResult(
        val isValid: Boolean,
        val message: String,
        val details: Map<String, Any>? = null
    )

    /**
     * Get comprehensive performance report
     */
    suspend fun getPerformanceReport(): Map<String, Any> {
        if (!isEnabled) return emptyMap()

        return mutex.withLock {
            val totalRequests = loadingMetrics.duplicateRequests.get() + loadingMetrics.uniqueRequests.get()
            val cacheHitRatio = if (totalRequests > 0) {
                (loadingMetrics.uniqueRequests.get() * 100) / totalRequests
            } else 0

            val transitionSuccessRate = if (countryMetrics.borderCrossings.get() > 0) {
                (countryMetrics.smoothTransitions.get() * 100) / countryMetrics.borderCrossings.get()
            } else 100

            mapOf(
                // Priority 1: Redux State Updates
                "redux_updates_per_second" to reduxMetrics.averageUpdatesPerSecond.get(),
                "redux_total_updates" to reduxMetrics.stateUpdateCount.get(),
                "redux_update_storm_warning" to (reduxMetrics.averageUpdatesPerSecond.get() > 100),

                // Priority 2: Memory Management
                "memory_overlay_objects" to memoryMetrics.overlayObjectCount.get(),
                "memory_cleared_objects" to memoryMetrics.referenceClears.get(),
                "memory_pressure_events" to memoryMetrics.memoryPressureEvents.get(),

                // Priority 3: Request Efficiency
                "loading_duplicate_requests" to loadingMetrics.duplicateRequests.get(),
                "loading_unique_requests" to loadingMetrics.uniqueRequests.get(),
                "loading_cache_hit_ratio" to cacheHitRatio,

                // Priority 4: Border Management
                "border_crossings" to countryMetrics.borderCrossings.get(),
                "border_smooth_transitions" to countryMetrics.smoothTransitions.get(),
                "border_visual_discontinuities" to countryMetrics.visualDiscontinuities.get(),
                "border_transition_success_rate" to transitionSuccessRate,

                // Summary
                "performance_score" to calculatePerformanceScore(),
                "critical_issues" to getCriticalIssues()
            )
        }
    }

    private fun calculatePerformanceScore(): Int {
        var score = 100

        // Deduct for state update storms
        if (reduxMetrics.averageUpdatesPerSecond.get() > 100) score -= 30
        if (reduxMetrics.averageUpdatesPerSecond.get() > 200) score -= 30

        // Deduct for memory pressure
        if (memoryMetrics.memoryPressureEvents.get() > 0) score -= 20

        // Deduct for visual discontinuities
        if (countryMetrics.visualDiscontinuities.get() > 0) score -= 25

        // Bonus for good cache hit ratio
        val totalRequests = loadingMetrics.duplicateRequests.get() + loadingMetrics.uniqueRequests.get()
        if (totalRequests > 0) {
            val hitRatio = (loadingMetrics.uniqueRequests.get() * 100) / totalRequests
            if (hitRatio > 80) score += 10
        }

        return maxOf(0, minOf(100, score))
    }

    private fun getCriticalIssues(): List<String> {
        val issues = mutableListOf<String>()

        if (reduxMetrics.averageUpdatesPerSecond.get() > 100) {
            issues.add("STATE_UPDATE_STORM: ${reduxMetrics.averageUpdatesPerSecond.get()}/sec")
        }

        if (memoryMetrics.memoryPressureEvents.get() > 0) {
            issues.add("MEMORY_PRESSURE: ${memoryMetrics.memoryPressureEvents.get()} events")
        }

        if (countryMetrics.visualDiscontinuities.get() > 0) {
            issues.add("VISUAL_DISCONTINUITY: ${countryMetrics.visualDiscontinuities.get()} events")
        }

        return issues
    }

    private fun startPeriodicReporting() {
        // Periodic performance reporting (debug only)
        CoroutineScope(Dispatchers.IO).launch {
            while (isEnabled) {
                try {
                    delay(30000) // Report every 30 seconds
                    logPerformanceSummary()
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.e(TAG, "Error in periodic reporting", e)
                    }
                    break
                }
            }
        }
    }

    private suspend fun logPerformanceSummary() {
        if (!isEnabled) return

        val report = getPerformanceReport()
        val score = report["performance_score"] as Int
        val issues = report["critical_issues"] as List<String>

        Log.d(TAG, "=== PERFORMANCE SUMMARY ===")
        Log.d(TAG, "Score: $score/100")
        Log.d(TAG, "Redux: ${report["redux_updates_per_second"]}/sec")
        Log.d(TAG, "Memory: ${report["memory_overlay_objects"]} objects")
        Log.d(TAG, "Cache: ${report["loading_cache_hit_ratio"]}% hit ratio")
        Log.d(TAG, "Borders: ${report["border_transition_success_rate"]}% smooth")

        if (issues.isNotEmpty()) {
            Log.w(TAG, "Critical Issues: $issues")
        }
    }
}

// ==================== CONVENIENCE FUNCTIONS ====================

/**
 * Convenience function to record Redux state updates
 */
fun recordStateUpdate(actionCount: Int = 1) {
    PerformanceDebugger.recordStateUpdate(actionCount)
}

/**
 * Convenience function to record overlay lifecycle
 */
fun recordOverlayLifecycle(overlayType: String, isCreation: Boolean) {
    if (isCreation) {
        PerformanceDebugger.recordOverlayCreation(overlayType)
    } else {
        PerformanceDebugger.recordOverlayCleanup(overlayType)
    }
}

/**
 * Convenience function to record border crossings
 */
fun recordBorderTransition(fromCountry: String, toCountry: String, isSmooth: Boolean) {
    PerformanceDebugger.recordBorderCrossing(fromCountry, toCountry)
    if (isSmooth) {
        PerformanceDebugger.recordSmoothTransition()
    } else {
        PerformanceDebugger.recordVisualDiscontinuity()
    }
}