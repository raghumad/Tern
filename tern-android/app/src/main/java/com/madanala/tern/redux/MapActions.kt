package com.madanala.tern.redux

import org.osmdroid.util.GeoPoint
import com.madanala.tern.model.FlightData
import com.madanala.tern.model.SensorState
import com.madanala.tern.model.FlightComputerData
import com.madanala.tern.model.FlightMetrics

/**
 * Redux actions for map functionality
 */

sealed class MapAction {

    // Permission actions
    object RequestLocationPermission : MapAction()
    data class UpdateLocationPermission(val granted: Boolean) : MapAction()

    // Location actions
    data class UpdateUserLocation(val location: GeoPoint?) : MapAction()
    data class SetLocationReady(val ready: Boolean) : MapAction()
    data class UpdateGpsStatus(val status: com.madanala.tern.redux.GpsStatus) : MapAction()
    object RetryGpsAcquisition : MapAction()

    // Map viewport actions
    data class UpdateRotation(val rotation: Float) : MapAction()
    data class UpdateCenter(val center: GeoPoint) : MapAction()
    data class UpdateZoom(val zoom: Double) : MapAction()

    // Combined map movement action for performance optimization
    data class UpdateMapMovement(
        val rotation: Float? = null,
        val center: GeoPoint? = null,
        val zoom: Double? = null
    ) : MapAction()

    // Overlay control actions - new modular system
    data class SetOverlayEnabled(val type: OverlayType, val enabled: Boolean) : MapAction()
    data class UpdateOverlayConfig(val type: OverlayType, val config: OverlayConfig) : MapAction()

    // Loading state actions
    data class SetLoadingAirspaces(val loading: Boolean) : MapAction()
    data class SetLoadingPGSpots(val loading: Boolean) : MapAction()

    // Cache actions
    data class UpdateCurrentCountryCode(val countryCode: String?) : MapAction()
    data class AddAirspaceCountry(val countryCode: String) : MapAction()
    data class AddPGSpotCountry(val countryCode: String) : MapAction()
    object ClearAirspaceCache : MapAction()
    object ClearPGSpotCache : MapAction()

    // Configuration actions
    data class UpdateMapStyle(val style: String) : MapAction()

    // UI actions
    data class SetCompassVisible(val visible: Boolean) : MapAction()

    // Settings actions
    data class SetSettingsOverlayEnabled(val overlayType: String, val enabled: Boolean) : MapAction()
    data class SetUnitPreference(val unitType: String, val unit: String) : MapAction()

    // Sensor actions - real-time flight data
    data class UpdateSensorState(val sensorState: SensorState) : MapAction()
    data class UpdateFlightData(val flightData: FlightData) : MapAction()
    data class UpdateFlightComputerData(val flightComputerData: FlightComputerData) : MapAction()
    data class UpdateFlightMetrics(val flightMetrics: FlightMetrics) : MapAction()

    // Sensor control actions
    data class StartSensors(val flightMode: com.madanala.tern.model.FlightMode = com.madanala.tern.model.FlightMode.FLIGHT) : MapAction()
    object StopSensors : MapAction()
    data class SetSensorConfig(val config: com.madanala.tern.model.SensorConfig) : MapAction()

    // Flight session actions
    object StartFlightSession : MapAction()
    object EndFlightSession : MapAction()
    data class UpdateFlightPath(val position: GeoPoint) : MapAction()

    // Handedness-aware UI actions - optimizes control placement for user preference
    data class SetHandedness(val handedness: Handedness) : MapAction()
    data class UpdateHandednessSource(val source: HandednessSource) : MapAction()
    data class UpdateAdaptiveLayout(val layoutConfig: com.madanala.tern.redux.AdaptiveLayoutConfig) : MapAction()

    // Flight mode actions - affects UI layout and control priorities
    data class SetFlightMode(val flightMode: com.madanala.tern.model.FlightMode) : MapAction()

    // User preferences actions
    data class UpdateUserPreferences(val preferences: com.madanala.tern.redux.UserPreferencesState) : MapAction()

    // Route actions
    data class AddRoute(val route: com.madanala.tern.route.Route) : MapAction()
    data class RemoveRoute(val routeId: String) : MapAction()
    data class UpdateRoute(val route: com.madanala.tern.route.Route) : MapAction()
    object ClearAllRoutes : MapAction()

    // Waypoint actions (for multi-waypoint routes)
    data class AddWaypointToRoute(val routeId: String, val lat: Double, val lon: Double, val type: com.madanala.tern.model.Waypoint.Type = com.madanala.tern.model.Waypoint.Type.TURNPOINT, val label: String? = null) : MapAction()
    data class RemoveWaypoint(val routeId: String, val waypointId: String) : MapAction()
    data class UpdateWaypoint(val routeId: String, val waypointId: String, val lat: Double? = null, val lon: Double? = null, val type: com.madanala.tern.model.Waypoint.Type? = null, val label: String? = null) : MapAction()
}
