package com.madanala.tern.model

import org.osmdroid.util.GeoPoint

/**
 * Unified interface for any geographic point of interest (Waypoint or PG spot).
 * Ensures consistency across aviation-grade map overlays and weather awareness.
 */
interface UnifiedLocation {
    val id: String
    val coordinate: GeoPoint
    val name: String?
    val type: LocationType
    val source: LocationSource
    val altitude: Double?
    val metadata: Map<String, Any>

    /**
     * Helper to get a string property from metadata with fallback to name
     */
    fun computeLabel(): String? = name ?: metadata["name"] as? String
}

/**
 * Source of the location data
 */
enum class LocationSource {
    WAYPOINT, // Internal/User-defined
    PG_SPOT   // Remote database (ParaglidingEarth, etc.)
}

/**
 * Purpose/Type of the location
 */
enum class LocationType {
    LAUNCH,
    LANDING,
    TURNPOINT,
    SSS,
    ESS,
    GOAL
}
