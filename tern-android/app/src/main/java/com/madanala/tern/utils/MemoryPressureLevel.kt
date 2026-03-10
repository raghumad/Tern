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

        // Legacy/Fallback limits (Single Source of Truth)
        private const val MAX_AIRSPACES_FALLBACK = 150
        private const val MAX_PG_SPOTS_FALLBACK = 50
        private const val MAX_VIEWPORT_AIRSPACES_FALLBACK = 100

        /**
         * Get memory pressure level from available memory in MB
         */
        fun fromAvailableMemory(availableMemoryMB: Long): MemoryPressureLevel {
            return when {
                availableMemoryMB > THRESHOLD_HIGH_MB -> HIGH_MEMORY
                availableMemoryMB > THRESHOLD_MEDIUM_MB -> MEDIUM_MEMORY
                availableMemoryMB > THRESHOLD_LOW_MB -> LOW_MEMORY
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
                else -> fromAvailableMemory(availableMemoryMB)
            }
        }

        /**
         * Get fallback overlay budget when adaptive system is unavailable
         */
        fun getFallbackOverlayBudget(
            overlayType: String,
            flightPhase: FlightPhase
        ): OverlayBudget {
            val totalBudget = if (overlayType.contains("airspace")) {
                MAX_AIRSPACES_FALLBACK
            } else {
                MAX_PG_SPOTS_FALLBACK
            }

            // Apply flight phase multiplier
            val multiplier = when (flightPhase) {
                FlightPhase.LAUNCH -> 1.0
                FlightPhase.THERMAL -> 1.1
                FlightPhase.LANDING -> 1.2
                FlightPhase.GLIDE -> 0.9
                FlightPhase.CRUISING -> 0.8
            }

            val adjustedBudget = (totalBudget * multiplier).toInt()

            return OverlayBudget(
                totalOverlays = adjustedBudget,
                memoryPressure = LOW_MEMORY, // Conservative fallback
                flightPhase = flightPhase,
                zoneBudgets = getFallbackZoneBudgets(overlayType),
                recommendation = "Using fallback (adaptive system unavailable)"
            )
        }

        /**
         * Get fallback zone allocation for specific overlay type
         */
        fun getFallbackZoneBudgets(overlayType: String): Map<DistanceZone, Int> {
            return if (overlayType.contains("airspace")) {
                mapOf(
                    DistanceZone.CORE to MAX_VIEWPORT_AIRSPACES_FALLBACK,
                    DistanceZone.NEAR to 30,
                    DistanceZone.MID to 15,
                    DistanceZone.FAR to 5,
                    DistanceZone.EXTREME to 0
                )
            } else {
                mapOf(
                    DistanceZone.CORE to 25,
                    DistanceZone.NEAR to MAX_PG_SPOTS_FALLBACK,
                    DistanceZone.MID to 0,
                    DistanceZone.FAR to 0,
                    DistanceZone.EXTREME to 0
                )
            }
        }

        /**
         * Get aviation-safe minimum budgets (absolute floor)
         */
        fun getAviationSafetyMinimums(): Map<DistanceZone, Int> = mapOf(
            DistanceZone.CORE to 20,
            DistanceZone.NEAR to 10,
            DistanceZone.MID to 0,
            DistanceZone.FAR to 0,
            DistanceZone.EXTREME to 0
        )
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