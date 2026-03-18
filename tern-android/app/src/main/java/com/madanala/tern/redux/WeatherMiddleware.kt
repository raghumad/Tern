package com.madanala.tern.redux

import android.util.Log
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.OpenMeteoWeatherAPI
import com.madanala.tern.utils.WeatherAPI
import com.madanala.tern.utils.WeatherCache
import com.madanala.tern.utils.WeatherForecast
import com.madanala.tern.model.TrajectoryAnalyzer
import com.madanala.tern.utils.WeatherData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

    override suspend fun process(action: Any, store: MapStore) {
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

                // API Fetch
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
                val cacheMisses = mutableListOf<com.madanala.tern.utils.LocationRequest>()

                // Cache PASS
                action.spots.forEach { (id, lat, lon) ->
                    val cached = weatherCache.queryNearbyWeather(lat, lon)
                    if (cached != null) {
                        results[id] = cached
                    } else {
                        cacheMisses.add(com.madanala.tern.utils.LocationRequest(id, lat, lon))
                    }
                }

                // API PASS
                if (cacheMisses.isNotEmpty()) {
                    val batchResult = weatherAPI.fetchBatchForecast(cacheMisses)
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
            val cacheMissWaypoints = mutableListOf<com.madanala.tern.model.Waypoint>()
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
                        com.madanala.tern.utils.LocationRequest(wp.id, wp.lat, wp.lon)
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
