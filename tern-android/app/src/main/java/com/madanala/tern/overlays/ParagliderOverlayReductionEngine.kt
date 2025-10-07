package com.madanala.tern.overlays

import android.util.Log
import kotlin.math.abs
import org.osmdroid.util.GeoPoint

/**
 * Intelligent overlay reduction engine specifically designed for paraglider operations.
 *
 * Key differences from general aviation:
 * - Prioritizes training areas and parachute zones over airways
 * - Considers flight phase (launch/thermal/glide/landing/cruising)
 * - Altitude-aware filtering (landing options at low altitude)
 * - Thermal activity correlation
 * - Skill level adjustments (student vs competition pilot)
 */
class ParagliderOverlayReductionEngine(
    private val pilotSkillLevel: PilotSkillLevel = PilotSkillLevel.INTERMEDIATE,
    private val performanceMode: PerformanceMode = PerformanceMode.BALANCED
) {

    enum class PilotSkillLevel(val description: String, val reductionThreshold: Double) {
        STUDENT("Maximum safety overlays", 0.3),           // Show 70% of overlays
        RECREATIONAL("Balanced view", 0.5),               // Show 50% of overlays
        INTERMEDIATE("Performance optimized", 0.7),       // Show 30% of overlays
        COMPETITION("Minimal but critical only", 0.9)     // Show 10% of overlays
    }

    enum class PerformanceMode(val description: String, val targetReduction: Double) {
        MAXIMUM_SAFETY("Show all safety overlays", 0.2),   // Minimal reduction
        BALANCED("Balanced safety/performance", 0.5),     // Moderate reduction
        HIGH_PERFORMANCE("Performance prioritized", 0.8)  // Aggressive reduction
    }

    private val TAG = "ParagliderReductionEngine"

    /**
     * Apply intelligent overlay reduction based on paraglider priorities and flight context
     */
    fun applyParagliderReduction(
        overlays: List<ParagliderOverlayInfo>,
        targetCount: Int,
        currentLocation: GeoPoint,
        flightPhase: FlightPhase,
        currentAltitudeAGL: Int,
        thermalActivity: ThermalStrength = ThermalStrength.NONE
    ): List<ParagliderOverlayInfo> {

        Log.d(TAG, "Applying paraglider reduction: ${overlays.size} → $targetCount overlays " +
              "(Phase: $flightPhase, Altitude: ${currentAltitudeAGL}ft, Thermal: $thermalActivity)")

        if (overlays.size <= targetCount) {
            return overlays // No reduction needed
        }

        // Step 1: Always preserve critical safety overlays
        val safetyCritical = identifySafetyCriticalOverlays(overlays, flightPhase)
        var remainingQuota = targetCount - safetyCritical.size

        Log.d(TAG, "Safety critical overlays: ${safetyCritical.size}, Remaining quota: $remainingQuota")

        if (remainingQuota <= 0) {
            Log.w(TAG, "Only safety critical overlays fit in quota - reducing to critical only")
            return safetyCritical
        }

        // Step 2: Apply flight phase-specific filtering
        val phaseFiltered = applyFlightPhaseFiltering(
            overlays.filter { it !in safetyCritical },
            flightPhase,
            currentAltitudeAGL,
            thermalActivity,
            remainingQuota
        )

        val result = safetyCritical + phaseFiltered

        Log.d(TAG, "Reduction complete: ${overlays.size} → ${result.size} overlays " +
              "(${safetyCritical.size} safety critical, ${phaseFiltered.size} phase-filtered)")

        return result
    }

    /**
     * Identify overlays that are always safety-critical for paragliders
     */
    private fun identifySafetyCriticalOverlays(
        overlays: List<ParagliderOverlayInfo>,
        flightPhase: FlightPhase
    ): List<ParagliderOverlayInfo> {

        // Base safety critical priorities (never reduce)
        val baseCritical = setOf(
            ParagliderAirspacePriority.DANGER_AREAS,
            ParagliderAirspacePriority.RESTRICTED_AREAS,
            ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS,
            ParagliderAirspacePriority.PARACHUTE_ZONES
        )

        // Add phase-specific safety critical overlays
        val phaseCritical = when (flightPhase) {
            FlightPhase.LAUNCH -> setOf(
                ParagliderAirspacePriority.TERRAIN_HAZARDS,
                ParagliderAirspacePriority.LANDING_OPTIONS
            )
            FlightPhase.LANDING -> setOf(
                ParagliderAirspacePriority.TERRAIN_HAZARDS,
                ParagliderAirspacePriority.LANDING_OPTIONS
            )
            FlightPhase.THERMAL -> setOf(
                ParagliderAirspacePriority.WEATHER_AVOIDANCE
            )
            FlightPhase.GLIDE -> setOf(
                ParagliderAirspacePriority.LANDING_OPTIONS
            )
            FlightPhase.CRUISING -> setOf(
                ParagliderAirspacePriority.WEATHER_AVOIDANCE
            )
        }

        val allCriticalPriorities = baseCritical + phaseCritical

        return overlays.filter { it.priority in allCriticalPriorities }
    }

    /**
     * Apply flight phase-specific filtering with altitude and thermal awareness
     */
    private fun applyFlightPhaseFiltering(
        overlays: List<ParagliderOverlayInfo>,
        flightPhase: FlightPhase,
        currentAltitudeAGL: Int,
        thermalActivity: ThermalStrength,
        quota: Int
    ): List<ParagliderOverlayInfo> {

        // Score each overlay based on relevance to current flight context
        val scoredOverlays = overlays.map { overlay ->
            val score = calculateOverlayRelevanceScore(
                overlay, flightPhase, currentAltitudeAGL, thermalActivity
            )
            overlay to score
        }

        // Sort by relevance score (highest first) and take quota
        return scoredOverlays
            .sortedByDescending { it.second }
            .take(quota)
            .map { it.first }
    }

    /**
     * Calculate how relevant an overlay is to the current flight context
     */
    private fun calculateOverlayRelevanceScore(
        overlay: ParagliderOverlayInfo,
        flightPhase: FlightPhase,
        currentAltitudeAGL: Int,
        thermalActivity: ThermalStrength
    ): Double {

        var score = 0.5 // Base score

        // Flight phase relevance
        score += getFlightPhaseRelevanceBonus(overlay.priority, flightPhase)

        // Altitude relevance
        score += getAltitudeRelevanceBonus(overlay, currentAltitudeAGL)

        // Thermal activity relevance
        score += getThermalRelevanceBonus(overlay.priority, thermalActivity)

        // Distance relevance (closer = more relevant)
        score += getDistanceRelevanceBonus(overlay)

        // Pilot skill level adjustment
        score += getSkillLevelAdjustment(overlay.priority)

        // Performance mode adjustment
        score += getPerformanceModeAdjustment(overlay.priority)

        Log.v(TAG, "Overlay ${overlay.id} (${overlay.priority.name}) score: ${"%.2f".format(score)}")

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Get relevance bonus based on flight phase
     */
    private fun getFlightPhaseRelevanceBonus(
        priority: ParagliderAirspacePriority,
        flightPhase: FlightPhase
    ): Double {
        val phasePriorities = ParagliderAirspacePriority.getPrioritiesForFlightPhase(flightPhase)
        return when {
            priority in phasePriorities.take(3) -> 0.3  // Top 3 priorities for this phase
            priority in phasePriorities.take(6) -> 0.2  // Next 3 priorities
            priority in phasePriorities -> 0.1          // Remaining priorities
            else -> -0.2                                // Not relevant for this phase
        }
    }

    /**
     * Get relevance bonus based on current altitude
     */
    private fun getAltitudeRelevanceBonus(
        overlay: ParagliderOverlayInfo,
        currentAltitudeAGL: Int
    ): Double {

        // Special handling for landing-critical overlays when low
        if (currentAltitudeAGL < 1000 && overlay.priority == ParagliderAirspacePriority.LANDING_OPTIONS) {
            return 0.4
        }

        // Special handling for thermal sources when thermaling at appropriate altitudes
        if (currentAltitudeAGL in 500..6000 && overlay.priority == ParagliderAirspacePriority.THERMAL_SOURCES) {
            return 0.3
        }

        // Special handling for terrain hazards when low
        if (currentAltitudeAGL < 2000 && overlay.priority == ParagliderAirspacePriority.TERRAIN_HAZARDS) {
            return 0.3
        }

        // General altitude appropriateness
        val altitudeDiff = when {
            overlay.altitudeRange.minAltitude == null && overlay.altitudeRange.maxAltitude == null -> 0
            overlay.altitudeRange.minAltitude == null -> currentAltitudeAGL - (overlay.altitudeRange.maxAltitude ?: 0)
            overlay.altitudeRange.maxAltitude == null -> (overlay.altitudeRange.minAltitude ?: 0) - currentAltitudeAGL
            else -> {
                val minDiff = abs(currentAltitudeAGL - (overlay.altitudeRange.minAltitude ?: 0))
                val maxDiff = abs(currentAltitudeAGL - (overlay.altitudeRange.maxAltitude ?: 0))
                minOf(minDiff, maxDiff)
            }
        }

        // Bonus for being at appropriate altitude (within 1000ft)
        return when {
            altitudeDiff <= 500 -> 0.2
            altitudeDiff <= 1000 -> 0.1
            altitudeDiff <= 2000 -> 0.0
            else -> -0.1
        }
    }

    /**
     * Get relevance bonus based on thermal activity
     */
    private fun getThermalRelevanceBonus(
        priority: ParagliderAirspacePriority,
        thermalActivity: ThermalStrength
    ): Double {
        return when (priority) {
            ParagliderAirspacePriority.THERMAL_SOURCES -> when (thermalActivity) {
                ThermalStrength.STRONG, ThermalStrength.EXTREME -> 0.3
                ThermalStrength.MODERATE -> 0.2
                ThermalStrength.WEAK -> 0.1
                ThermalStrength.NONE -> -0.1
            }
            ParagliderAirspacePriority.GLIDER_SITES -> when (thermalActivity) {
                ThermalStrength.STRONG, ThermalStrength.EXTREME -> 0.2
                else -> 0.0
            }
            ParagliderAirspacePriority.TRAINING_AREAS -> when (thermalActivity) {
                ThermalStrength.MODERATE, ThermalStrength.STRONG -> 0.2
                else -> 0.0
            }
            else -> 0.0
        }
    }

    /**
     * Get relevance bonus based on distance from current location
     */
    private fun getDistanceRelevanceBonus(overlay: ParagliderOverlayInfo): Double {
        // This would need the current location passed in
        // For now, return neutral score
        return 0.0
    }

    /**
     * Get skill level adjustment for overlay relevance
     */
    private fun getSkillLevelAdjustment(priority: ParagliderAirspacePriority): Double {
        return when (pilotSkillLevel) {
            PilotSkillLevel.STUDENT -> {
                // Students need more information
                when (priority) {
                    ParagliderAirspacePriority.TRAINING_AREAS -> 0.2
                    ParagliderAirspacePriority.CONTROLLED_AIRSPACE -> 0.1
                    ParagliderAirspacePriority.LANDING_OPTIONS -> 0.2
                    else -> 0.0
                }
            }
            PilotSkillLevel.COMPETITION -> {
                // Competition pilots want minimal distractions
                when (priority) {
                    ParagliderAirspacePriority.COMPETITION_AREAS -> 0.1
                    ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS -> 0.1
                    else -> -0.1
                }
            }
            else -> 0.0 // Neutral for recreational and intermediate
        }
    }

    /**
     * Get performance mode adjustment
     */
    private fun getPerformanceModeAdjustment(priority: ParagliderAirspacePriority): Double {
        return when (performanceMode) {
            PerformanceMode.MAXIMUM_SAFETY -> {
                // Show more overlays for safety
                when (priority) {
                    ParagliderAirspacePriority.TRAINING_AREAS -> 0.1
                    ParagliderAirspacePriority.CONTROLLED_AIRSPACE -> 0.1
                    else -> 0.0
                }
            }
            PerformanceMode.HIGH_PERFORMANCE -> {
                // Aggressive reduction for performance
                when (priority) {
                    ParagliderAirspacePriority.LANDING_OPTIONS -> 0.0 // Keep only when actually needed
                    ParagliderAirspacePriority.GLIDER_SITES -> -0.1
                    ParagliderAirspacePriority.TRAINING_AREAS -> -0.1
                    else -> 0.0
                }
            }
            PerformanceMode.BALANCED -> 0.0 // Neutral
        }
    }

    /**
     * Get reduction statistics for debugging and monitoring
     */
    fun getReductionStats(
        originalCount: Int,
        reducedCount: Int,
        flightPhase: FlightPhase
    ): Map<String, Any> {
        return mapOf(
            "original_overlays" to originalCount,
            "reduced_overlays" to reducedCount,
            "reduction_ratio" to (reducedCount.toDouble() / originalCount.toDouble()),
            "flight_phase" to flightPhase.name,
            "pilot_skill_level" to pilotSkillLevel.name,
            "performance_mode" to performanceMode.name
        )
    }
}

/**
 * Thermal activity strength for contextual filtering
 */
enum class ThermalStrength {
    NONE,       // No thermal activity
    WEAK,       // Light thermals, <2 m/s
    MODERATE,   // Good soaring conditions, 2-4 m/s
    STRONG,     // Strong thermals, 4-6 m/s
    EXTREME     // Extreme conditions, >6 m/s
}