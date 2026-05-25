package com.madanala.tern.utils

/**
 * Represents the current memory pressure level of the Android device
 * Used to determine optimal overlay budgets and performance settings
 */
enum class MemoryPressureLevel(
    val maxOverlays: Int,
    val description: String,
    val animationQuality: AnimationQuality = AnimationQuality.FULL
) {
    /**
     * High-end devices with abundant memory (>200MB free)
     */
    HIGH_MEMORY(
        maxOverlays = 400,
        description = "High-end device",
        animationQuality = AnimationQuality.FULL
    ),

    /**
     * Mid-range devices (100-200MB free)
     */
    MEDIUM_MEMORY(
        maxOverlays = 200,
        description = "Mid-range device",
        animationQuality = AnimationQuality.STANDARD
    ),

    /**
     * Low-end devices (50-100MB free)
     */
    LOW_MEMORY(
        maxOverlays = 100,
        description = "Low-end device",
        animationQuality = AnimationQuality.REDUCED
    ),

    /**
     * Critical memory pressure (<50MB free)
     */
    CRITICAL_MEMORY(
        maxOverlays = 50,
        description = "Critical memory pressure",
        animationQuality = AnimationQuality.ESSENTIAL
    );

    /**
     * Animation quality levels that adapt to memory pressure
     */
    enum class AnimationQuality {
        FULL,       // Full staggered animations (100ms delays)
        STANDARD,   // Standard animations (50ms delays)
        REDUCED,    // Reduced animations (no stagger)
        ESSENTIAL   // Essential only (immediate)
    }

    companion object {
        const val THRESHOLD_HIGH_MB = 200L
        const val THRESHOLD_MEDIUM_MB = 100L
        const val THRESHOLD_LOW_MB = 50L

        fun fromAvailableMemory(availableMemoryMB: Long): MemoryPressureLevel {
            return when {
                availableMemoryMB > THRESHOLD_HIGH_MB -> HIGH_MEMORY
                availableMemoryMB > THRESHOLD_MEDIUM_MB -> MEDIUM_MEMORY
                availableMemoryMB > THRESHOLD_LOW_MB -> LOW_MEMORY
                else -> CRITICAL_MEMORY
            }
        }

        fun fromAndroidMemoryInfo(
            isLowMemory: Boolean,
            availableMemoryMB: Long
        ): MemoryPressureLevel {
            return when {
                isLowMemory -> CRITICAL_MEMORY
                else -> fromAvailableMemory(availableMemoryMB)
            }
        }
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
)