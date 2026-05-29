@file:Suppress("DEPRECATION")
package com.ternparagliding.utils
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
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Atomic reference for thread-safe state updates
    private val currentMemoryState = AtomicReference<ApplicationMemoryState>()
    private var lastMemoryCheck = 0L

    // Memory monitoring job
    private var monitoringJob: kotlinx.coroutines.Job? = null

    init {
        context.applicationContext.registerComponentCallbacks(this)
    }

    /**
     * Get consolidated memory state
     */
    fun getComprehensiveMemoryState(): ApplicationMemoryState {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMemoryCheck < MEMORY_CHECK_INTERVAL_MS) {
            currentMemoryState.get()?.let { return it }
        }

        lastMemoryCheck = currentTime

        return try {
            val systemMemory = getSystemMemoryInfo()
            val runtimeMemory = getRuntimeMemoryInfo()
            
            // Simple determination using centralized thresholds
            val calculatedPressure = MemoryPressureLevel.fromAndroidMemoryInfo(
                systemMemory.isLowMemory, systemMemory.availableMemoryMB
            )

            val memoryState = ApplicationMemoryState(
                systemMemory = systemMemory,
                processMemory = createEmptyProcessMemoryInfo(), // RSS/PSS removed for simplicity
                runtimeMemory = runtimeMemory,
                trimMemoryLevel = TrimMemoryLevel.NORMAL,
                calculatedPressure = calculatedPressure
            )

            currentMemoryState.set(memoryState)
            memoryState

        } catch (e: Exception) {
            Log.e(TAG, "Error gathering memory state", e)
            val fallback = createFallbackMemoryState()
            currentMemoryState.set(fallback)
            fallback
        }
    }

    /**
     * Legacy support for SimpleMemoryMonitor's detailed info
     */
    fun getDetailedMemoryInfo(): DetailedMemoryInfo {
        val state = getComprehensiveMemoryState()
        return DetailedMemoryInfo(
            availableMemoryMB = state.systemMemory.availableMemoryMB,
            totalMemoryMB = state.systemMemory.totalMemoryMB,
            usedMemoryMB = state.systemMemory.usedMemoryMB,
            thresholdMB = state.systemMemory.thresholdMB,
            isLowMemory = state.systemMemory.isLowMemory,
            memoryPressureLevel = state.calculatedPressure
        )
    }

    private fun getSystemMemoryInfo(): SystemMemoryInfo {
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

    private fun getRuntimeMemoryInfo(): RuntimeMemoryInfo {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()

        return RuntimeMemoryInfo(
            heapUsedMB = used / BYTES_TO_MB,
            heapFreeMB = runtime.freeMemory() / BYTES_TO_MB,
            heapTotalMB = runtime.totalMemory() / BYTES_TO_MB,
            heapMaxMB = runtime.maxMemory() / BYTES_TO_MB,
            heapUsagePercent = (used * 100.0 / runtime.maxMemory())
        )
    }

    fun startContinuousMonitoring(onMemoryStateChanged: (ApplicationMemoryState) -> Unit) {
        stopContinuousMonitoring()
        monitoringJob = coroutineScope.launch {
            while (true) {
                try {
                    onMemoryStateChanged(getComprehensiveMemoryState())
                    kotlinx.coroutines.delay(MEMORY_CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    kotlinx.coroutines.delay(MEMORY_CHECK_INTERVAL_MS)
                }
            }
        }
    }

    fun stopContinuousMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        // Force update on significant trim events
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            getComprehensiveMemoryState()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        getComprehensiveMemoryState()
    }

    private fun createEmptyProcessMemoryInfo() = ProcessMemoryInfo(0, 0, 0, 0.0, 0.0, 0.0)

    private fun createFallbackMemoryState(): ApplicationMemoryState {
        return ApplicationMemoryState(
            systemMemory = SystemMemoryInfo(50, 2000, 1950, 100, true),
            processMemory = createEmptyProcessMemoryInfo(),
            runtimeMemory = RuntimeMemoryInfo(100, 50, 150, 256, 60.0),
            trimMemoryLevel = TrimMemoryLevel.NORMAL,
            calculatedPressure = MemoryPressureLevel.CRITICAL_MEMORY
        )
    }

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