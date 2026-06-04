package com.ternparagliding.redux

import android.util.Log
import com.ternparagliding.utils.cache.CacheManager
import com.ternparagliding.utils.io.OpenMeteoWeatherAPI
import com.ternparagliding.utils.io.WeatherAPI
import com.ternparagliding.utils.cache.WeatherCache
import com.ternparagliding.utils.io.WeatherForecast
import com.ternparagliding.model.TrajectoryAnalyzer
import com.ternparagliding.utils.io.WeatherData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * Middleware handling side-effects for Aviation Weather fetching.
 * Handles both Route Waypoint weather and PG Spot weather.
 * Aviation-grade architecture separation from UI components.
 */
class WeatherMiddleware(
    private val weatherAPI: WeatherAPI = OpenMeteoWeatherAPI(),
    private val weatherCache: WeatherCache = CacheManager.weatherCache,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
) : Middleware {

    override suspend fun process(action: TernAction, store: MapStore) {
        if (action is WeatherActions) {
            val state = store.state.value
            when (action) {
                is WeatherActions.FetchWeatherForRoute -> fetchRouteWeather(state, action.routeId, store)
                is WeatherActions.FetchWeatherForPGSpot -> fetchPGSpotWeather(action, store)
                is WeatherActions.FetchWeatherForSpots -> fetchBatchWeatherForSpots(action, store)
                else -> {} 
            }
        }
    }

    private fun fetchPGSpotWeather(action: WeatherActions.FetchWeatherForPGSpot, store: MapStore) {
        coroutineScope.launch {
            try {
                // Check cache first
                val cached = weatherCache.queryNearbyWeather(action.latitude, action.longitude)
                if (cached != null) {
                    store.dispatch(WeatherActions.WeatherFetched(action.pgSpotId, cached))
                    return@launch
                }

                // API Fetch - Restrict to CORE and NEAR zones (RFC 005)
                val mapCenter = store.state.value.center ?: store.state.value.userLocation
                val distanceKm = mapCenter?.let { 
                    org.osmdroid.util.GeoPoint(action.latitude, action.longitude).distanceToAsDouble(it) / 1000.0
                } ?: Double.MAX_VALUE

                val zone = com.ternparagliding.utils.geo.DistanceZone.fromDistanceKm(distanceKm)
                if (zone != com.ternparagliding.utils.geo.DistanceZone.CORE && zone != com.ternparagliding.utils.geo.DistanceZone.NEAR) {
                    // Skip API fetch for distant spots to optimize performance/API costs
                    return@launch
                }

                val forecast = weatherAPI.fetchForecast(action.latitude, action.longitude)
                if (forecast != null) {
                    weatherCache.cacheWeatherData(action.latitude, action.longitude, forecast)
                    store.dispatch(WeatherActions.WeatherFetched(action.pgSpotId, forecast))
                } else {
                    store.dispatch(WeatherActions.WeatherFetchError(action.pgSpotId, Exception("Weather API returned null")))
                }
            } catch (e: Exception) {
                Log.e("WeatherMiddleware", "Failed to fetch weather for PG Spot ${action.pgSpotId}", e)
                store.dispatch(WeatherActions.WeatherFetchError(action.pgSpotId, e))
            }
        }
    }

    private fun fetchBatchWeatherForSpots(action: WeatherActions.FetchWeatherForSpots, store: MapStore) {
        coroutineScope.launch {
            try {
                val results = mutableMapOf<String, WeatherForecast>()
                val cacheMisses = mutableListOf<com.ternparagliding.utils.io.LocationRequest>()

                // Cache PASS
                action.spots.forEach { (id, lat, lon) ->
                    val cached = weatherCache.queryNearbyWeather(lat, lon)
                    if (cached != null) {
                        results[id] = cached
                    } else {
                        cacheMisses.add(com.ternparagliding.utils.io.LocationRequest(id, lat, lon))
                    }
                }

                // API PASS - Restrict to CORE and NEAR zones (RFC 005)
                if (cacheMisses.isNotEmpty()) {
                    val state = store.state.value
                    val mapCenter = state.center ?: state.userLocation
                    val filteredMisses = cacheMisses.filter { req ->
                        val referencePoint = mapCenter ?: state.userLocation
                        val distanceKm = if (referencePoint != null) {
                            GeoPoint(req.lat, req.lon).distanceToAsDouble(referencePoint) / 1000.0
                        } else {
                            0.0 // Allow fetch if we don't have a reference location yet (cold start)
                        }
                        val zone = com.ternparagliding.utils.geo.DistanceZone.fromDistanceKm(distanceKm)
                        zone == com.ternparagliding.utils.geo.DistanceZone.CORE || zone == com.ternparagliding.utils.geo.DistanceZone.NEAR
                    }

                    if (filteredMisses.isNotEmpty()) {
                        val batchResult = weatherAPI.fetchBatchForecast(filteredMisses)
                        batchResult.forEach { (id, forecast) ->
                            if (forecast != null) {
                                results[id] = forecast
                                // Find the original lat/lon for caching
                                val original = action.spots.find { it.first == id }
                                if (original != null) {
                                    weatherCache.cacheWeatherData(original.second, original.third, forecast)
                                }
                            }
                        }
                    }
                }

                if (results.isNotEmpty()) {
                    store.dispatch(WeatherActions.SpotsWeatherFetched(results))
                }
            } catch (e: Exception) {
                Log.e("WeatherMiddleware", "Batch weather fetch failed", e)
            }
        }
    }

    private fun fetchRouteWeather(state: MapState, routeId: String, store: MapStore) {
        val route = state.routes.find { it.id == routeId } ?: return

        // Calculate 4D Trajectory ETAs (default 15 knots paragliding speed)
        val etas = TrajectoryAnalyzer.calculateETAs(route, 15.0)

        coroutineScope.launch {
            val waypointForecasts = mutableMapOf<String, WeatherForecast>()

            // CACHE PASS: Check cache for all waypoints first
            val cacheMissWaypoints = mutableListOf<com.ternparagliding.model.Waypoint>()
            route.waypoints.forEach { waypoint ->
                val cached = weatherCache.queryNearbyWeather(waypoint.lat, waypoint.lon)
                if (cached != null) {
                    val eta = etas[waypoint.id] ?: System.currentTimeMillis()
                    val correctedWeather = interpolateWeatherForEta(cached, eta)
                    waypointForecasts[waypoint.id] = cached.copy(current = correctedWeather)
                } else {
                    cacheMissWaypoints.add(waypoint)
                }
            }

            // BATCH FETCH: Single API call for all cache-miss waypoints
            if (cacheMissWaypoints.isNotEmpty()) {
                try {
                    val locations = cacheMissWaypoints.map { wp ->
                        com.ternparagliding.utils.io.LocationRequest(wp.id, wp.lat, wp.lon)
                    }
                    val batchResult = weatherAPI.fetchBatchForecast(locations)

                    cacheMissWaypoints.forEach { waypoint ->
                        val forecast = batchResult[waypoint.id]
                        if (forecast != null) {
                            weatherCache.cacheWeatherData(waypoint.lat, waypoint.lon, forecast)
                            val eta = etas[waypoint.id] ?: System.currentTimeMillis()
                            val correctedWeather = interpolateWeatherForEta(forecast, eta)
                            waypointForecasts[waypoint.id] = forecast.copy(current = correctedWeather)
                        } else {
                            Log.w("WeatherMiddleware", "Batch fetch returned null for waypoint ${waypoint.label}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WeatherMiddleware", "Batch weather fetch failed for route $routeId", e)
                }
            }

            if (waypointForecasts.isNotEmpty()) {
                store.dispatch(WeatherActions.RouteWeatherFetched(routeId, waypointForecasts, etas))
            }
        }
    }


    /**
     * Interpolate weather data from a forecast for a specific timestamp (4D calculation).
     */
    private fun interpolateWeatherForEta(forecast: WeatherForecast, targetTimestamp: Long): WeatherData? {
        val series = forecast.hourly ?: return forecast.current
        
        // Find the bounding periods
        val before = series.lastOrNull { it.startTime <= targetTimestamp }
        val after = series.firstOrNull { it.startTime > targetTimestamp }
        
        return if (before != null && after != null) {
            WeatherCache.interpolateWeather(before, after, targetTimestamp)
        } else {
            before?.weather ?: forecast.current
        }
    }
}
