package com.madanala.tern.redux

import org.osmdroid.util.GeoPoint

import com.madanala.tern.mezulla.redux.PeerAction
import com.madanala.tern.model.Waypoint
import com.madanala.tern.model.Route
import com.madanala.tern.model.LocationType
import com.madanala.tern.model.TernBoundingBox
import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * Redux actions for map functionality
 */

sealed class MapAction : TernAction {

    // Permission actions
    object RequestLocationPermission : MapAction()
    data class UpdateLocationPermission(val granted: Boolean) : MapAction()

    // Location actions
    data class UpdateUserLocation(val location: GeoPoint?) : MapAction()
    data class SetLocationReady(val ready: Boolean) : MapAction()
    data class UpdateGpsStatus(val status: GpsStatus) : MapAction()
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


    // Handedness-aware UI actions - optimizes control placement for user preference
    data class SetHandedness(val handedness: Handedness) : MapAction()
    data class UpdateHandednessSource(val source: HandednessSource) : MapAction()

    // User preferences actions
    data class UpdateUserPreferences(val preferences: UserPreferencesState) : MapAction()

    // Route actions
    data class AddRoute(val route: Route) : MapAction()
    data class RemoveRoute(val routeId: String) : MapAction()
    data class UpdateRoute(val route: Route) : MapAction()
    object ClearAllRoutes : MapAction()

    // Waypoint actions (for multi-waypoint routes)
    data class AddWaypointToRoute(val routeId: String, val lat: Double, val lon: Double, val type: LocationType = LocationType.TURNPOINT, val label: String? = null, val id: String? = null) : MapAction()
    data class RemoveWaypoint(val routeId: String, val waypointId: String) : MapAction()
    data class UpdateWaypoint(val routeId: String, val waypointId: String, val lat: Double? = null, val lon: Double? = null, val type: LocationType? = null, val label: String? = null) : MapAction()
    data class UpdateWaypointType(val routeId: String, val waypointId: String, val type: LocationType) : MapAction()
    data class UpdateWaypointRadius(val routeId: String, val waypointId: String, val radius: Double) : MapAction()
    data class UpdateWaypointAltitude(val routeId: String, val waypointId: String, val alt: Double?) : MapAction()
    data class UpdateWaypointTimeGates(val routeId: String, val waypointId: String, val openTime: String?, val closeTime: String?) : MapAction()
    data class ReorderWaypoint(val routeId: String, val fromIndex: Int, val toIndex: Int) : MapAction()

    // Interactive editing actions (Phase 7.1)
    data class SelectWaypoint(val routeId: String, val waypointId: String) : MapAction()
    object DeselectWaypoint : MapAction()
    data class StartWaypointDrag(val routeId: String, val waypointId: String) : MapAction()
    data class UpdateWaypointDrag(val lat: Double, val lon: Double) : MapAction()
    object EndWaypointDrag : MapAction()
    object CancelWaypointDrag : MapAction()

    // Route selection actions
    data class SelectRoute(val routeId: String) : MapAction()
    object DeselectRoute : MapAction()

    // Smart Suggestion actions
    data class SetSmartSuggestion(val nearbyPGSpot: com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature?, val pendingWaypointCreation: GeoPoint?) : MapAction()
    data class CheckSmartSuggestion(val geoPoint: GeoPoint) : MapAction()
    object ClearSmartSuggestion : MapAction()

    // Map Interaction actions
    data class LongPressMap(
        val geoPoint: GeoPoint,
        val type: LocationType = LocationType.TURNPOINT,
        val label: String? = null
    ) : MapAction()
    data class SetAirspaceCollision(val hasCollision: Boolean) : MapAction()
    data class UpdateBoundingBox(val box: TernBoundingBox?) : MapAction()
    data class ZoomToRoute(val routeId: String) : MapAction()

    // UI State Actions
    object ToggleRoutePanelExpanded : MapAction()
    data class SetRoutePanelExpanded(val expanded: Boolean) : MapAction()

    // Mezulla view mode
    object CycleMezullaViewMode : MapAction()
    data class SetMezullaViewMode(val mode: MezullaViewMode) : MapAction()

    // SOS dismiss (UI concept, not protocol)
    data class DismissSosAlert(val senderNodeNumber: Long) : MapAction()

    // M3: PG spot GeoJSON for MapLibre rendering
    data class UpdatePgSpotGeoJson(val geoJson: FeatureCollection<Geometry, JsonObject>?) : MapAction()
}

/**
 * Overlay-specific Redux actions
 */
sealed class OverlayActions {
    data class SetOverlayEnabled(val type: OverlayType, val enabled: Boolean) : OverlayActions()
    data class UpdateOverlayConfig(val type: OverlayType, val config: OverlayConfig) : OverlayActions()
    data class SetMultipleEnabled(val enabledTypes: Map<OverlayType, Boolean>) : OverlayActions()
    data object LoadOverlayDefaults : OverlayActions()
}

/**
 * Weather-specific Redux actions for PG spot weather management
 */
sealed class WeatherActions : TernAction {
    // Weather data fetching and caching for PG Spots
    data class FetchWeatherForPGSpot(val pgSpotId: String, val latitude: Double, val longitude: Double) : WeatherActions()
    data class WeatherFetched(val pgSpotId: String, val forecast: com.madanala.tern.utils.WeatherForecast?) : WeatherActions()
    data class WeatherFetchError(val pgSpotId: String, val error: Throwable) : WeatherActions()
    
    // Batch weather actions for performance optimization (Phase 7.3)
    data class FetchWeatherForSpots(val spots: List<Triple<String, Double, Double>>) : WeatherActions()
    data class SpotsWeatherFetched(val forecasts: Map<String, com.madanala.tern.utils.WeatherForecast>) : WeatherActions()

    // Weather data fetching for Routes/Waypoints
    data class FetchWeatherForRoute(val routeId: String) : WeatherActions()
    data class RouteWeatherFetched(
        val routeId: String, 
        val waypointForecasts: Map<String, com.madanala.tern.utils.WeatherForecast>,
        val etas: Map<String, Long> = emptyMap()
    ) : WeatherActions()

    // Weather display controls
    data class SetWeatherGaugeEnabled(val enabled: Boolean) : WeatherActions()
    data class SetWeatherDetailsEnabled(val enabled: Boolean) : WeatherActions()

    // UI controls for weather details
    data class ShowWeatherDetails(val pgSpotId: String, val spotName: String, val forecast: com.madanala.tern.utils.WeatherForecast?) : WeatherActions()
    data object DismissWeatherDetails : WeatherActions()

    // Cache management
    data object ClearWeatherCache : WeatherActions()
    data class WeatherCacheCleared(val freedEntries: Int) : WeatherActions()

    // API availability monitoring
    data class WeatherAPIStatus(val apiAvailable: Boolean) : WeatherActions()

    // Additional weather actions for testing
    data object RequestWeatherUpdate : WeatherActions()
    data class WeatherDataLoaded(val data: List<Any>) : WeatherActions()
    data class WeatherError(val error: String) : WeatherActions()
    data class SetStormRisk(val hasRisk: Boolean) : WeatherActions()
}
