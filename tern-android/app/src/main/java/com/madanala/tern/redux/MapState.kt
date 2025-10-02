package com.madanala.tern.redux

import org.osmdroid.util.GeoPoint

/**
 * Global state for map functionality using Redux pattern
 */
data class MapState(
    // Map viewport state
    val rotation: Float = 0f,
    val center: GeoPoint? = null,
    val zoom: Double = 8.0,

    // Location state
    val isLocationReady: Boolean = false,
    val userLocation: GeoPoint? = null,

    // Permission state
    val hasLocationPermission: Boolean = false,
    val permissionRequested: Boolean = false,

    // Overlay state
    val airspacesEnabled: Boolean = true,
    val pgSpotsEnabled: Boolean = false,
    val showOverlays: Boolean = true,

    // Loading state
    val isLoadingAirspaces: Boolean = false,
    val isLoadingPGSpots: Boolean = false,

    // Cache state
    val currentCountryCode: String? = null,
    val airspaceCountries: Set<String> = emptySet(),
    val pgSpotCountries: Set<String> = emptySet(),

    // Configuration
    val mapStyle: String = "terrain", // "satellite", "terrain", "topo"

    // UI state
    val compassVisible: Boolean = true
)
