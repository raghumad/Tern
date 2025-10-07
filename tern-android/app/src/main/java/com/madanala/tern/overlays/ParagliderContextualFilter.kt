package com.madanala.tern.overlays

import android.util.Log
import kotlin.math.abs
import org.osmdroid.util.GeoPoint

/**
 * Contextual filter for paraglider overlays that makes intelligent filtering decisions
 * based on real-time flight conditions, pilot preferences, and aviation safety requirements.
 *
 * Key features:
 * - Flight phase detection and appropriate filtering
 * - Altitude-based overlay relevance
 * - Thermal activity correlation
 * - Geographic context awareness
 * - Performance-optimized filtering
 */
class ParagliderContextualFilter(
    private val flightContext: ParagliderFlightContext,
    private val filterConfig: FilterConfig = FilterConfig()
) {

    /**
     * Flight context information for intelligent filtering decisions
     */
    data class ParagliderFlightContext(
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
        val competitionMode: Boolean = false,
        val pilotSkillLevel: PilotSkillLevel = PilotSkillLevel.INTERMEDIATE,
        val performanceMode: PerformanceMode = PerformanceMode.BALANCED
    )

    /**
     * Configuration for filter behavior
     */
    data class FilterConfig(
        val enableAltitudeFiltering: Boolean = true,
        val enableThermalFiltering: Boolean = true,
        val enableGeographicFiltering: Boolean = true,
        val enableTimeBasedFiltering: Boolean = true,
        val maxOverlayDistanceKm: Double = 100.0,
        val minAltitudeRelevance: Int = 500,  // ft
        val thermalRelevanceThreshold: ThermalStrength = ThermalStrength.WEAK
    )

    enum class PilotSkillLevel {
        STUDENT, RECREATIONAL, INTERMEDIATE, COMPETITION
    }

    enum class PerformanceMode {
        MAXIMUM_SAFETY, BALANCED, HIGH_PERFORMANCE
    }

    private val TAG = "ParagliderContextualFilter"

    /**
     * Determine if an overlay should be shown based on current flight context
     */
    fun shouldShowOverlay(overlay: ParagliderOverlayInfo): Boolean {
        Log.v(TAG, "Evaluating overlay: ${overlay.id} (${overlay.priority.name}) for phase: ${flightContext.currentPhase}")

        // Apply all contextual filters
        val altitudeCheck = if (filterConfig.enableAltitudeFiltering) {
            checkAltitudeRelevance(overlay)
        } else true

        val thermalCheck = if (filterConfig.enableThermalFiltering) {
            checkThermalRelevance(overlay)
        } else true

        val geographicCheck = if (filterConfig.enableGeographicFiltering) {
            checkGeographicRelevance(overlay)
        } else true

        val timeCheck = if (filterConfig.enableTimeBasedFiltering) {
            checkTimeRelevance(overlay)
        } else true

        val contextCheck = checkFlightContextRelevance(overlay)

        val shouldShow = altitudeCheck && thermalCheck && geographicCheck && timeCheck && contextCheck

        Log.v(TAG, "Overlay ${overlay.id} visibility: " +
              "altitude=$altitudeCheck, thermal=$thermalCheck, geographic=$geographicCheck, " +
              "time=$timeCheck, context=$contextCheck → $shouldShow")

        return shouldShow
    }

    /**
     * Check if overlay is relevant at current altitude
     */
    private fun checkAltitudeRelevance(overlay: ParagliderOverlayInfo): Boolean {
        val currentAltitude = flightContext.altitudeAGL

        // Critical safety overlays are always shown regardless of altitude
        if (overlay.priority in ParagliderAirspacePriority.getSafetyCriticalPriorities()) {
            return true
        }

        return when (overlay.priority) {
            ParagliderAirspacePriority.LANDING_OPTIONS -> {
                // Show landing options when low or in glide/landing phases
                currentAltitude < 2000 ||
                flightContext.currentPhase in listOf(FlightPhase.GLIDE, FlightPhase.LANDING)
            }
            ParagliderAirspacePriority.THERMAL_SOURCES -> {
                // Show thermal sources when in appropriate altitude range and thermaling
                currentAltitude in 500..8000 &&
                (flightContext.currentPhase == FlightPhase.THERMAL ||
                 flightContext.thermalActivity >= ThermalStrength.MODERATE)
            }
            ParagliderAirspacePriority.TERRAIN_HAZARDS -> {
                // Show terrain hazards when low
                currentAltitude < 3000
            }
            ParagliderAirspacePriority.TRAINING_AREAS -> {
                // Show training areas when in thermal conditions or student pilot
                flightContext.thermalActivity >= ThermalStrength.MODERATE ||
                flightContext.pilotSkillLevel in listOf(PilotSkillLevel.STUDENT, PilotSkillLevel.RECREATIONAL)
            }
            ParagliderAirspacePriority.GLIDER_SITES -> {
                // Show glider sites during prime thermal hours or when thermaling
                (flightContext.timeOfDay in 10..16) ||
                flightContext.currentPhase == FlightPhase.THERMAL
            }
            ParagliderAirspacePriority.COMPETITION_AREAS -> {
                // Show only in competition mode
                flightContext.competitionMode
            }
            ParagliderAirspacePriority.CONTROLLED_AIRSPACE -> {
                // Show controlled airspace when it matters for current altitude
                val inControlledAltitude = when {
                    overlay.altitudeRange.minAltitude != null &&
                    overlay.altitudeRange.maxAltitude != null ->
                        currentAltitude in overlay.altitudeRange.minAltitude..overlay.altitudeRange.maxAltitude
                    overlay.altitudeRange.minAltitude != null ->
                        currentAltitude >= overlay.altitudeRange.minAltitude
                    overlay.altitudeRange.maxAltitude != null ->
                        currentAltitude <= overlay.altitudeRange.maxAltitude
                    else -> true
                }
                inControlledAltitude && currentAltitude <= 5000
            }
            else -> true // Show others by default
        }
    }

    /**
     * Check if overlay is relevant for current thermal conditions
     */
    private fun checkThermalRelevance(overlay: ParagliderOverlayInfo): Boolean {
        // Safety critical overlays always shown
        if (overlay.priority in ParagliderAirspacePriority.getSafetyCriticalPriorities()) {
            return true
        }

        return when (overlay.priority) {
            ParagliderAirspacePriority.THERMAL_SOURCES -> {
                flightContext.thermalActivity >= filterConfig.thermalRelevanceThreshold
            }
            ParagliderAirspacePriority.GLIDER_SITES -> {
                flightContext.thermalActivity >= ThermalStrength.MODERATE ||
                flightContext.timeOfDay in 10..16
            }
            ParagliderAirspacePriority.TRAINING_AREAS -> {
                flightContext.thermalActivity >= ThermalStrength.MODERATE ||
                flightContext.pilotSkillLevel in listOf(PilotSkillLevel.STUDENT, PilotSkillLevel.RECREATIONAL)
            }
            else -> true
        }
    }

    /**
     * Check if overlay is geographically relevant
     */
    private fun checkGeographicRelevance(overlay: ParagliderOverlayInfo): Boolean {
        // Calculate distance from current location to overlay
        val distance = flightContext.location.distanceToAsDouble(overlay.centroid) / 1000.0 // Convert to km

        // Check if overlay is within maximum distance
        if (distance > filterConfig.maxOverlayDistanceKm) {
            return false
        }

        // Apply distance-based relevance for different overlay types
        return when (overlay.priority) {
            ParagliderAirspacePriority.LANDING_OPTIONS -> {
                // Landing options are relevant within 10km when low
                distance <= 10.0 || flightContext.altitudeAGL < 1500
            }
            ParagliderAirspacePriority.THERMAL_SOURCES -> {
                // Thermal sources are relevant within 20km during thermaling
                distance <= 20.0 || flightContext.currentPhase == FlightPhase.THERMAL
            }
            ParagliderAirspacePriority.TRAINING_AREAS -> {
                // Training areas within 15km
                distance <= 15.0
            }
            ParagliderAirspacePriority.TERRAIN_HAZARDS -> {
                // Terrain hazards within 5km when low
                distance <= 5.0 || flightContext.altitudeAGL < 1000
            }
            else -> {
                // Other overlays within standard distance
                distance <= filterConfig.maxOverlayDistanceKm * 0.7
            }
        }
    }

    /**
     * Check if overlay is relevant for current time conditions
     */
    private fun checkTimeRelevance(overlay: ParagliderOverlayInfo): Boolean {
        val hour = flightContext.timeOfDay

        return when (overlay.priority) {
            ParagliderAirspacePriority.THERMAL_SOURCES -> {
                // Thermal sources during prime thermal time
                hour in 9..17
            }
            ParagliderAirspacePriority.GLIDER_SITES -> {
                // Glider sites during flying hours
                hour in 8..18
            }
            ParagliderAirspacePriority.TRAINING_AREAS -> {
                // Training areas during typical training hours
                hour in 9..16
            }
            ParagliderAirspacePriority.LANDING_OPTIONS -> {
                // Landing options always relevant, but especially in evening
                hour <= 20
            }
            else -> true // Others not time-sensitive
        }
    }

    /**
     * Check overall flight context relevance
     */
    private fun checkFlightContextRelevance(overlay: ParagliderOverlayInfo): Boolean {
        return when (flightContext.currentPhase) {
            FlightPhase.LAUNCH -> {
                // During launch, prioritize immediate safety and landing options
                when (overlay.priority) {
                    ParagliderAirspacePriority.DANGER_AREAS,
                    ParagliderAirspacePriority.PARACHUTE_ZONES,
                    ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS,
                    ParagliderAirspacePriority.TERRAIN_HAZARDS,
                    ParagliderAirspacePriority.LANDING_OPTIONS -> true
                    ParagliderAirspacePriority.TRAINING_AREAS -> {
                        flightContext.pilotSkillLevel in listOf(PilotSkillLevel.STUDENT, PilotSkillLevel.RECREATIONAL)
                    }
                    else -> false // Hide non-essential during launch
                }
            }
            FlightPhase.THERMAL -> {
                // During thermaling, prioritize thermal-related overlays
                when (overlay.priority) {
                    ParagliderAirspacePriority.THERMAL_SOURCES,
                    ParagliderAirspacePriority.GLIDER_SITES,
                    ParagliderAirspacePriority.TRAINING_AREAS -> true
                    ParagliderAirspacePriority.DANGER_AREAS,
                    ParagliderAirspacePriority.WEATHER_AVOIDANCE -> true
                    else -> {
                        // Show others only if not crowded
                        flightContext.nearbyPilots < 5
                    }
                }
            }
            FlightPhase.GLIDE -> {
                // During glide, focus on path ahead and landing
                when (overlay.priority) {
                    ParagliderAirspacePriority.LANDING_OPTIONS,
                    ParagliderAirspacePriority.TERRAIN_HAZARDS,
                    ParagliderAirspacePriority.DANGER_AREAS,
                    ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS -> true
                    ParagliderAirspacePriority.THERMAL_SOURCES -> {
                        // Show thermals only if actively looking for lift
                        abs(flightContext.verticalSpeed) > 1.0
                    }
                    else -> false
                }
            }
            FlightPhase.LANDING -> {
                // During landing, only show critical safety information
                overlay.priority in setOf(
                    ParagliderAirspacePriority.DANGER_AREAS,
                    ParagliderAirspacePriority.PARACHUTE_ZONES,
                    ParagliderAirspacePriority.TEMPORARY_RESTRICTIONS,
                    ParagliderAirspacePriority.TERRAIN_HAZARDS,
                    ParagliderAirspacePriority.LANDING_OPTIONS,
                    ParagliderAirspacePriority.CONTROLLED_AIRSPACE
                )
            }
            FlightPhase.CRUISING -> {
                // During cruising, balanced view based on skill level
                when (flightContext.pilotSkillLevel) {
                    PilotSkillLevel.STUDENT -> {
                        // Students see more overlays
                        overlay.priority.reductionOrder <= 10
                    }
                    PilotSkillLevel.RECREATIONAL -> {
                        // Recreational pilots see moderate overlays
                        overlay.priority.reductionOrder <= 8
                    }
                    PilotSkillLevel.INTERMEDIATE -> {
                        // Intermediate pilots see essential overlays
                        overlay.priority.reductionOrder <= 6
                    }
                    PilotSkillLevel.COMPETITION -> {
                        // Competition pilots see minimal overlays
                        overlay.priority.reductionOrder <= 4
                    }
                }
            }
        }
    }

    /**
     * Get filter explanation for debugging
     */
    fun getFilterExplanation(overlay: ParagliderOverlayInfo): String {
        val reasons = mutableListOf<String>()

        if (!checkAltitudeRelevance(overlay)) reasons.add("altitude")
        if (!checkThermalRelevance(overlay)) reasons.add("thermal")
        if (!checkGeographicRelevance(overlay)) reasons.add("geographic")
        if (!checkTimeRelevance(overlay)) reasons.add("time")
        if (!checkFlightContextRelevance(overlay)) reasons.add("context")

        return if (reasons.isEmpty()) {
            "Show: All filters passed"
        } else {
            "Hide: ${reasons.joinToString(", ")}"
        }
    }

    /**
     * Apply bulk filtering to a list of overlays
     */
    fun filterOverlayList(overlays: List<ParagliderOverlayInfo>): List<ParagliderOverlayInfo> {
        Log.d(TAG, "Filtering ${overlays.size} overlays for ${flightContext.currentPhase} phase")

        val filtered = overlays.filter { shouldShowOverlay(it) }

        Log.d(TAG, "Filtered ${overlays.size} → ${filtered.size} overlays")

        return filtered
    }

    /**
     * Get filtering statistics for performance monitoring
     */
    fun getFilteringStats(originalCount: Int, filteredCount: Int): Map<String, Any> {
        return mapOf(
            "original_overlays" to originalCount,
            "filtered_overlays" to filteredCount,
            "filter_ratio" to (filteredCount.toDouble() / originalCount.toDouble()),
            "flight_phase" to flightContext.currentPhase.name,
            "altitude_agl" to flightContext.altitudeAGL,
            "thermal_activity" to flightContext.thermalActivity.name,
            "pilot_skill_level" to flightContext.pilotSkillLevel.name
        )
    }
}