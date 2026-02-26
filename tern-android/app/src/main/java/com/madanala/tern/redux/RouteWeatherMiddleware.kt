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
 * Middleware handling side-effects for Route Weather fetching.
 * Aviation-grade architecture separation from UI components.
 */
class RouteWeatherMiddleware(
    private val mapStore: MapStore,
    private val weatherAPI: WeatherAPI = OpenMeteoWeatherAPI(),
    private val weatherCache: WeatherCache = CacheManager.weatherCache,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
) {

    fun handleAction(state: MapState, action: WeatherActions) {
        when (action) {
            is WeatherActions.FetchWeatherForRoute -> fetchRouteWeather(state, action.routeId)
            else -> {} // Handle other weather actions if necessary
        }
    }

    private fun fetchRouteWeather(state: MapState, routeId: String) {
        val route = state.routes.find { it.id == routeId } ?: return
        
        // Calculate 4D Trajectory ETAs (default 15 knots paragliding speed)
        val etas = TrajectoryAnalyzer.calculateETAs(route, 15.0)

        coroutineScope.launch {
            val waypointForecasts = mutableMapOf<String, WeatherForecast>()

            route.waypoints.forEach { waypoint ->
                try {
                    val eta = etas[waypoint.id] ?: System.currentTimeMillis()
                    
                    // Check cache for raw forecast data
                    var forecast = weatherCache.queryNearbyWeather(waypoint.lat, waypoint.lon)

                    // Fallback to API if cache miss
                    if (forecast == null) {
                        forecast = weatherAPI.fetchForecast(waypoint.lat, waypoint.lon)
                        if (forecast != null) {
                            weatherCache.cacheWeatherData(waypoint.lat, waypoint.lon, forecast)
                        }
                    }

                    if (forecast != null) {
                        // 4D Transformation: Interpolate weather for the specific ETA
                        val correctedWeather = interpolateWeatherForEta(forecast, eta)
                        
                        // We store a "point-in-time" forecast for the waypoint
                        waypointForecasts[waypoint.id] = forecast.copy(current = correctedWeather)
                    } else {
                        Log.w("RouteWeatherMiddleware", "Failed to fetch weather for waypoint ${waypoint.label}")
                    }
                } catch (e: Exception) {
                    Log.e("RouteWeatherMiddleware", "Error fetching weather for waypoint ${waypoint.label}", e)
                }
            }

            if (waypointForecasts.isNotEmpty()) {
                mapStore.dispatch(WeatherActions.RouteWeatherFetched(routeId, waypointForecasts, etas))
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
