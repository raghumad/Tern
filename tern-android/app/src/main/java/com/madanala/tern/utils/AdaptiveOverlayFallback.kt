package com.madanala.tern.utils

import android.util.Log

/**
 * Graceful fallback system for adaptive overlay management
 * Provides safe, hardcoded defaults when adaptive system fails
 */
object AdaptiveOverlayFallback {

    // Legacy hardcoded limits (safe defaults)
    const val LEGACY_MAX_AIRSPACES = 150
    const val LEGACY_MAX_VIEWPORT_AIRSPACES = 100
    const val LEGACY_MAX_PG_SPOTS = 50

    // Fallback zone allocation (conservative but safe)
    val FALLBACK_ZONE_BUDGETS = mapOf(
        DistanceZone.CORE to 75,    // 50% - Safety critical
        DistanceZone.NEAR to 45,    // 30% - Important area
        DistanceZone.MID to 22,     // 15% - Regional context
        DistanceZone.FAR to 8,      // 5% - Extended awareness
        DistanceZone.EXTREME to 0   // 0% - Memory pressure
    )

    /**
     * Get fallback overlay budget when adaptive system fails
     */
    fun getFallbackOverlayBudget(
        overlayType: String = "unknown",
        flightPhase: FlightPhase = FlightPhase.LAUNCH
    ): OverlayBudget {

        val totalBudget = when (overlayType) {
            "airspace" -> LEGACY_MAX_AIRSPACES
            "pg_spot" -> LEGACY_MAX_PG_SPOTS
            else -> LEGACY_MAX_AIRSPACES // Default fallback
        }

        // Apply flight phase multiplier to fallback budget
        val adjustedBudget = (totalBudget * getFlightPhaseMultiplier(flightPhase)).toInt()

        return OverlayBudget(
            totalOverlays = adjustedBudget,
            memoryPressure = MemoryPressureLevel.LOW_MEMORY, // Conservative fallback
            flightPhase = flightPhase,
            zoneBudgets = FALLBACK_ZONE_BUDGETS,
            recommendation = "Using fallback budget (adaptive system unavailable)"
        )
    }

    /**
     * Get fallback memory pressure level when monitoring fails
     */
    fun getFallbackMemoryPressure(): MemoryPressureLevel {
        return MemoryPressureLevel.LOW_MEMORY // Conservative fallback
    }

    /**
     * Get fallback zone allocation for specific overlay type
     */
    fun getFallbackZoneBudgets(overlayType: String): Map<DistanceZone, Int> {
        return when (overlayType) {
            "airspace" -> mapOf(
                DistanceZone.CORE to LEGACY_MAX_VIEWPORT_AIRSPACES,
                DistanceZone.NEAR to 30,
                DistanceZone.MID to 15,
                DistanceZone.FAR to 5,
                DistanceZone.EXTREME to 0
            )
            "pg_spot" -> mapOf(
                DistanceZone.CORE to 25,
                DistanceZone.NEAR to LEGACY_MAX_PG_SPOTS,
                DistanceZone.MID to 0,
                DistanceZone.FAR to 0,
                DistanceZone.EXTREME to 0
            )
            else -> FALLBACK_ZONE_BUDGETS
        }
    }

    /**
     * Get flight phase multiplier for fallback calculations
     */
    private fun getFlightPhaseMultiplier(flightPhase: FlightPhase): Double {
        return when (flightPhase) {
            FlightPhase.LAUNCH -> 1.0
            FlightPhase.THERMAL -> 1.1
            FlightPhase.LANDING -> 1.2
            FlightPhase.GLIDE -> 0.9
            FlightPhase.CRUISING -> 0.8
        }
    }

    /**
     * Check if fallback system should be used instead of adaptive system
     */
    fun shouldUseFallback(adaptiveBudget: OverlayBudget?): Boolean {
        return adaptiveBudget == null ||
               adaptiveBudget.totalOverlays <= 0 ||
               adaptiveBudget.zoneBudgets.values.sum() <= 0
    }

    /**
     * Get safe distance zone for a given distance when zone calculation fails
     */
    fun getFallbackDistanceZone(distanceKm: Double): DistanceZone {
        return try {
            DistanceZone.fromDistanceKm(distanceKm)
        } catch (e: Exception) {
            // Fallback to NEAR zone for safety
            DistanceZone.NEAR
        }
    }

    /**
     * Get emergency cleanup recommendation when adaptive system fails
     */
    fun getFallbackEmergencyCleanup(): com.madanala.tern.ui.overlays.EmergencyCleanupResult {
        return com.madanala.tern.ui.overlays.EmergencyCleanupResult(
            success = false,
            overlaysRemoved = 0,
            zonesCleared = emptyList(),
            safetyCriticalPreserved = 0,
            reason = "Adaptive system unavailable - using fallback"
        )
    }

    /**
     * Validate overlay budget and return fallback if invalid
     */
    fun validateOrFallback(
        budget: OverlayBudget?,
        overlayType: String,
        flightPhase: FlightPhase = FlightPhase.LAUNCH
    ): OverlayBudget {
        return if (shouldUseFallback(budget)) {
            Log.w("AdaptiveOverlayFallback", "Adaptive system failed, using fallback for $overlayType")
            getFallbackOverlayBudget(overlayType, flightPhase)
        } else {
            budget!!
        }
    }

    /**
     * Get aviation-safe minimum budgets (never go below these)
     */
    fun getAviationSafetyMinimums(): Map<DistanceZone, Int> {
        return mapOf(
            DistanceZone.CORE to 20,    // Always maintain minimum safety overlays
            DistanceZone.NEAR to 10,    // Keep essential nearby overlays
            DistanceZone.MID to 0,      // Can be cleared if needed
            DistanceZone.FAR to 0,      // Can be cleared if needed
            DistanceZone.EXTREME to 0   // Always clear
        )
    }

    /**
     * Check if current allocation violates aviation safety minimums
     */
    fun violatesAviationSafety(
        currentAllocation: Map<DistanceZone, Int>,
        memoryPressure: MemoryPressureLevel
    ): Boolean {
        val safetyMinimums = getAviationSafetyMinimums()

        // Check if CORE zone allocation is below safety minimum
        val coreAllocation = currentAllocation[DistanceZone.CORE] ?: 0
        val coreMinimum = safetyMinimums[DistanceZone.CORE] ?: 0

        return when (memoryPressure) {
            MemoryPressureLevel.CRITICAL_MEMORY -> coreAllocation < (coreMinimum * 0.5).toInt()
            MemoryPressureLevel.LOW_MEMORY -> coreAllocation < coreMinimum
            else -> false // Normal memory conditions don't violate safety
        }
    }
}