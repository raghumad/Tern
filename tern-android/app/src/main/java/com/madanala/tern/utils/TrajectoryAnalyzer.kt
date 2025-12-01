package com.madanala.tern.utils

import android.util.Log
import com.madanala.tern.model.CapePoint
import com.madanala.tern.model.OverdevelopmentRisk
import com.madanala.tern.model.RiskLevel
import com.madanala.tern.model.Route
import com.madanala.tern.model.RouteWeather
import com.madanala.tern.model.SkewTForecast
import com.madanala.tern.model.TrajectoryForecast
import com.madanala.tern.model.WaypointWeather
import com.madanala.tern.model.WindPoint
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt

// Alias to avoid conflict
import com.madanala.tern.model.WeatherForecast as ModelForecast
import com.madanala.tern.utils.WeatherForecast as UtilsForecast

/**
 * Trajectory Analyzer - 4D Weather Forecasting
 * Calculates weather conditions along a route based on estimated time of arrival.
 */
class TrajectoryAnalyzer(
    private val weatherCache: WeatherCache,
    private val weatherAPI: WeatherAPI
) {

    companion object {
        private const val TAG = "TrajectoryAnalyzer"
        private const val AVERAGE_SPEED_KMH = 30.0
    }

    /**
     * Analyze route trajectory and fetch 4D weather
     * @param route The route to analyze
     * @param startTime Epoch millis for start time
     * @return RouteWeather with trajectory forecast
     */
    suspend fun analyzeTrajectory(route: Route, startTime: Long): RouteWeather {
        val waypoints = route.waypoints
        if (waypoints.isEmpty()) {
            return RouteWeather(route.id, createEmptyModelForecast(), null)
        }

        val trajectoryWaypoints = mutableListOf<WaypointWeather>()
        var currentTime = startTime
        var maxRisk = OverdevelopmentRisk("", 0.0, RiskLevel.LOW)
        var totalHeadwind = 0.0
        var headwindCount = 0

        // Process start point
        val startWp = waypoints.first()
        val startGeo = GeoPoint(startWp.lat, startWp.lon)
        val startForecastUtils = getOrFetchWeather(startGeo, currentTime)
        val startForecastModel = startForecastUtils?.toModelForecast() ?: createEmptyModelForecast()
        
        if (startForecastUtils != null) {
            trajectoryWaypoints.add(
                WaypointWeather(
                    waypointId = startWp.id,
                    location = startGeo,
                    estimatedArrival = currentTime,
                    forecast = startForecastModel
                )
            )
            updateRiskAndHeadwind(startForecastUtils, 0.0, currentTime, maxRisk, totalHeadwind, headwindCount).let {
                maxRisk = it.first
                totalHeadwind = it.second
                headwindCount = it.third
            }
        }

        // Process subsequent waypoints
        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            
            val distKm = calculateDistance(p1.lat, p1.lon, p2.lat, p2.lon)
            val durationHours = distKm / AVERAGE_SPEED_KMH
            val durationMillis = (durationHours * 3600 * 1000).toLong()
            
            currentTime += durationMillis
            
            val p2Geo = GeoPoint(p2.lat, p2.lon)
            val forecastUtils = getOrFetchWeather(p2Geo, currentTime)
            val forecastModel = forecastUtils?.toModelForecast() ?: createEmptyModelForecast()
            
            if (forecastUtils != null) {
                trajectoryWaypoints.add(
                    WaypointWeather(
                        waypointId = p2.id,
                        location = p2Geo,
                        estimatedArrival = currentTime,
                        forecast = forecastModel
                    )
                )
                
                // Calculate bearing for headwind
                val bearing = calculateBearing(p1.lat, p1.lon, p2.lat, p2.lon)
                updateRiskAndHeadwind(forecastUtils, bearing, currentTime, maxRisk, totalHeadwind, headwindCount).let {
                    maxRisk = it.first
                    totalHeadwind = it.second
                    headwindCount = it.third
                }
            }
        }

        // Cache the entire route weather for offline use
        // weatherCache.cacheRouteWeather(trajectoryWaypoints) 

        val avgHeadwind = if (headwindCount > 0) totalHeadwind / headwindCount else 0.0

        return RouteWeather(
            routeId = route.id,
            forecast = startForecastModel,
            trajectoryForecast = TrajectoryForecast(
                waypoints = trajectoryWaypoints,
                maxRisk = maxRisk,
                avgHeadwind = avgHeadwind
            )
        )
    }

    private suspend fun getOrFetchWeather(location: GeoPoint, time: Long): UtilsForecast? {
        // 1. Try Cache
        val cached = weatherCache.queryNearbyWeather(location.latitude, location.longitude, 1000.0)
        if (cached != null && !cached.isStale()) {
            return cached
        }

        // 2. Fetch from API
        if (weatherAPI.isAvailable()) {
            val fetched = weatherAPI.fetchForecast(location.latitude, location.longitude)
            if (fetched != null) {
                weatherCache.cacheWeatherData(location.latitude, location.longitude, fetched)
                return fetched
            }
        }

        return cached
    }

    private fun updateRiskAndHeadwind(
        forecast: UtilsForecast,
        bearing: Double,
        time: Long,
        currentMaxRisk: OverdevelopmentRisk,
        currentTotalHeadwind: Double,
        currentHeadwindCount: Int
    ): Triple<OverdevelopmentRisk, Double, Int> {
        // Find hourly forecast for time
        val period = forecast.hourly.minByOrNull { kotlin.math.abs(it.startTime * 1000 - time) }
        
        var newMaxRisk = currentMaxRisk
        var newTotalHeadwind = currentTotalHeadwind
        var newHeadwindCount = currentHeadwindCount

        if (period != null) {
            // Risk Calculation
            val riskLevel = when {
                period.weather.cloudCover > 80 && period.weather.humidity > 80 -> RiskLevel.HIGH
                period.weather.cloudCover > 50 -> RiskLevel.MODERATE
                else -> RiskLevel.LOW
            }
            
            if (riskLevel > currentMaxRisk.riskLevel) {
                newMaxRisk = OverdevelopmentRisk(
                    peakTime = java.time.Instant.ofEpochMilli(time).toString(),
                    maxCape = 0.0,
                    riskLevel = riskLevel
                )
            }

            // Headwind Calculation
            val windSpeed = period.weather.wind.speed
            val windDir = period.weather.wind.direction
            val angleDiff = Math.toRadians(windDir - bearing - 180)
            val headwind = windSpeed * cos(angleDiff)
            
            newTotalHeadwind += headwind
            newHeadwindCount++
        }
        
        return Triple(newMaxRisk, newTotalHeadwind, newHeadwindCount)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
    
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLam = Math.toRadians(lon2 - lon1)

        val y = Math.sin(dLam) * Math.cos(phi2)
        val x = Math.cos(phi1) * Math.sin(phi2) -
                Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLam)
        var bearing = Math.toDegrees(kotlin.math.atan2(y, x))
        return (bearing + 360) % 360
    }

    private fun createEmptyModelForecast(): ModelForecast {
        return ModelForecast(SkewTForecast(emptyList()), emptyList(), emptyList())
    }
    
    // Extension to convert UtilsForecast to ModelForecast
    private fun UtilsForecast.toModelForecast(): ModelForecast {
        val windPoints = this.hourly.map { period ->
            WindPoint(
                altitude = 0.0, // Surface
                speed = period.weather.wind.speed,
                direction = period.weather.wind.direction,
                gust = period.weather.wind.gust
            )
        }
        
        // Mock CAPE from cloud cover/precip if needed, or leave empty
        val capePoints = this.hourly.map { period ->
            CapePoint(
                time = java.time.Instant.ofEpochSecond(period.startTime).toString(),
                cape = 0.0 // Not available in basic API
            )
        }
        
        return ModelForecast(
            skewT = SkewTForecast(emptyList()), // Not available
            cape = capePoints,
            wind = windPoints
        )
    }
}
