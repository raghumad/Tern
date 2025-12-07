package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

/**
 * Weather Cache - FlexBuffers + Hilbert Spatial Indexing
 * Stores weather forecasts spatially for efficient retrieval.
 * Refactored to group data by Country/Region to prevent file explosion.
 */
class WeatherCache(private val context: Context) {

    companion object {
        const val WEATHER_CACHE_HOURS = 4 // Default expiration
        private const val TAG = "WeatherCache"
        
        // Aviation-specific constants from PGSpotWeatherCache
        private const val WEATHER_CACHE_EXPIRATION_HOURS = 2L  // Weather data expires in 2 hours
        private const val FORECAST_CACHE_HOURS = 12L   // Forecasts last 12 hours
        private const val SPATIAL_PRECISION_METERS = 1000 // 1km weather precision
    }

    // Delegate storage and indexing to generic SpatialDiskCache
    // Cache name "weather" -> files will be {countryCode}_weather.flex
    private val diskCache = SpatialDiskCache(context, "weather", WEATHER_CACHE_HOURS)
    private val objectMapper = jacksonObjectMapper()
    
    // L1 Memory Cache
    private val memoryCache = androidx.collection.LruCache<String, CachedWeatherData>(50)

    data class CachedWeatherData(
        val forecast: WeatherForecast,
        val centroid: GeoPoint,
        val cacheTime: Long,
        val expirationTime: Long
    )

    /**
     * Cache weather forecast for a specific location (e.g. Route centroid or PG Spot)
     */
    fun cacheWeather(id: String, location: GeoPoint, forecast: WeatherForecast) {
        try {
            // 1. Determine expiration
            val expirationTime = if (forecast.current != null) {
                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(WEATHER_CACHE_EXPIRATION_HOURS)
            } else {
                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(FORECAST_CACHE_HOURS)
            }

            // 2. Cache in Memory (L1)
            val cacheKey = createCacheKey(location)
            val cachedData = CachedWeatherData(
                forecast = forecast,
                centroid = location,
                cacheTime = System.currentTimeMillis(),
                expirationTime = expirationTime
            )
            memoryCache.put(cacheKey, cachedData)

            // 3. Cache in Disk (L2) via SpatialDiskCache
            // Determine Country Code for Region ID
            val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, location) ?: "unknown"
            
            // Convert WeatherForecast to Map for OverlayFeature
            val forecastMap = objectMapper.convertValue(forecast, object : TypeReference<Map<String, Any>>() {})
            
            // Create OverlayFeature
            val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(location, 16)
            val feature = OverlayFeature(
                feature = forecastMap,
                centroid = location,
                hilbertIndex = hilbertIndex,
                overlayType = "weather"
            )

            // Cache as a single-item list (appends to country file)
            diskCache.cacheFeatures(countryCode, listOf(feature))

        } catch (e: Exception) {
            Log.e(TAG, "Error caching weather for $id", e)
        }
    }
    
    // cacheRouteWeather removed to avoid type mismatch (Model vs Utils Forecast)
    // TrajectoryAnalyzer handles caching of source data point-by-point.
    
    /**
     * Alias for cacheWeather to match PGSpotWeatherCache API (for easier migration)
     */
    fun cacheWeatherData(latitude: Double, longitude: Double, forecast: WeatherForecast) {
        val id = "weather_${latitude}_${longitude}"
        cacheWeather(id, GeoPoint(latitude, longitude), forecast)
    }

    /**
     * Query nearby weather forecasts (L1 + L2)
     */
    fun queryNearbyWeather(id: String, center: GeoPoint, maxDistanceMiles: Double): List<WeatherForecast> {
        // 1. Check L1 Memory Cache first
        val maxDistanceMeters = maxDistanceMiles * 1609.34
        val memoryResult = checkMemoryCache(center, maxDistanceMeters)
        if (memoryResult != null) {
            return listOf(memoryResult)
        }

        // 2. Check L2 Disk Cache
        try {
            // Determine Country Code for Region ID
            val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, center) ?: "unknown"
            
            val features = diskCache.queryNearby(countryCode, center, maxDistanceMiles)
            
            return features.mapNotNull { feature ->
                try {
                    val featureMap = feature.feature
                    objectMapper.convertValue(featureMap, WeatherForecast::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "Error deserializing weather forecast: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying weather for $id", e)
            return emptyList()
        }
    }
    
    /**
     * Overload for PGSpotOverlayManager compatibility (returns single result or null)
     */
    fun queryNearbyWeather(latitude: Double, longitude: Double, maxDistanceMeters: Double = 10000.0): WeatherForecast? {
        val center = GeoPoint(latitude, longitude)
        val maxDistanceMiles = maxDistanceMeters / 1609.34
        
        // 1. Check L1 Memory Cache
        val memoryResult = checkMemoryCache(center, maxDistanceMeters)
        if (memoryResult != null) return memoryResult
        
        // 2. Check L2 Disk Cache
        // Use a generic ID since we are querying by location
        val results = queryNearbyWeather("query", center, maxDistanceMiles)
        return results.firstOrNull()
    }

    private fun checkMemoryCache(center: GeoPoint, maxDistanceMeters: Double): WeatherForecast? {
        try {
            val snapshot = memoryCache.snapshot()
            for ((key, cached) in snapshot) {
                // Check expiration
                if (System.currentTimeMillis() > cached.expirationTime) {
                    memoryCache.remove(key)
                    continue
                }
                
                // Check distance
                val distance = center.distanceToAsDouble(cached.centroid)
                if (distance <= maxDistanceMeters) {
                    return cached.forecast
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking memory cache", e)
        }
        return null
    }

    fun clearCache() {
        memoryCache.evictAll()
        diskCache.clearAll()
    }
    
    private fun createCacheKey(centroid: GeoPoint): String {
        return String.format("%.4f_%.4f", centroid.latitude, centroid.longitude)
    }
}
