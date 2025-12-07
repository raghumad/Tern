package com.madanala.tern.redux

import org.osmdroid.util.GeoPoint

import com.madanala.tern.model.Route

/**
 * Global state for map functionality using Redux pattern
 */
data class MapState(
    // Map viewport state
    val rotation: Float = 0f,
    val center: GeoPoint? = null,
    val zoom: Double = MapConstants.DEFAULT_ZOOM_LEVEL,

    // Location state
    val isLocationReady: Boolean = false,
    val userLocation: GeoPoint? = null,
    val gpsStatus: GpsStatus = GpsStatus.INITIAL,

    // Permission state
    val hasLocationPermission: Boolean = false,
    val permissionRequested: Boolean = false,

    // Overlay state - modular overlay management
    val overlayState: OverlayState = OverlayState(
        airspaces = OverlayConfig(enabled = true),  // Airspaces enabled by default
        pgSpots = OverlayConfig(enabled = true)     // PG spots enabled by default
    ),

    // Weather state for PG spots
    val weatherState: WeatherState = WeatherState(),


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
    val compassVisible: Boolean = true,

    // Settings state - user preferences and configuration
    val settingsState: SettingsState = SettingsState(),





    // Route state - cached routes for display
    val routes: List<Route> = emptyList(),

    // Route editing state - for interactive waypoint editing
    val selectedWaypoint: WaypointSelection? = null,

    // Selected route for viewing/editing
    val selectedRouteId: String? = null,


    // Handedness-aware UI state - optimizes control placement for user preference
    val userPreferences: UserPreferencesState = UserPreferencesState(),

    // Smart Suggestion State - for nearby PG spot detection
    val smartSuggestionState: SmartSuggestionState = SmartSuggestionState()
)

/**
 * State for smart waypoint suggestions (nearby PG spots)
 */
data class SmartSuggestionState(
    val nearbyPGSpot: com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature? = null,
    val pendingWaypointCreation: GeoPoint? = null
)
