package com.madanala.tern.overlays

import android.util.Log
import org.osmdroid.util.GeoPoint

/**
 * Flight phase-aware prioritizer that dynamically adjusts overlay priorities based on
 * current flight context for optimal paraglider safety and performance.
 *
 * Uses GPS data, sensor information, and flight characteristics to determine:
 * - Current flight phase (launch/thermal/glide/landing/cruising)
 * - Appropriate overlay priorities for each phase
 * - Dynamic priority adjustments based on real-time conditions
 */
class FlightPhaseAwarePrioritizer(
    private val pilotPreferences: PilotPreferences = PilotPreferences()
) {

    private val TAG = "FlightPhasePrioritizer"

    /**
     * Pilot preferences for customizing overlay behavior
     */
    data class PilotPreferences(
        val showThermalSources: Boolean = true,
        val showLandingOptions: Boolean = true,
        val showTrainingAreas: Boolean = true,
        val competitionMode: Boolean = false,
        val conservativeMode: Boolean = false,  // Extra safety margins for students
        val maxOverlayCount: Int = 150
    )

    /**
     * Flight context information for intelligent prioritization
     */
    data class FlightContext(
        val currentPhase: FlightPhase,
        val altitudeAGL: Int,           // Feet above ground level
        val altitudeMSL: Int,           // Feet above sea level
        val groundSpeed: Double,        // km/h
        val verticalSpeed: Double,      // m/s (positive = climbing)
        val location: GeoPoint,
        val thermalActivity: ThermalStrength,
        val nearbyPilots: Int,          // Other paragliders detected
        val windSpeed: Double,          // km/h
        val windDirection: Int,         // Degrees
        val timeOfDay: Int,             // Hour (0-23)
        val competitionMode: Boolean = false
    )

    /**
     * Get dynamic priority adjustments for the current flight context
     */
    fun getDynamicPriorityAdjustments(context: FlightContext): Map<ParagliderAirspacePriority, Double> {
        Log.d(TAG, "Calculating dynamic priorities for phase: ${context.currentPhase}, " +
              "altitude: ${context.altitudeAGL}ft, thermal: ${context.thermalActivity}")

        val baseAdjustments = getBasePhaseAdjustments(context.currentPhase)

        // Apply contextual modifications
        val contextualAdjustments = applyContextualModifications(baseAdjustments, context)

        // Apply pilot preference adjustments
        val preferenceAdjustments = applyPreferenceAdjustments(contextualAdjustments, context)

        Log.d(TAG, "Priority adjustments calculated for ${preferenceAdjustments.size} overlay types")

        return preferenceAdjustments
    }

    /**
     * Get base priority adjustments for each flight phase
     */
    private fun getBasePhaseAdjustments(flightPhase: FlightPhase): Map<ParagliderAirspacePriority, Double> {
        return when (flightPhase) {
            FlightPhase.LAUNCH -> mapOf(
                ParagliderAirspacePriority.DANGER_AREAS to 1.0,
                ParagliderAirspacePriority.PARACHUTE_ZONES to 0.9,
                ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS to 0.9,
                ParagliderAirspacePriority.TRAINING_AREAS to 0.8,
                ParagliderAirspacePriority.TERRAIN_HAZARDS to 0.8,
                ParagliderAirspacePriority.LANDING_OPTIONS to 0.7,
                ParagliderAirspacePriority.CONTROLLED_AIRSPACE to 0.6,
                ParagliderAirspacePriority.WEATHER_AVOIDANCE to 0.5,
                ParagliderAirspacePriority.GLIDER_SITES to 0.3,
                ParagliderAirspacePriority.THERMAL_SOURCES to 0.2,
                ParagliderAirspacePriority.COMPETITION_AREAS to 0.1,
                ParagliderAirspacePriority.RESTRICTED_AREAS to 0.0,
                ParagliderAirspacePriority.AIRWAYS to -0.5,
                ParagliderAirspacePriority.REPORTING_POINTS to -0.5,
                ParagliderAirspacePriority.NAVIGATION_AIDS to -0.5,
                ParagliderAirspacePriority.CIVIL_AIRPORTS to -0.3
            )
            FlightPhase.THERMAL -> mapOf(
                ParagliderAirspacePriority.DANGER_AREAS to 1.0,
                ParagliderAirspacePriority.WEATHER_AVOIDANCE to 0.9,
                ParagliderAirspacePriority.THERMAL_SOURCES to 0.9,
                ParagliderAirspacePriority.TRAINING_AREAS to 0.8,
                ParagliderAirspacePriority.GLIDER_SITES to 0.8,
                ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS to 0.7,
                ParagliderAirspacePriority.CONTROLLED_AIRSPACE to 0.4,
                ParagliderAirspacePriority.TERRAIN_HAZARDS to 0.3,
                ParagliderAirspacePriority.LANDING_OPTIONS to 0.3,
                ParagliderAirspacePriority.COMPETITION_AREAS to 0.2,
                ParagliderAirspacePriority.RESTRICTED_AREAS to 0.1,
                ParagliderAirspacePriority.PARACHUTE_ZONES to 0.1,
                ParagliderAirspacePriority.AIRWAYS to -0.5,
                ParagliderAirspacePriority.REPORTING_POINTS to -0.5,
                ParagliderAirspacePriority.NAVIGATION_AIDS to -0.5,
                ParagliderAirspacePriority.CIVIL_AIRPORTS to -0.3
            )
            FlightPhase.GLIDE -> mapOf(
                ParagliderAirspacePriority.DANGER_AREAS to 1.0,
                ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS to 0.9,
                ParagliderAirspacePriority.LANDING_OPTIONS to 0.9,
                ParagliderAirspacePriority.TERRAIN_HAZARDS to 0.8,
                ParagliderAirspacePriority.CONTROLLED_AIRSPACE to 0.6,
                ParagliderAirspacePriority.WEATHER_AVOIDANCE to 0.5,
                ParagliderAirspacePriority.GLIDER_SITES to 0.4,
                ParagliderAirspacePriority.THERMAL_SOURCES to 0.3,
                ParagliderAirspacePriority.TRAINING_AREAS to 0.2,
                ParagliderAirspacePriority.COMPETITION_AREAS to 0.2,
                ParagliderAirspacePriority.RESTRICTED_AREAS to 0.1,
                ParagliderAirspacePriority.PARACHUTE_ZONES to 0.1,
                ParagliderAirspacePriority.AIRWAYS to -0.5,
                ParagliderAirspacePriority.REPORTING_POINTS to -0.5,
                ParagliderAirspacePriority.NAVIGATION_AIDS to -0.5,
                ParagliderAirspacePriority.CIVIL_AIRPORTS to -0.2
            )
            FlightPhase.LANDING -> mapOf(
                ParagliderAirspacePriority.DANGER_AREAS to 1.0,
                ParagliderAirspacePriority.PARACHUTE_ZONES to 0.9,
                ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS to 0.9,
                ParagliderAirspacePriority.TERRAIN_HAZARDS to 0.9,
                ParagliderAirspacePriority.LANDING_OPTIONS to 0.9,
                ParagliderAirspacePriority.CONTROLLED_AIRSPACE to 0.7,
                ParagliderAirspacePriority.TRAINING_AREAS to 0.5,
                ParagliderAirspacePriority.WEATHER_AVOIDANCE to 0.5,
                ParagliderAirspacePriority.GLIDER_SITES to 0.3,
                ParagliderAirspacePriority.THERMAL_SOURCES to 0.2,
                ParagliderAirspacePriority.COMPETITION_AREAS to 0.2,
                ParagliderAirspacePriority.RESTRICTED_AREAS to 0.1,
                ParagliderAirspacePriority.AIRWAYS to -0.5,
                ParagliderAirspacePriority.REPORTING_POINTS to -0.5,
                ParagliderAirspacePriority.NAVIGATION_AIDS to -0.5,
                ParagliderAirspacePriority.CIVIL_AIRPORTS to -0.1
            )
            FlightPhase.CRUISING -> mapOf(
                ParagliderAirspacePriority.DANGER_AREAS to 1.0,
                ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS to 0.9,
                ParagliderAirspacePriority.RESTRICTED_AREAS to 0.8,
                ParagliderAirspacePriority.COMPETITION_AREAS to 0.8,
                ParagliderAirspacePriority.WEATHER_AVOIDANCE to 0.7,
                ParagliderAirspacePriority.CONTROLLED_AIRSPACE to 0.5,
                ParagliderAirspacePriority.TRAINING_AREAS to 0.4,
                ParagliderAirspacePriority.GLIDER_SITES to 0.3,
                ParagliderAirspacePriority.THERMAL_SOURCES to 0.3,
                ParagliderAirspacePriority.TERRAIN_HAZARDS to 0.2,
                ParagliderAirspacePriority.LANDING_OPTIONS to 0.2,
                ParagliderAirspacePriority.PARACHUTE_ZONES to 0.1,
                ParagliderAirspacePriority.AIRWAYS to -0.5,
                ParagliderAirspacePriority.REPORTING_POINTS to -0.5,
                ParagliderAirspacePriority.NAVIGATION_AIDS to -0.5,
                ParagliderAirspacePriority.CIVIL_AIRPORTS to -0.3
            )
        }
    }

    /**
     * Apply contextual modifications based on real-time flight conditions
     */
    private fun applyContextualModifications(
        baseAdjustments: Map<ParagliderAirspacePriority, Double>,
        context: FlightContext
    ): Map<ParagliderAirspacePriority, Double> {

        val modifications = baseAdjustments.toMutableMap()

        // Altitude-based adjustments
        modifications.applyAltitudeAdjustments(context.altitudeAGL)

        // Thermal activity adjustments
        modifications.applyThermalAdjustments(context.thermalActivity)

        // Wind condition adjustments
        modifications.applyWindAdjustments(context.windSpeed)

        // Time of day adjustments
        modifications.applyTimeAdjustments(context.timeOfDay)

        // Competition mode adjustments
        if (context.competitionMode) {
            modifications.applyCompetitionAdjustments()
        }

        return modifications
    }

    /**
     * Apply altitude-based priority adjustments
     */
    private fun MutableMap<ParagliderAirspacePriority, Double>.applyAltitudeAdjustments(altitudeAGL: Int) {
        when {
            altitudeAGL < 500 -> {
                // Very low - prioritize landing and terrain
                this[ParagliderAirspacePriority.LANDING_OPTIONS] = (this[ParagliderAirspacePriority.LANDING_OPTIONS] ?: 0.0) + 0.3
                this[ParagliderAirspacePriority.TERRAIN_HAZARDS] = (this[ParagliderAirspacePriority.TERRAIN_HAZARDS] ?: 0.0) + 0.2
                this[ParagliderAirspacePriority.THERMAL_SOURCES] = (this[ParagliderAirspacePriority.THERMAL_SOURCES] ?: 0.0) - 0.2
            }
            altitudeAGL < 1000 -> {
                // Low altitude - balance landing and thermals
                this[ParagliderAirspacePriority.LANDING_OPTIONS] = (this[ParagliderAirspacePriority.LANDING_OPTIONS] ?: 0.0) + 0.2
                this[ParagliderAirspacePriority.THERMAL_SOURCES] = (this[ParagliderAirspacePriority.THERMAL_SOURCES] ?: 0.0) + 0.1
            }
            altitudeAGL > 8000 -> {
                // High altitude - reduce terrain relevance
                this[ParagliderAirspacePriority.TERRAIN_HAZARDS] = (this[ParagliderAirspacePriority.TERRAIN_HAZARDS] ?: 0.0) - 0.2
                this[ParagliderAirspacePriority.LANDING_OPTIONS] = (this[ParagliderAirspacePriority.LANDING_OPTIONS] ?: 0.0) - 0.1
            }
        }
    }

    /**
     * Apply thermal activity-based adjustments
     */
    private fun MutableMap<ParagliderAirspacePriority, Double>.applyThermalAdjustments(thermalActivity: ThermalStrength) {
        when (thermalActivity) {
            ThermalStrength.STRONG, ThermalStrength.EXTREME -> {
                this[ParagliderAirspacePriority.THERMAL_SOURCES] = (this[ParagliderAirspacePriority.THERMAL_SOURCES] ?: 0.0) + 0.3
                this[ParagliderAirspacePriority.GLIDER_SITES] = (this[ParagliderAirspacePriority.GLIDER_SITES] ?: 0.0) + 0.2
                this[ParagliderAirspacePriority.TRAINING_AREAS] = (this[ParagliderAirspacePriority.TRAINING_AREAS] ?: 0.0) + 0.1
            }
            ThermalStrength.MODERATE -> {
                this[ParagliderAirspacePriority.THERMAL_SOURCES] = (this[ParagliderAirspacePriority.THERMAL_SOURCES] ?: 0.0) + 0.2
                this[ParagliderAirspacePriority.GLIDER_SITES] = (this[ParagliderAirspacePriority.GLIDER_SITES] ?: 0.0) + 0.1
            }
            ThermalStrength.NONE -> {
                this[ParagliderAirspacePriority.THERMAL_SOURCES] = (this[ParagliderAirspacePriority.THERMAL_SOURCES] ?: 0.0) - 0.2
                this[ParagliderAirspacePriority.GLIDER_SITES] = (this[ParagliderAirspacePriority.GLIDER_SITES] ?: 0.0) - 0.1
            }
            ThermalStrength.WEAK -> {
                // Minimal adjustment for weak thermals
            }
        }
    }

    /**
     * Apply wind condition adjustments
     */
    private fun MutableMap<ParagliderAirspacePriority, Double>.applyWindAdjustments(windSpeed: Double) {
        when {
            windSpeed > 30 -> {
                // Strong winds - prioritize weather avoidance
                this[ParagliderAirspacePriority.WEATHER_AVOIDANCE] = (this[ParagliderAirspacePriority.WEATHER_AVOIDANCE] ?: 0.0) + 0.2
                this[ParagliderAirspacePriority.LANDING_OPTIONS] = (this[ParagliderAirspacePriority.LANDING_OPTIONS] ?: 0.0) + 0.1
            }
            windSpeed < 5 -> {
                // Light winds - good for thermaling
                this[ParagliderAirspacePriority.THERMAL_SOURCES] = (this[ParagliderAirspacePriority.THERMAL_SOURCES] ?: 0.0) + 0.1
            }
        }
    }

    /**
     * Apply time of day adjustments
     */
    private fun MutableMap<ParagliderAirspacePriority, Double>.applyTimeAdjustments(hour: Int) {
        when (hour) {
            in 10..16 -> {
                // Prime thermal time - boost thermal-related overlays
                this[ParagliderAirspacePriority.THERMAL_SOURCES] = (this[ParagliderAirspacePriority.THERMAL_SOURCES] ?: 0.0) + 0.1
                this[ParagliderAirspacePriority.GLIDER_SITES] = (this[ParagliderAirspacePriority.GLIDER_SITES] ?: 0.0) + 0.1
            }
            in 18..20 -> {
                // Evening - approaching sunset, prioritize landing
                this[ParagliderAirspacePriority.LANDING_OPTIONS] = (this[ParagliderAirspacePriority.LANDING_OPTIONS] ?: 0.0) + 0.2
            }
        }
    }

    /**
     * Apply competition mode adjustments
     */
    private fun MutableMap<ParagliderAirspacePriority, Double>.applyCompetitionAdjustments() {
        this[ParagliderAirspacePriority.COMPETITION_AREAS] = (this[ParagliderAirspacePriority.COMPETITION_AREAS] ?: 0.0) + 0.3
        this[ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS] = (this[ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS] ?: 0.0) + 0.2
        this[ParagliderAirspacePriority.TRAINING_AREAS] = (this[ParagliderAirspacePriority.TRAINING_AREAS] ?: 0.0) - 0.2
        this[ParagliderAirspacePriority.CONTROLLED_AIRSPACE] = (this[ParagliderAirspacePriority.CONTROLLED_AIRSPACE] ?: 0.0) - 0.1
    }

    /**
     * Apply pilot preference adjustments
     */
    private fun applyPreferenceAdjustments(
        adjustments: Map<ParagliderAirspacePriority, Double>,
        context: FlightContext
    ): Map<ParagliderAirspacePriority, Double> {

        val modified = adjustments.toMutableMap()

        // Apply individual preferences
        if (!pilotPreferences.showThermalSources) {
            modified[ParagliderAirspacePriority.THERMAL_SOURCES] = -0.5
        }

        if (!pilotPreferences.showLandingOptions) {
            modified[ParagliderAirspacePriority.LANDING_OPTIONS] = -0.5
        }

        if (!pilotPreferences.showTrainingAreas) {
            modified[ParagliderAirspacePriority.TRAINING_AREAS] = -0.5
        }

        if (pilotPreferences.conservativeMode) {
            // Boost all safety-related overlays
            ParagliderAirspacePriority.getSafetyCriticalPriorities().forEach { priority ->
                modified[priority] = (modified[priority] ?: 0.0) + 0.2
            }
        }

        if (pilotPreferences.competitionMode) {
            modified.applyCompetitionAdjustments()
        }

        return modified
    }

    /**
     * Calculate recommended overlay count for current context
     */
    fun calculateRecommendedOverlayCount(context: FlightContext): Int {
        val baseCount = when (context.currentPhase) {
            FlightPhase.LAUNCH -> 120
            FlightPhase.LANDING -> 130
            FlightPhase.THERMAL -> 100
            FlightPhase.GLIDE -> 90
            FlightPhase.CRUISING -> 80
        }

        // Adjust based on altitude density
        val altitudeAdjustment = when {
            context.altitudeAGL < 1000 -> 20  // More overlays when low
            context.altitudeAGL > 5000 -> -20 // Fewer overlays when high
            else -> 0
        }

        // Adjust based on thermal activity
        val thermalAdjustment = when (context.thermalActivity) {
            ThermalStrength.STRONG, ThermalStrength.EXTREME -> 15
            ThermalStrength.MODERATE -> 10
            else -> 0
        }

        val recommended = baseCount + altitudeAdjustment + thermalAdjustment

        return recommended.coerceIn(50, pilotPreferences.maxOverlayCount)
    }

    /**
     * Get prioritization explanation for debugging
     */
    fun getPrioritizationExplanation(context: FlightContext): String {
        val adjustments = getDynamicPriorityAdjustments(context)
        val topPriorities = adjustments.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString(", ") { "${it.key.name}(%.1f)".format(it.value) }

        return "Flight Phase: ${context.currentPhase}, " +
               "Top Priorities: $topPriorities, " +
               "Thermal: ${context.thermalActivity}, " +
               "Altitude: ${context.altitudeAGL}ft"
    }
}