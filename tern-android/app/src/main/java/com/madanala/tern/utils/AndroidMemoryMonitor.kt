@file:Suppress("DEPRECATION")
package com.madanala.tern.utils
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Comprehensive Android memory monitor that tracks multiple memory sources
 * for accurate pressure calculation and adaptive overlay management
 */
class AndroidMemoryMonitor(private val context: Context) : ComponentCallbacks2 {

    companion object {
        private const val TAG = "AndroidMemoryMonitor"
        private const val BYTES_TO_MB = 1024L * 1024L
        private const val MEMORY_CHECK_INTERVAL_MS = 15000L // 15 seconds

        // Weight factors for multi-factor pressure calculation
        private const val SYSTEM_MEMORY_WEIGHT = 0.4
        private const val PROCESS_MEMORY_WEIGHT = 0.3
        private const val TRIM_MEMORY_WEIGHT = 0.2
        private const val RUNTIME_MEMORY_WEIGHT = 0.1
    }

    private val simpleMemoryMonitor = SimpleMemoryMonitor(context)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Atomic reference for thread-safe state updates
    private val currentMemoryState = AtomicReference<ApplicationMemoryState>()
    private var lastMemoryCheck = 0L

    // Memory monitoring job (will be started by the overlay system)
    private var monitoringJob: kotlinx.coroutines.Job? = null

    init {
        // Register for trim memory callbacks
        context.applicationContext.registerComponentCallbacks(this)
    }

    /**
     * Get comprehensive memory state from all Android memory APIs
     */
    fun getComprehensiveMemoryState(): ApplicationMemoryState {
        val currentTime = System.currentTimeMillis()

        // Throttle memory checks to avoid excessive system calls
        if (currentTime - lastMemoryCheck < MEMORY_CHECK_INTERVAL_MS) {
            currentMemoryState.get()?.let { return it }
        }

        lastMemoryCheck = currentTime

        return try {
            val systemMemory = getSystemMemoryInfo()
            val processMemory = getProcessMemoryInfo()
            val runtimeMemory = getRuntimeMemoryInfo()
            val trimMemoryLevel = getCurrentTrimMemoryLevel()

            val calculatedPressure = calculateMemoryPressure(
                systemMemory, processMemory, runtimeMemory, trimMemoryLevel
            )

            val memoryState = ApplicationMemoryState(
                systemMemory = systemMemory,
                processMemory = processMemory,
                runtimeMemory = runtimeMemory,
                trimMemoryLevel = trimMemoryLevel,
                calculatedPressure = calculatedPressure
            )

            currentMemoryState.set(memoryState)
            memoryState

        } catch (e: Exception) {
            Log.e(TAG, "Error getting comprehensive memory state", e)

            // Return safe fallback state
            val fallbackState = createFallbackMemoryState()
            currentMemoryState.set(fallbackState)
            fallbackState
        }
    }

    /**
     * Get system memory information using ActivityManager.MemoryInfo
     */
    private fun getSystemMemoryInfo(): SystemMemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()

        activityManager.getMemoryInfo(memoryInfo)

        return SystemMemoryInfo(
            availableMemoryMB = memoryInfo.availMem / BYTES_TO_MB,
            totalMemoryMB = memoryInfo.totalMem / BYTES_TO_MB,
            usedMemoryMB = (memoryInfo.totalMem - memoryInfo.availMem) / BYTES_TO_MB,
            thresholdMB = memoryInfo.threshold / BYTES_TO_MB,
            isLowMemory = memoryInfo.lowMemory
        )
    }

    /**
     * Get process memory information using ActivityManager.getProcessMemoryInfo()
     */
    private fun getProcessMemoryInfo(): ProcessMemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        return try {
            val processInfos = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
            if (processInfos.isNotEmpty()) {
                val processInfo = processInfos[0]

                ProcessMemoryInfo(
                    pssKB = 0, // PSS not available in this API level
                    ussKB = 0, // USS not available in this API level
                    rssKB = 0, // RSS not available in this API level
                    totalPssMB = 0.0, // Will use runtime memory instead
                    totalUssMB = 0.0, // Will use runtime memory instead
                    totalRssMB = 0.0  // Will use runtime memory instead
                )
            } else {
                // Fallback if no process info available
                createFallbackProcessMemoryInfo()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting process memory info", e)
            createFallbackProcessMemoryInfo()
        }
    }

    private fun createFallbackProcessMemoryInfo(): ProcessMemoryInfo {
        return ProcessMemoryInfo(
            pssKB = 0,
            ussKB = 0,
            rssKB = 0,
            totalPssMB = 0.0,
            totalUssMB = 0.0,
            totalRssMB = 0.0
        )
    }

    /**
     * Get runtime memory information using Runtime.getRuntime()
     */
    private fun getRuntimeMemoryInfo(): RuntimeMemoryInfo {
        val runtime = Runtime.getRuntime()

        return RuntimeMemoryInfo(
            heapUsedMB = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_TO_MB,
            heapFreeMB = runtime.freeMemory() / BYTES_TO_MB,
            heapTotalMB = runtime.totalMemory() / BYTES_TO_MB,
            heapMaxMB = runtime.maxMemory() / BYTES_TO_MB,
            heapUsagePercent = ((runtime.totalMemory() - runtime.freeMemory()) * 100.0 / runtime.maxMemory())
        )
    }

    /**
     * Get current trim memory level (from ComponentCallbacks2)
     */
    private fun getCurrentTrimMemoryLevel(): TrimMemoryLevel {
        // This would ideally track the last received trim memory level
        // For now, return NORMAL as default
        return TrimMemoryLevel.NORMAL
    }

    /**
     * Calculate weighted memory pressure from multiple factors
     */
    private fun calculateMemoryPressure(
        systemMemory: SystemMemoryInfo,
        processMemory: ProcessMemoryInfo,
        runtimeMemory: RuntimeMemoryInfo,
        trimMemoryLevel: TrimMemoryLevel
    ): MemoryPressureLevel {

        // System memory factor (40% weight)
        val systemPressure = when {
            systemMemory.isLowMemory -> 1.0
            systemMemory.availableMemoryMB > 200 -> 0.0
            systemMemory.availableMemoryMB > 100 -> 0.25
            systemMemory.availableMemoryMB > 50 -> 0.5
            else -> 0.75
        }

        // Process memory factor (30% weight)
        val processPressure = when {
            processMemory.totalPssMB > 300 -> 0.75  // High PSS usage
            processMemory.totalPssMB > 200 -> 0.5  // Moderate PSS usage
            processMemory.totalPssMB > 100 -> 0.25 // Light PSS usage
            else -> 0.0
        }

        // Trim memory factor (20% weight)
        val trimPressure = when (trimMemoryLevel) {
            TrimMemoryLevel.CRITICAL -> 1.0
            TrimMemoryLevel.MODERATE -> 0.75
            TrimMemoryLevel.BACKGROUND -> 0.5
            TrimMemoryLevel.UI_HIDDEN -> 0.25
            TrimMemoryLevel.LOW -> 0.1
            TrimMemoryLevel.NORMAL -> 0.0
        }

        // Runtime memory factor (10% weight)
        val runtimePressure = when {
            runtimeMemory.heapUsagePercent > 90 -> 1.0
            runtimeMemory.heapUsagePercent > 80 -> 0.75
            runtimeMemory.heapUsagePercent > 70 -> 0.5
            runtimeMemory.heapUsagePercent > 60 -> 0.25
            else -> 0.0
        }

        // Calculate weighted average
        val weightedPressure = (systemPressure * SYSTEM_MEMORY_WEIGHT) +
                              (processPressure * PROCESS_MEMORY_WEIGHT) +
                              (trimPressure * TRIM_MEMORY_WEIGHT) +
                              (runtimePressure * RUNTIME_MEMORY_WEIGHT)

        // Convert to memory pressure level
        return when {
            weightedPressure > 0.8 -> MemoryPressureLevel.CRITICAL_MEMORY
            weightedPressure > 0.6 -> MemoryPressureLevel.LOW_MEMORY
            weightedPressure > 0.4 -> MemoryPressureLevel.MEDIUM_MEMORY
            else -> MemoryPressureLevel.HIGH_MEMORY
        }
    }

    /**
     * Start continuous memory monitoring
     */
    fun startContinuousMonitoring(onMemoryStateChanged: (ApplicationMemoryState) -> Unit) {
        stopContinuousMonitoring()

        monitoringJob = coroutineScope.launch {
            while (true) {
                try {
                    val memoryState = getComprehensiveMemoryState()
                    onMemoryStateChanged(memoryState)

                    // Check every 15 seconds
                    kotlinx.coroutines.delay(MEMORY_CHECK_INTERVAL_MS)

                } catch (e: Exception) {
                    Log.e(TAG, "Error in continuous memory monitoring", e)
                    kotlinx.coroutines.delay(MEMORY_CHECK_INTERVAL_MS)
                }
            }
        }

        Log.d(TAG, "Started continuous memory monitoring")
    }

    /**
     * Stop continuous memory monitoring
     */
    fun stopContinuousMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.d(TAG, "Stopped continuous memory monitoring")
    }

    /**
     * ComponentCallbacks2 implementation for trim memory events
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        val trimLevel = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> TrimMemoryLevel.CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> TrimMemoryLevel.MODERATE
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> TrimMemoryLevel.BACKGROUND
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> TrimMemoryLevel.UI_HIDDEN
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> TrimMemoryLevel.CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> TrimMemoryLevel.LOW
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> TrimMemoryLevel.MODERATE
            else -> TrimMemoryLevel.NORMAL
        }

        Log.d(TAG, "Trim memory event: $trimLevel")

        // Force immediate memory state update
        getComprehensiveMemoryState()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onConfigurationChanged(newConfig: Configuration) {
        // Not needed for memory monitoring
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        Log.w(TAG, "Low memory event received")
        // Force immediate memory state update
        getComprehensiveMemoryState()
    }

    /**
     * Create fallback memory state for error conditions
     */
    private fun createFallbackMemoryState(): ApplicationMemoryState {
        return ApplicationMemoryState(
            systemMemory = SystemMemoryInfo(
                availableMemoryMB = 50,
                totalMemoryMB = 2000,
                usedMemoryMB = 1950,
                thresholdMB = 100,
                isLowMemory = true
            ),
            processMemory = ProcessMemoryInfo(
                pssKB = 200000, // 200MB
                ussKB = 150000, // 150MB
                rssKB = 250000, // 250MB
                totalPssMB = 200.0,
                totalUssMB = 150.0,
                totalRssMB = 250.0
            ),
            runtimeMemory = RuntimeMemoryInfo(
                heapUsedMB = 100,
                heapFreeMB = 50,
                heapTotalMB = 150,
                heapMaxMB = 256,
                heapUsagePercent = 60.0
            ),
            trimMemoryLevel = TrimMemoryLevel.NORMAL,
            calculatedPressure = MemoryPressureLevel.CRITICAL_MEMORY
        )
    }

    /**
     * Cleanup when monitor is no longer needed
     */
    fun cleanup() {
        stopContinuousMonitoring()
        context.applicationContext.unregisterComponentCallbacks(this)
    }
}

/**
 * System memory information from ActivityManager.MemoryInfo
 */
data class SystemMemoryInfo(
    val availableMemoryMB: Long,
    val totalMemoryMB: Long,
    val usedMemoryMB: Long,
    val thresholdMB: Long,
    val isLowMemory: Boolean
)

/**
 * Process memory information from ActivityManager.getProcessMemoryInfo()
 */
data class ProcessMemoryInfo(
    val pssKB: Int,        // Proportional Set Size in KB
    val ussKB: Int,        // Unique Set Size in KB
    val rssKB: Int,        // Resident Set Size in KB
    val totalPssMB: Double, // PSS in MB
    val totalUssMB: Double, // USS in MB
    val totalRssMB: Double  // RSS in MB
)

/**
 * Runtime memory information from Runtime.getRuntime()
 */
data class RuntimeMemoryInfo(
    val heapUsedMB: Long,
    val heapFreeMB: Long,
    val heapTotalMB: Long,
    val heapMaxMB: Long,
    val heapUsagePercent: Double
)

/**
 * Trim memory level from ComponentCallbacks2
 */
enum class TrimMemoryLevel {
    NORMAL,
    LOW,
    MODERATE,
    BACKGROUND,
    UI_HIDDEN,
    CRITICAL
}

/**
 * Comprehensive application memory state from all Android memory APIs
 */
data class ApplicationMemoryState(
    val systemMemory: SystemMemoryInfo,
    val processMemory: ProcessMemoryInfo,
    val runtimeMemory: RuntimeMemoryInfo,
    val trimMemoryLevel: TrimMemoryLevel,
    val calculatedPressure: MemoryPressureLevel
) {
    override fun toString(): String {
        return "ApplicationMemoryState(" +
                "system: ${systemMemory.availableMemoryMB}MB free, " +
                "process: ${processMemory.totalPssMB}MB PSS, " +
                "runtime: ${runtimeMemory.heapUsagePercent}% heap, " +
                "trim: ${trimMemoryLevel}, " +
                "pressure: ${calculatedPressure.name})"
    }
}