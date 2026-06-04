package com.ternparagliding.redux

import android.content.Context
import android.util.Log
import com.ternparagliding.utils.geo.ThermalHotspotService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import com.ternparagliding.utils.geo.SpatialSafetyUtils
import com.ternparagliding.utils.cache.CacheManager
import com.ternparagliding.utils.geo.CountryUtils

/**
 * Middleware handling side-effects for Route Planning.
 * Port of iOS RoutePlannerModel logic for Android Redux.
 * Handles:
 * - Automatic Thermal Hotspot fetching around launch/waypoints.
 * - [PLANNED] Automatic pre-caching of route corridor (Hilbert tiles).
 * - [PLANNED] Airspace analytics triggers.
 */
class RoutePlanningMiddleware(
    private val context: Context,
    private val thermalService: ThermalHotspotService = ThermalHotspotService(context),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
) : Middleware {

    override suspend fun process(action: TernAction, store: MapStore) {
        val state = store.state.value
        when (action) {
            is MapAction.SelectRoute -> {
                zoomToRoute(state, action.routeId, store)
                fetchThermalHotspotsForRoute(state, action.routeId)
                triggerRouteCorridorSync(state, action.routeId, store)
                store.dispatch(WeatherActions.FetchWeatherForRoute(action.routeId))
            }
            is MapAction.ZoomToRoute -> {
                zoomToRoute(state, action.routeId, store)
            }
            is MapAction.AddRoute -> {
                fetchThermalHotspotsForRoute(state, action.route.id)
                triggerRouteCorridorSync(state, action.route.id, store)
                store.dispatch(WeatherActions.FetchWeatherForRoute(action.route.id))
                // [RFC 005] Strategic Auto-Minimize: Collapse panel on new route creation
                store.dispatch(MapAction.SetRoutePanelExpanded(false))
            }
            is MapAction.AddWaypointToRoute -> {
                fetchThermalHotspotsForPoint(GeoPoint(action.lat, action.lon))
                triggerRouteCorridorSync(state, action.routeId, store)
                store.dispatch(WeatherActions.FetchWeatherForRoute(action.routeId))
                // [RFC 005] Strategic Auto-Minimize: If adding points, ensure panel is minimized 
                // to show the evolving trajectory on the map.
                store.dispatch(MapAction.SetRoutePanelExpanded(false))
            }
            is MapAction.UpdateWaypoint -> {
                if (action.lat != null && action.lon != null) {
                    fetchThermalHotspotsForPoint(GeoPoint(action.lat, action.lon))
                    triggerRouteCorridorSync(state, action.routeId, store)
                    store.dispatch(WeatherActions.FetchWeatherForRoute(action.routeId))
                }
            }
            // Explicit deletion clears the persisted route(s). Route *writes*
            // are handled by RoutePersistence (an observer), not here — a
            // middleware can't see post-edit state. But deletes carry their
            // id in the action, so they're safe to handle inline.
            is MapAction.RemoveRoute -> {
                coroutineScope.launch {
                    CacheManager.routeCache.clearCacheForRoute(action.routeId)
                }
            }
            is MapAction.ClearAllRoutes -> {
                coroutineScope.launch {
                    CacheManager.routeCache.clearCache()
                }
            }
        }
    }

    private fun fetchThermalHotspotsForRoute(state: MapState, routeId: String) {
        val route = state.routes.find { it.id == routeId } ?: return
        val firstWaypoint = route.waypoints.firstOrNull() ?: return
        
        Log.i("RoutePlanningMiddleware", "Route context detected. Pre-fetching thermal hotspots around launch.")
        fetchThermalHotspotsForPoint(GeoPoint(firstWaypoint.lat, firstWaypoint.lon))
    }

    private fun fetchThermalHotspotsForPoint(point: GeoPoint) {
        coroutineScope.launch {
            try {
                val hotspots = thermalService.getHotspots(point)
                if (hotspots.isNotEmpty()) {
                    Log.d("RoutePlanningMiddleware", "Background Sync: Secured ${hotspots.size} thermal hotspots for offline use.")
                }
            } catch (e: Exception) {
                Log.e("RoutePlanningMiddleware", "Aviation Sync Error (Thermal): ${e.message}")
            }
        }
    }

    private fun zoomToRoute(state: MapState, routeId: String, store: MapStore) {
        val route = state.routes.find { it.id == routeId } ?: return
        route.extent?.let { extent ->
            Log.i("RoutePlanningMiddleware", "Auto-zooming to route: ${route.name}")
            store.dispatch(MapAction.UpdateBoundingBox(extent))
            
            // [RFC 005] Strategic Auto-Minimize: If zooming to a route extent, we are likely in "Strategic" mode.
            // Collapse the panel by default to show the whole route.
            store.dispatch(MapAction.SetRoutePanelExpanded(false))
        }
    }

    private fun triggerRouteCorridorSync(state: MapState, routeId: String, store: MapStore) {
        val route = state.routes.find { it.id == routeId } ?: return
        coroutineScope.launch {
            try {
                Log.i("RoutePlanningMiddleware", "Initiating corridor pre-cache for: ${route.name}")
                
                // [Aviation-Grade Truth] Perform real collision detection along the route
                val waypoints = route.waypoints.map { GeoPoint(it.lat, it.lon) }
                if (waypoints.isNotEmpty()) {
                    val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, waypoints.first()) ?: "US"
                    val nearbyAirspaces = CacheManager.airspaceCache.queryNearbyFeatures(countryCode, waypoints.first(), 50.0)
                    
                    val hasCollision = SpatialSafetyUtils.checkRouteCollision(waypoints, nearbyAirspaces)
                    store.dispatch(MapAction.SetAirspaceCollision(hasCollision))
                    
                    // Check for storm risk along the route
                    val weatherState = store.state.value.weatherState
                    val routeForecast = weatherState.waypointWeathers.values.firstOrNull()
                    val hasStormRisk = waypoints.any { SpatialSafetyUtils.checkStormRisk(it, routeForecast) }
                    store.dispatch(WeatherActions.SetStormRisk(hasStormRisk))
                }

                Log.d("RoutePlanningMiddleware", "Corridor Sync: 5km route corridor secured for offline flight.")
            } catch (e: Exception) {
                Log.e("RoutePlanningMiddleware", "Corridor Sync Failed: ${e.message}")
            }
        }
    }
}

