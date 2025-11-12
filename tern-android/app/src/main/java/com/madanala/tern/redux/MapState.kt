package com.madanala.tern.redux

import org.osmdroid.util.GeoPoint
import com.madanala.tern.model.FlightData
import com.madanala.tern.model.SensorState
import com.madanala.tern.model.FlightComputerData
import com.madanala.tern.model.FlightMetrics

/**
 * Handedness preference for adaptive UI layout
 */
enum class Handedness {
    LEFT_HANDED,
    RIGHT_HANDED,
    AMBIDEXTROUS
}

/**
 * Source of handedness detection for transparency
 */
enum class HandednessSource {
    USER_SELECTED,      // User explicitly chose during onboarding
    SYSTEM_DETECTED,    // Detected from system settings
    SMART_DEFAULT       // Educated guess from device config
}

/**
 * User preferences for adaptive UI
 */
data class UserPreferencesState(
    val handedness: Handedness = Handedness.RIGHT_HANDED,
    val handednessSource: HandednessSource = HandednessSource.SMART_DEFAULT,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Adaptive layout configuration based on handedness and flight mode
 */
data class AdaptiveLayoutConfig(
    val criticalZone: ScreenZone = ScreenZone.BOTTOM_RIGHT,
    val importantZone: ScreenZone = ScreenZone.BOTTOM_CENTER,
    val secondaryZone: ScreenZone = ScreenZone.TOP_CENTER,
    val tertiaryZone: ScreenZone = ScreenZone.TOP_RIGHT,

    // Layout metadata
    val layoutVersion: Int = 1,
    val lastCalculated: Long = System.currentTimeMillis(),

    // Performance optimization
    val requiresRecalculation: Boolean = true
)

/**
 * Screen zones for control placement optimization
 */
enum class ScreenZone {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,

    // Special zones
    CENTER, FULL_WIDTH_TOP, FULL_WIDTH_BOTTOM,

    // Adaptive zones that change based on handedness
    THUMB_ZONE, INDEX_ZONE, VISUAL_ZONE
}

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
    val gpsStatus: com.madanala.tern.redux.GpsStatus = com.madanala.tern.redux.GpsStatus.INITIAL,

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

    // Sensor state - real-time flight data and sensor fusion
    val sensorState: SensorState = SensorState(),

    // Current flight data - real-time sensor readings
    val currentFlightData: FlightData? = null,

    // Flight computer data - calculated aviation parameters
    val flightComputerData: FlightComputerData? = null,

    // Flight metrics - accumulated flight statistics
    val flightMetrics: FlightMetrics? = null,

    // Handedness-aware UI state - optimizes control placement for user preference
    val userPreferences: UserPreferencesState = UserPreferencesState(),

    // Current flight mode - affects UI layout and control priorities
    val currentFlightMode: com.madanala.tern.model.FlightMode = com.madanala.tern.model.FlightMode.GROUND,

    // Adaptive layout configuration - calculated based on handedness and flight mode
    val adaptiveLayout: AdaptiveLayoutConfig = AdaptiveLayoutConfig(),

    // Route state - cached routes for display
    val routes: List<com.madanala.tern.route.Route> = emptyList(),

    // Route editing state - for interactive waypoint editing
    val selectedWaypoint: WaypointSelection? = null
)

/**
 * Waypoint selection state for interactive editing
 */
data class WaypointSelection(
    val routeId: String,
    val waypointId: String,
    val isDragging: Boolean = false
)
