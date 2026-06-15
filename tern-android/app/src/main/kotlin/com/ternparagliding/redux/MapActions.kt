package com.ternparagliding.redux

import org.osmdroid.util.GeoPoint

import com.ternparagliding.mezulla.redux.PeerAction
import com.ternparagliding.model.Waypoint
import com.ternparagliding.model.Task
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.TernBoundingBox
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

    // Sensor actions - real-time flight data (XC Tracer vario over BLE)
    /** A parsed fix from the external vario, plus the live wind estimate (m/s, from-deg) and
     *  the thermal-averaged climb (the centering needle). */
    data class UpdateVarioFix(
        val fix: com.ternparagliding.flight.SensorFix,
        val windFromDeg: Double?,
        val windSpeedMs: Double?,
        val avgClimbMs: Double? = null,
    ) : MapAction()
    /** Vario BLE link state for the shelf button + battery hand-off. */
    data class SetVarioLinkState(val connected: Boolean, val scanning: Boolean) : MapAction()
    /** Pilot tapped "Connect vario" — start/stop scanning for the XC Tracer. */
    object ToggleVario : MapAction()
    /** Start/stop replaying a bundled IGC flight into the deck (bench demo, no hardware). */
    data class StartDeckReplay(val flightId: String) : MapAction()
    object StopDeckReplay : MapAction()

    // Handedness-aware UI actions - optimizes control placement for user preference
    data class SetHandedness(val handedness: Handedness) : MapAction()
    data class UpdateHandednessSource(val source: HandednessSource) : MapAction()

    // User preferences actions
    data class UpdateUserPreferences(val preferences: UserPreferencesState) : MapAction()

    // Waypoint library actions — standalone waypoints (exist without tasks).
    /** Replace the whole library (used to hydrate from disk on start). */
    data class SetWaypointLibrary(val waypoints: List<com.ternparagliding.model.LibraryWaypoint>) : MapAction()
    /** Merge imported waypoints into the library (by code; re-import refreshes). */
    data class ImportWaypointsToLibrary(val waypoints: List<com.ternparagliding.model.LibraryWaypoint>) : MapAction()
    /** Remove one library waypoint by id. */
    data class RemoveLibraryWaypoint(val waypointId: String) : MapAction()
    /** Clear the entire waypoint library. */
    object ClearWaypointLibrary : MapAction()

    // Task actions
    data class AddTask(val task: Task) : MapAction()
    data class RemoveTask(val taskId: String) : MapAction()
    data class UpdateTask(val task: Task) : MapAction()
    /** Merge nearby preplanned tasks (from the spatial TaskCache) into the active set. */
    data class SurfaceNearbyTasks(val tasks: List<Task>) : MapAction()
    object ClearAllTasks : MapAction()

    // Waypoint actions (for multi-waypoint tasks)
    data class AddWaypointToTask(val taskId: String, val lat: Double, val lon: Double, val type: LocationType = LocationType.TURNPOINT, val label: String? = null, val id: String? = null) : MapAction()
    data class RemoveWaypoint(val taskId: String, val waypointId: String) : MapAction()
    data class UpdateWaypoint(val taskId: String, val waypointId: String, val lat: Double? = null, val lon: Double? = null, val type: LocationType? = null, val label: String? = null) : MapAction()
    data class UpdateWaypointType(val taskId: String, val waypointId: String, val type: LocationType) : MapAction()
    data class UpdateWaypointDescription(val taskId: String, val waypointId: String, val description: String?) : MapAction()
    data class UpdateWaypointRadius(val taskId: String, val waypointId: String, val radius: Double) : MapAction()
    data class UpdateWaypointAltitude(val taskId: String, val waypointId: String, val alt: Double?) : MapAction()
    data class UpdateWaypointTimeGates(val taskId: String, val waypointId: String, val openTime: String?, val closeTime: String?) : MapAction()
    data class ReorderWaypoint(val taskId: String, val fromIndex: Int, val toIndex: Int) : MapAction()

    // Interactive editing actions (Phase 7.1)
    data class SelectWaypoint(val taskId: String, val waypointId: String) : MapAction()
    object DeselectWaypoint : MapAction()
    data class StartWaypointDrag(val taskId: String, val waypointId: String) : MapAction()
    data class UpdateWaypointDrag(val lat: Double, val lon: Double) : MapAction()
    object EndWaypointDrag : MapAction()
    object CancelWaypointDrag : MapAction()

    // Task selection actions
    data class SelectTask(val taskId: String) : MapAction()
    object DeselectTask : MapAction()

    // Active-task navigation actions (driven by TaskProgressOverlay)
    /** Set the next waypoint the pilot is flying to (null = no active target). */
    data class SetActiveWaypoint(val waypointId: String?) : MapAction()
    /** Mark a waypoint reached (flew into its cylinder); the engine then advances. */
    data class TagWaypoint(val waypointId: String) : MapAction()
    /** Manual retarget (Phase 2 "Go to"): make [waypointId] the active target out of
     *  sequence by tagging every waypoint before it and un-tagging it + everything
     *  after. Auto-advance then resumes from there on cylinder entry. */
    data class GoToWaypoint(val taskId: String, val waypointId: String) : MapAction()
    /** Clear all task progress (active + tagged) — on task switch/deselect. */
    object ResetTaskProgress : MapAction()

    // Smart Suggestion actions
    data class SetSmartSuggestion(val nearbyPGSpot: com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature?, val pendingWaypointCreation: GeoPoint?) : MapAction()
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
    data class ZoomToTask(val taskId: String) : MapAction()

    // UI State Actions
    object ToggleTaskPanelExpanded : MapAction()
    data class SetTaskPanelExpanded(val expanded: Boolean) : MapAction()

    // Mezulla view mode
    object CycleMezullaViewMode : MapAction()
    data class SetMezullaViewMode(val mode: MezullaViewMode) : MapAction()

    // SOS dismiss (UI concept, not protocol)
    data class DismissSosAlert(val senderNodeNumber: Long) : MapAction()

    // M3: PG spot GeoJSON for MapLibre rendering
    data class UpdatePgSpotGeoJson(val geoJson: FeatureCollection<Geometry, JsonObject>?) : MapAction()

    // Thermal-hotspot GeoJSON (kk7.ch) for MapLibre rendering
    data class UpdateThermalHotspotGeoJson(val geoJson: FeatureCollection<Geometry, JsonObject>?) : MapAction()
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
    data class WeatherFetched(val pgSpotId: String, val forecast: com.ternparagliding.utils.io.WeatherForecast?) : WeatherActions()
    data class WeatherFetchError(val pgSpotId: String, val error: Throwable) : WeatherActions()
    
    // Batch weather actions for performance optimization (Phase 7.3)
    data class FetchWeatherForSpots(val spots: List<Triple<String, Double, Double>>) : WeatherActions()
    data class SpotsWeatherFetched(val forecasts: Map<String, com.ternparagliding.utils.io.WeatherForecast>) : WeatherActions()

    // Weather data fetching for Tasks/Waypoints
    data class FetchWeatherForTask(val taskId: String) : WeatherActions()
    data class TaskWeatherFetched(
        val taskId: String, 
        val waypointForecasts: Map<String, com.ternparagliding.utils.io.WeatherForecast>,
        val etas: Map<String, Long> = emptyMap()
    ) : WeatherActions()

    // Weather display controls
    data class SetWeatherGaugeEnabled(val enabled: Boolean) : WeatherActions()
    data class SetWeatherDetailsEnabled(val enabled: Boolean) : WeatherActions()

    // UI controls for weather details
    data class ShowWeatherDetails(val pgSpotId: String, val spotName: String, val forecast: com.ternparagliding.utils.io.WeatherForecast?, val siteContext: com.ternparagliding.weather.SiteContext? = null) : WeatherActions()
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
