package com.madanala.tern.overlays

import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox

/**
 * Paraglider-specific airspace priority classification optimized for FAR 103 ultralight operations.
 *
 * This file serves as the main definition file for paraglider overlay types.
 * Other files import from this one to maintain consistency.
 */

/**
 * Paraglider-specific airspace priority classification optimized for FAR 103 ultralight operations.
 *
 * Paragliders have very different airspace concerns compared to powered aircraft:
 * - Not constrained by airways or reporting points
 * - Training areas and parachute zones are critical due to shared airspace
 * - Thermal sources and glider sites are important for flight optimization
 * - Landing options become critical at low altitudes
 */
enum class ParagliderAirspacePriority(
    val reductionOrder: Int,  // Lower number = reduced first during overload
    val safetyCritical: Boolean,  // Never reduce these for safety
    val description: String,
    val typicalAltitude: AltitudeRange? = null
) {
    // 🚨 PRIORITY 1: NEVER REDUCE - Critical Safety
    DANGER_AREAS(
        reductionOrder = 1,
        safetyCritical = true,
        description = "Military zones, prohibited areas - immediate danger to paragliders",
        typicalAltitude = AltitudeRange.SURFACE_TO_UNLIMITED
    ),
    RESTRICTED_AREAS(
        reductionOrder = 2,
        safetyCritical = true,
        description = "Competition zones, nature reserves - legal restrictions",
        typicalAltitude = AltitudeRange.SURFACE_TO_10000FT
    ),
    TEMPORARY_RESTRICTIONS(
        reductionOrder = 3,
        safetyCritical = true,
        description = "TFRs, NOTAM areas - temporary flight hazards",
        typicalAltitude = AltitudeRange.SURFACE_TO_UNLIMITED
    ),
    PARACHUTE_ZONES(
        reductionOrder = 4,
        safetyCritical = true,
        description = "Drop zones - collision risk at similar altitudes",
        typicalAltitude = AltitudeRange.SURFACE_TO_15000FT
    ),

    // ⚠️ PRIORITY 2: HIGH PRIORITY - Flight Critical
    TRAINING_AREAS(
        reductionOrder = 5,
        safetyCritical = false,
        description = "Glider training areas - shared airspace awareness",
        typicalAltitude = AltitudeRange.SURFACE_TO_8000FT
    ),
    COMPETITION_AREAS(
        reductionOrder = 6,
        safetyCritical = false,
        description = "Race courses, turnpoints - active event zones",
        typicalAltitude = AltitudeRange.SURFACE_TO_12000FT
    ),
    WEATHER_AVOIDANCE(
        reductionOrder = 7,
        safetyCritical = false,
        description = "Storm cells, icing areas - immediate threats",
        typicalAltitude = AltitudeRange.SURFACE_TO_UNLIMITED
    ),
    THERMAL_SOURCES(
        reductionOrder = 8,
        safetyCritical = false,
        description = "Known thermal hotspots - flight optimization",
        typicalAltitude = AltitudeRange.SURFACE_TO_6000FT
    ),

    // 📍 PRIORITY 3: MODERATE PRIORITY - Situational Awareness
    CONTROLLED_AIRSPACE(
        reductionOrder = 9,
        safetyCritical = false,
        description = "TMA, CTR - coordination requirements",
        typicalAltitude = AltitudeRange.SURFACE_TO_5000FT
    ),
    GLIDER_SITES(
        reductionOrder = 10,
        safetyCritical = false,
        description = "Known glider launch/landing sites",
        typicalAltitude = AltitudeRange.SURFACE_TO_3000FT
    ),
    TERRAIN_HAZARDS(
        reductionOrder = 11,
        safetyCritical = false,
        description = "Power lines, towers - collision avoidance",
        typicalAltitude = AltitudeRange.SURFACE_TO_2000FT
    ),
    LANDING_OPTIONS(
        reductionOrder = 12,
        safetyCritical = false,
        description = "Suitable landing fields - safety planning",
        typicalAltitude = AltitudeRange.SURFACE_TO_1000FT
    ),

    // ℹ️ PRIORITY 4: LOW PRIORITY - Reduce First
    AIRWAYS(
        reductionOrder = 13,
        safetyCritical = false,
        description = "Victor/Jet routes - not relevant for paragliders",
        typicalAltitude = AltitudeRange.FLIGHT_LEVELS
    ),
    REPORTING_POINTS(
        reductionOrder = 14,
        safetyCritical = false,
        description = "VRP, Compulsory points - powered aircraft only",
        typicalAltitude = AltitudeRange.FLIGHT_LEVELS
    ),
    NAVIGATION_AIDS(
        reductionOrder = 15,
        safetyCritical = false,
        description = "VOR, NDB stations - not used by paragliders",
        typicalAltitude = AltitudeRange.SURFACE_TO_5000FT
    ),
    CIVIL_AIRPORTS(
        reductionOrder = 16,
        safetyCritical = false,
        description = "Commercial airports - avoid but not critical",
        typicalAltitude = AltitudeRange.SURFACE_TO_3000FT
    );

    companion object {
        /**
         * Get all safety-critical priorities that should never be reduced
         */
        fun getSafetyCriticalPriorities(): Set<ParagliderAirspacePriority> {
            return values().filter { it.safetyCritical }.toSet()
        }

        /**
         * Get priorities appropriate for the given flight phase
         */
        fun getPrioritiesForFlightPhase(flightPhase: FlightPhase): List<ParagliderAirspacePriority> {
            return when (flightPhase) {
                FlightPhase.LAUNCH -> listOf(
                    DANGER_AREAS, PARACHUTE_ZONES, TEMPORARY_RESTRICTIONS,
                    TRAINING_AREAS, TERRAIN_HAZARDS, CONTROLLED_AIRSPACE, LANDING_OPTIONS
                )
                FlightPhase.THERMAL -> listOf(
                    DANGER_AREAS, WEATHER_AVOIDANCE, THERMAL_SOURCES,
                    TRAINING_AREAS, GLIDER_SITES
                )
                FlightPhase.GLIDE -> listOf(
                    DANGER_AREAS, TEMPORARY_RESTRICTIONS, LANDING_OPTIONS,
                    TERRAIN_HAZARDS, CONTROLLED_AIRSPACE
                )
                FlightPhase.LANDING -> listOf(
                    DANGER_AREAS, PARACHUTE_ZONES, TEMPORARY_RESTRICTIONS,
                    TERRAIN_HAZARDS, LANDING_OPTIONS, CONTROLLED_AIRSPACE
                )
                FlightPhase.CRUISING -> listOf(
                    DANGER_AREAS, TEMPORARY_RESTRICTIONS, RESTRICTED_AREAS,
                    COMPETITION_AREAS, WEATHER_AVOIDANCE, CONTROLLED_AIRSPACE
                )
            }
        }
    }
}

/**
 * Altitude range information for airspace types
 */
enum class AltitudeRange(
    val minAltitude: Int?,  // Feet AGL, null = surface
    val maxAltitude: Int?   // Feet AGL, null = unlimited
) {
    SURFACE_TO_1000FT(null, 1000),
    SURFACE_TO_2000FT(null, 2000),
    SURFACE_TO_3000FT(null, 3000),
    SURFACE_TO_5000FT(null, 5000),
    SURFACE_TO_6000FT(null, 6000),
    SURFACE_TO_8000FT(null, 8000),
    SURFACE_TO_10000FT(null, 10000),
    SURFACE_TO_12000FT(null, 12000),
    SURFACE_TO_15000FT(null, 15000),
    SURFACE_TO_UNLIMITED(null, null),
    FLIGHT_LEVELS(5000, null)  // Above typical paraglider altitudes
}

/**
 * Flight phase enumeration for context-aware priority adjustment
 */
enum class FlightPhase {
    LAUNCH,     // High power, detailed airspace awareness needed
    THERMAL,    // Circling, thermal sources and hazards important
    GLIDE,      // Transition, clear path and landing options needed
    LANDING,    // Approach, landing sites and obstacles critical
    CRUISING    // Cross-country, minimal overlays needed
}

/**
 * Data class for paraglider-specific overlay information
 */
data class ParagliderOverlayInfo(
    val id: String,
    val type: ParagliderOverlayType,
    val priority: ParagliderAirspacePriority,
    val centroid: GeoPoint,
    val bounds: BoundingBox,
    val altitudeRange: AltitudeRange,
    val name: String? = null,
    val description: String? = null,
    val isActive: Boolean = true,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Paraglider-specific overlay types aligned with FAR 103 operations
 */
sealed class ParagliderOverlayType {
    // Safety Critical
    object DangerArea : ParagliderOverlayType()
    object RestrictedArea : ParagliderOverlayType()
    object TemporaryRestriction : ParagliderOverlayType()
    object ParachuteDropZone : ParagliderOverlayType()

    // Flight Critical
    object TrainingArea : ParagliderOverlayType()
    object CompetitionArea : ParagliderOverlayType()
    object WeatherAvoidance : ParagliderOverlayType()
    object ThermalSource : ParagliderOverlayType()

    // Awareness
    object ControlledAirspace : ParagliderOverlayType()
    object GliderSite : ParagliderOverlayType()
    object TerrainHazard : ParagliderOverlayType()
    object LandingOption : ParagliderOverlayType()

    // Non-essential
    object Airway : ParagliderOverlayType()
    object ReportingPoint : ParagliderOverlayType()
    object NavigationAid : ParagliderOverlayType()
    object CivilAirport : ParagliderOverlayType()
}