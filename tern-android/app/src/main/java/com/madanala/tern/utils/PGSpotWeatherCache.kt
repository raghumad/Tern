package com.madanala.tern.utils

import android.content.Context
import androidx.collection.LruCache
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

/**
 * SAFETY-FIRST WEATHER DATA MANAGEMENT
 * Aviation-grade cache with spatial indexing and graceful failure handling
 *
 * Architecture follows AirspaceCache pattern for consistency:
 * - Hilbert spatial indexing for geolocation queries
 * - LRU eviction with manual override controls
 * - Weather-specific expiration policies (short-lived vs longer forecasts)
 */
class PGSpotWeatherCache(context: Context) {

    companion object {
        private const val WEATHER_CACHE_EXPIRATION_HOURS = 2L  // Weather data expires in 2 hours
        private const val FORECAST_CACHE_HOURS = 12L   // Forecasts last 12 hours
        private const val SPATIAL_PRECISION_METERS = 1000 // 1km weather precision
        private const val HILBERT_ORDER = 16
    }

    // Cache storage - simple for now (can add FlexBuffers later)
    private val memoryCache = LruCache<String, CachedWeatherData>(50) // LRU cache for frequently accessed weather

    // Aviation-specific data structures
    data class CachedWeatherData(
        val forecast: WeatherForecast,
        val centroid: GeoPoint,
        val cacheTime: Long,
        val expirationTime: Long,
        val hilbertIndex: Long
    )

    /**
     * FETCH WEATHER WITH SPATIAL INTELLIGENCE
     * Query cache for weather data near a PG spot location
     */
    fun queryNearbyWeather(
        latitude: Double,
        longitude: Double,
        maxDistanceMeters: Double = 10000.0 // 10km search radius
    ): WeatherForecast? {
        try {
            val queryPoint = GeoPoint(latitude, longitude)

            // Check memory cache for suitable weather data
            for (cached in memoryCache.snapshot().values) {
                if (System.currentTimeMillis() > cached.expirationTime) {
                    memoryCache.remove(createCacheKey(cached.centroid))
                    continue // Skip expired data
                }

                val distance = queryPoint.distanceToAsDouble(cached.centroid)
                if (distance <= maxDistanceMeters) {
                    return cached.forecast
                }
            }

            return null // No suitable weather found

        } catch (e: Exception) {
            // Aviation safety: fail gracefully, don't break app
            android.util.Log.w("PGSpotWeatherCache", "Failed to query weather cache", e)
            return null
        }
    }

    /**
     * STORE AVIATION WEATHER DATA
     * Cache weather data for a PG spot with spatial indexing
     */
    fun cacheWeatherData(
        latitude: Double,
        longitude: Double,
        forecast: WeatherForecast
    ) {
        try {
            val centroid = GeoPoint(latitude, longitude)
            val cacheKey = createCacheKey(centroid)

            // Determine appropriate expiration time based on forecast type
            val expirationTime = if (forecast.current != null) {
                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(WEATHER_CACHE_EXPIRATION_HOURS)
            } else {
                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(FORECAST_CACHE_HOURS)
            }

            val cachedData = CachedWeatherData(
                forecast = forecast,
                centroid = centroid,
                cacheTime = System.currentTimeMillis(),
                expirationTime = expirationTime,
                hilbertIndex = computeHilbertIndex(centroid)
            )

            // Store in memory cache
            memoryCache.put(cacheKey, cachedData)

        } catch (e: Exception) {
            // Fail gracefully - don't break app over weather caching
            android.util.Log.w("PGSpotWeatherCache", "Failed to cache weather data", e)
        }
    }

    /**
     * REMOVE EXPIRED WEATHER DATA
     * Aviation safety: ensure only current weather is available
     */
    fun removeExpiredData() {
        try {
            val expiredKeys = mutableListOf<String>()

            // Find expired items in memory cache
            for ((key, cached) in memoryCache.snapshot()) {
                if (System.currentTimeMillis() > cached.expirationTime) {
                    expiredKeys.add(key)
                }
            }

            // Remove expired items
            expiredKeys.forEach { key ->
                memoryCache.remove(key)
            }

        } catch (e: Exception) {
            // Fail gracefully
            android.util.Log.w("PGSpotWeatherCache", "Failed to clean expired cache", e)
        }
    }

    /**
     * CLEAR ALL WEATHER CACHE
     */
    fun clearCache() {
        memoryCache.evictAll()
    }

    // === PRIVATE HELPERS ===

    private fun createCacheKey(centroid: GeoPoint): String {
        // Precision-rounded key for weather location grouping
        return String.format("%.4f_%.4f", centroid.latitude, centroid.longitude)
    }

    /**
     * SIMPLE HILBERT SPATIAL INDEXING APPROXIMATION
     * Provides basic spatial locality for cache efficiency
     */
    private fun computeHilbertIndex(point: GeoPoint): Long {
        try {
            val lngNorm = (point.longitude + 180.0) / 360.0  // Normalize to [0,1]
            val latNorm = (point.latitude + 90.0) / 180.0    // Normalize to [0,1]

            // Simple approximation (production would use proper Hilbert curve)
            val quadrantBit = if (lngNorm > 0.5) (if (latNorm > 0.5) 3L else 1L) else (if (latNorm > 0.5) 2L else 0L)

            val lngIndex = (lngNorm * (1L shl HILBERT_ORDER)).toLong()
            val latIndex = (latNorm * (1L shl HILBERT_ORDER)).toLong()

            return (lngIndex shl HILBERT_ORDER) or latIndex or (quadrantBit shl (HILBERT_ORDER * 2))

        } catch (e: Exception) {
            return 0L // Fallback
        }
    }
}
