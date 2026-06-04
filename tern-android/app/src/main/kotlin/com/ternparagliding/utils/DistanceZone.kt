package com.ternparagliding.utils

/**
 * Distance-based zones for intelligent overlay management
 * Overlays are prioritized and managed based on their distance from map center
 */
enum class DistanceZone(
    val maxKm: Double,
    val priority: Int,
    val description: String,
    val safetyCritical: Boolean = false
) {
    /**
     * CORE zone: 0-8km - Critical safety overlays
     * Always visible regardless of memory pressure
     * Examples: Danger areas, parachute zones, TFRs, immediate hazards
     */
    CORE(
        maxKm = 8.0,
        priority = 1,
        description = "Critical safety zone - always visible",
        safetyCritical = true
    ),

    /**
     * NEAR zone: 8-40km - High priority area overlays
     * Reduced only under significant memory pressure
     * Examples: Training areas, thermal sources, nearby landing sites
     */
    NEAR(
        maxKm = 40.0,
        priority = 2,
        description = "High priority area - essential for flight",
        safetyCritical = false
    ),

    /**
     * MID zone: 40-160km - Medium priority regional overlays
     * Reduced under moderate memory pressure
     * Examples: Controlled airspace, glider sites, terrain hazards
     */
    MID(
        maxKm = 160.0,
        priority = 3,
        description = "Medium priority - regional context",
        safetyCritical = false
    ),

    /**
     * FAR zone: 160-320km - Low priority extended overlays
     * Reduced under light memory pressure
     * Examples: Airways, navigation aids, distant airports
     */
    FAR(
        maxKm = 320.0,
        priority = 4,
        description = "Low priority - extended awareness",
        safetyCritical = false
    ),

    /**
     * EXTREME zone: 320km+ - Remove first under any memory pressure
     * Examples: Distant reporting points, non-relevant airspace
     */
    EXTREME(
        maxKm = Double.MAX_VALUE,
        priority = 5,
        description = "Remove first - non-essential overlays",
        safetyCritical = false
    );

    companion object {
        /**
         * Get distance zone for a given distance from map center
         */
        fun fromDistanceKm(distanceKm: Double): DistanceZone {
            return when {
                distanceKm <= CORE.maxKm -> CORE
                distanceKm <= NEAR.maxKm -> NEAR
                distanceKm <= MID.maxKm -> MID
                distanceKm <= FAR.maxKm -> FAR
                else -> EXTREME
            }
        }

        /**
         * Get all safety-critical zones (never reduced by memory pressure)
         */
        fun getSafetyCriticalZones(): List<DistanceZone> {
            return values().filter { it.safetyCritical }
        }

        /**
         * Get zones in priority order (highest priority first)
         */
        fun getPriorityOrder(): List<DistanceZone> {
            return values().sortedBy { it.priority }
        }

        /**
         * Get zones in reverse priority order (lowest priority first, for cleanup)
         */
        fun getCleanupOrder(): List<DistanceZone> {
            return values().sortedByDescending { it.priority }
        }
    }

    /**
     * Aviation-specific zone allocation based on flight phase
     */
    fun getFlightPhaseMultiplier(flightPhase: FlightPhase): Double {
        return when (this) {
            CORE -> 1.0  // Always maximum allocation for safety

            NEAR -> when (flightPhase) {
                FlightPhase.LAUNCH -> 1.2
                FlightPhase.THERMAL -> 1.1
                FlightPhase.LANDING -> 1.3
                FlightPhase.GLIDE -> 1.0
                FlightPhase.CRUISING -> 0.8
            }

            MID -> when (flightPhase) {
                FlightPhase.LAUNCH -> 0.9
                FlightPhase.THERMAL -> 1.2
                FlightPhase.LANDING -> 1.1
                FlightPhase.GLIDE -> 1.3
                FlightPhase.CRUISING -> 0.7
            }

            FAR -> when (flightPhase) {
                FlightPhase.LAUNCH -> 0.5
                FlightPhase.THERMAL -> 0.8
                FlightPhase.LANDING -> 0.6
                FlightPhase.GLIDE -> 0.9
                FlightPhase.CRUISING -> 1.0
            }

            EXTREME -> when (flightPhase) {
                FlightPhase.LAUNCH -> 0.2
                FlightPhase.THERMAL -> 0.3
                FlightPhase.LANDING -> 0.2
                FlightPhase.GLIDE -> 0.4
                FlightPhase.CRUISING -> 0.5
            }
        }
    }
}

/**
 * Paraglider flight phases that affect overlay priorities
 */
enum class FlightPhase(
    val description: String,
    val typicalAltitude: String,
    val keyConcerns: List<String>
) {
    LAUNCH(
        description = "Launch preparation and takeoff",
        typicalAltitude = "Ground level to 500ft",
        keyConcerns = listOf("Launch sites", "Immediate hazards", "Wind conditions")
    ),

    THERMAL(
        description = "Thermalling for altitude gain",
        typicalAltitude = "500ft to 8000ft",
        keyConcerns = listOf("Thermal sources", "Other traffic", "Weather avoidance")
    ),

    GLIDE(
        description = "Cross-country gliding",
        typicalAltitude = "1000ft to 5000ft",
        keyConcerns = listOf("Landing options", "Controlled airspace", "Terrain hazards")
    ),

    LANDING(
        description = "Approach and landing",
        typicalAltitude = "1000ft to ground",
        keyConcerns = listOf("Landing sites", "Obstacles", "Wind conditions")
    ),

    CRUISING(
        description = "Long cross-country flight",
        typicalAltitude = "2000ft to 10000ft+",
        keyConcerns = listOf("Weather systems", "Airspace boundaries", "Alternate landing sites")
    )
}

/**
 * Utility functions for working with distance zones
 */
object DistanceZoneUtils {
    /**
     * Calculate optimal overlay budget for each zone based on memory pressure and flight phase
     */
    fun calculateZoneBudgets(
        totalBudget: Int,
        memoryPressure: MemoryPressureLevel,
        flightPhase: FlightPhase = FlightPhase.LAUNCH
    ): Map<DistanceZone, Int> {

        // Apply memory pressure multiplier
        val pressureMultiplier = when (memoryPressure) {
            MemoryPressureLevel.HIGH_MEMORY -> 1.0
            MemoryPressureLevel.MEDIUM_MEMORY -> 0.8
            MemoryPressureLevel.LOW_MEMORY -> 0.6
            MemoryPressureLevel.CRITICAL_MEMORY -> 0.4
        }

        val adjustedBudget = (totalBudget * pressureMultiplier).toInt()

        return mapOf(
            DistanceZone.CORE to (adjustedBudget * 0.5).toInt(),     // 50% - Critical safety
            DistanceZone.NEAR to (adjustedBudget * 0.3).toInt(),     // 30% - Important area
            DistanceZone.MID to (adjustedBudget * 0.15).toInt(),     // 15% - Regional context
            DistanceZone.FAR to (adjustedBudget * 0.05).toInt(),     // 5% - Extended awareness
            DistanceZone.EXTREME to 0                               // 0% - Memory pressure
        )
    }

    /**
     * Get aviation safety budget that preserves critical overlays
     * Budget is now zoom-aware to prevent "Halo Effect" at regional levels.
     */
    fun getAviationSafetyBudget(
        totalBudget: Int,
        memoryPressure: MemoryPressureLevel,
        zoom: Double = 12.0
    ): Map<DistanceZone, Int> {
        val safetyCriticalZones = DistanceZone.getSafetyCriticalZones()
        val nonCriticalBudget = totalBudget * 0.3 // Reserve 70% for safety-critical

        // Always allocate maximum to safety-critical zones
        val safetyBudget = mutableMapOf<DistanceZone, Int>()
        safetyCriticalZones.forEach { zone ->
            safetyBudget[zone] = (totalBudget * 0.7 / safetyCriticalZones.size).toInt()
        }

        // Allocate remaining budget to non-critical zones based on memory pressure
        val remainingBudget = totalBudget - safetyBudget.values.sum()
        val nonCriticalZones = DistanceZone.values().filter { !it.safetyCritical }

        nonCriticalZones.forEach { zone ->
                val allocation = when (memoryPressure) {
                MemoryPressureLevel.HIGH_MEMORY -> (remainingBudget * 0.25).toInt()
                MemoryPressureLevel.MEDIUM_MEMORY -> (remainingBudget * 0.2).toInt()
                MemoryPressureLevel.LOW_MEMORY -> {
                    // At low zoom, give more to MID/FAR to prevent Halo Effect
                    if (zoom < ZoomCategory.REGIONAL_THRESHOLD && (zone == DistanceZone.MID || zone == DistanceZone.NEAR)) {
                         (remainingBudget * 0.15).toInt()
                    } else {
                         (remainingBudget * 0.1).toInt()
                    }
                }
                MemoryPressureLevel.CRITICAL_MEMORY -> {
                    // Even in critical, show minimal regional context if zoomed out
                    if (zoom < ZoomCategory.REGIONAL_THRESHOLD && zone == DistanceZone.MID) {
                        (remainingBudget * 0.05).toInt().coerceAtLeast(5)
                    } else 0
                }
            }

            safetyBudget[zone] = allocation
        }

        return safetyBudget
    }
}