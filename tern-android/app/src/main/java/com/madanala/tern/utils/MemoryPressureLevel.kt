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
     * Maximum overlays with full animations
     */
    HIGH_MEMORY(
        maxOverlays = 400,
        description = "High-end device - maximum overlays",
        animationQuality = AnimationQuality.FULL
    ),

    /**
     * Mid-range devices with moderate memory (100-200MB free)
     * Good overlay count with standard animations
     */
    MEDIUM_MEMORY(
        maxOverlays = 200,
        description = "Mid-range device - moderate overlays",
        animationQuality = AnimationQuality.STANDARD
    ),

    /**
     * Low-end devices with limited memory (50-100MB free)
     * Conservative overlay count with reduced animations
     */
    LOW_MEMORY(
        maxOverlays = 100,
        description = "Low-end device - conservative overlays",
        animationQuality = AnimationQuality.REDUCED
    ),

    /**
     * Critical memory pressure (<50MB free)
     * Minimum safe overlays only, essential animations only
     */
    CRITICAL_MEMORY(
        maxOverlays = 50,
        description = "Critical memory - minimum safe overlays",
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
        /**
         * Get memory pressure level from available memory in MB
         */
        fun fromAvailableMemory(availableMemoryMB: Long): MemoryPressureLevel {
            return when {
                availableMemoryMB > 200 -> HIGH_MEMORY
                availableMemoryMB > 100 -> MEDIUM_MEMORY
                availableMemoryMB > 50 -> LOW_MEMORY
                else -> CRITICAL_MEMORY
            }
        }

        /**
         * Get memory pressure level from Android's low memory flag and available memory
         */
        fun fromAndroidMemoryInfo(
            isLowMemory: Boolean,
            availableMemoryMB: Long
        ): MemoryPressureLevel {
            return when {
                isLowMemory -> CRITICAL_MEMORY
                availableMemoryMB > 200 -> HIGH_MEMORY
                availableMemoryMB > 100 -> MEDIUM_MEMORY
                availableMemoryMB > 50 -> LOW_MEMORY
                else -> CRITICAL_MEMORY
            }
        }
    }
}