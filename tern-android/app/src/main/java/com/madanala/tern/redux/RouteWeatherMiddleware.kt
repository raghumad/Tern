package com.madanala.tern.redux

import android.util.Log
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.OpenMeteoWeatherAPI
import com.madanala.tern.utils.WeatherAPI
import com.madanala.tern.utils.WeatherCache
import com.madanala.tern.utils.WeatherForecast
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

        coroutineScope.launch {
            val waypointForecasts = mutableMapOf<String, WeatherForecast>()

            route.waypoints.forEach { waypoint ->
                try {
                    // Check cache
                    var forecast = weatherCache.queryNearbyWeather(waypoint.lat, waypoint.lon)

                    // Fallback to API
                    if (forecast == null) {
                        forecast = weatherAPI.fetchForecast(waypoint.lat, waypoint.lon)
                        
                        // Save to cache
                        if (forecast != null) {
                            weatherCache.cacheWeatherData(waypoint.lat, waypoint.lon, forecast)
                        }
                    }

                    if (forecast != null) {
                        waypointForecasts[waypoint.id] = forecast
                    } else {
                        Log.w("RouteWeatherMiddleware", "Failed to fetch weather for waypoint ${waypoint.label}")
                    }
                } catch (e: Exception) {
                    Log.e("RouteWeatherMiddleware", "Error fetching weather for waypoint ${waypoint.label}", e)
                }
            }

            if (waypointForecasts.isNotEmpty()) {
                mapStore.dispatch(WeatherActions.RouteWeatherFetched(routeId, waypointForecasts))
            }
        }
    }
}
