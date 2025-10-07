package com.madanala.tern.utils

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Simple memory monitor that uses Android's ActivityManager.MemoryInfo
 * to determine current memory pressure level for adaptive overlay management
 */
class SimpleMemoryMonitor(private val context: Context) {

    companion object {
        private const val TAG = "SimpleMemoryMonitor"
        private const val BYTES_TO_MB = 1024L * 1024L
    }

    /**
     * Get current memory pressure level using Android's MemoryInfo API
     */
    fun getMemoryPressureLevel(): MemoryPressureLevel {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()

            activityManager.getMemoryInfo(memoryInfo)

            val availableMemoryMB = memoryInfo.availMem / BYTES_TO_MB
            val isLowMemory = memoryInfo.lowMemory

            Log.v(TAG, "Memory state - Available: ${availableMemoryMB}MB, Low memory: $isLowMemory")

            MemoryPressureLevel.fromAndroidMemoryInfo(isLowMemory, availableMemoryMB)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting memory info, using safe fallback", e)
            // Safe fallback for memory monitoring failures
            MemoryPressureLevel.LOW_MEMORY
        }
    }

    /**
     * Get detailed memory information for debugging and logging
     */
    fun getDetailedMemoryInfo(): DetailedMemoryInfo {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()

            activityManager.getMemoryInfo(memoryInfo)

            val availableMemoryMB = memoryInfo.availMem / BYTES_TO_MB
            val totalMemoryMB = memoryInfo.totalMem / BYTES_TO_MB
            val usedMemoryMB = totalMemoryMB - availableMemoryMB
            val thresholdMB = memoryInfo.threshold / BYTES_TO_MB

            DetailedMemoryInfo(
                availableMemoryMB = availableMemoryMB,
                totalMemoryMB = totalMemoryMB,
                usedMemoryMB = usedMemoryMB,
                thresholdMB = thresholdMB,
                isLowMemory = memoryInfo.lowMemory,
                memoryPressureLevel = MemoryPressureLevel.fromAndroidMemoryInfo(memoryInfo.lowMemory, availableMemoryMB)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error getting detailed memory info", e)
            // Return safe fallback values
            DetailedMemoryInfo(
                availableMemoryMB = 50,
                totalMemoryMB = 2000,
                usedMemoryMB = 1950,
                thresholdMB = 100,
                isLowMemory = true,
                memoryPressureLevel = MemoryPressureLevel.CRITICAL_MEMORY
            )
        }
    }

    /**
     * Check if device is currently experiencing memory pressure
     */
    fun isMemoryPressureHigh(): Boolean {
        val pressureLevel = getMemoryPressureLevel()
        return pressureLevel == MemoryPressureLevel.LOW_MEMORY ||
               pressureLevel == MemoryPressureLevel.CRITICAL_MEMORY
    }

    /**
     * Get recommended overlay budget based on current memory state
     */
    fun getRecommendedOverlayBudget(): Int {
        return getMemoryPressureLevel().maxOverlays
    }
}

/**
 * Detailed memory information for debugging and logging
 */
data class DetailedMemoryInfo(
    val availableMemoryMB: Long,
    val totalMemoryMB: Long,
    val usedMemoryMB: Long,
    val thresholdMB: Long,
    val isLowMemory: Boolean,
    val memoryPressureLevel: MemoryPressureLevel
) {
    override fun toString(): String {
        return "DetailedMemoryInfo(" +
                "available=${availableMemoryMB}MB, " +
                "total=${totalMemoryMB}MB, " +
                "used=${usedMemoryMB}MB, " +
                "threshold=${thresholdMB}MB, " +
                "lowMemory=$isLowMemory, " +
                "pressure=${memoryPressureLevel.name})"
    }
}