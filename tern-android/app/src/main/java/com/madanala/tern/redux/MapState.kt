package com.madanala.tern.redux

import org.osmdroid.util.GeoPoint
import com.madanala.tern.model.FlightData
import com.madanala.tern.model.SensorState
import com.madanala.tern.model.FlightComputerData
import com.madanala.tern.model.FlightMetrics

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
    val flightMetrics: FlightMetrics? = null
)
