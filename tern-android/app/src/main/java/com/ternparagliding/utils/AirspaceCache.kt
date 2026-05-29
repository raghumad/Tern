package com.ternparagliding.utils

import android.content.Context
import android.util.Log
import com.ternparagliding.utils.MapOverlayCacheUtils.OverlayFeature
import org.osmdroid.util.GeoPoint
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache manager for airspace data using FlexBuffers and Hilbert indexing
 */
class AirspaceCache(context: Context) {

    companion object {
        const val AIRSPACE_CACHE_HOURS = 2160 // 3 months (90 days)
        private const val TAG = "AirspaceCache"
    }

    // Delegate storage and indexing to generic SpatialDiskCache
    private val diskCache = SpatialDiskCache(context, "airspace", AIRSPACE_CACHE_HOURS)
    private val downloadInProgress = ConcurrentHashMap<String, Boolean>()

    /**
     * Check if cached airspace data exists and is fresh for country
     */
    fun isCached(countryCode: String): Boolean {
        return diskCache.isCached(countryCode)
    }

    /**
     * Get fully loaded cached airspaces for a country
     */
    fun getCachedAirspaces(countryCode: String): List<OverlayFeature>? {
        return diskCache.getCachedFeatures(countryCode)
    }

    /**
     * Cache airspace data from OpenAIP
     */
    // Base URL for API - modifiable for testing
    @Volatile
    private var baseUrl = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f"

    @androidx.annotation.VisibleForTesting
    fun setBaseUrlForTesting(url: String) {
        baseUrl = url
    }

    @androidx.annotation.VisibleForTesting
    fun resetBaseUrlForTesting() {
        baseUrl = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f"
    }

    suspend fun downloadAndCache(countryCode: String): Boolean {
        Log.d(TAG, "Attempting to download airspaces for country: $countryCode")

        if (isCached(countryCode)) {
            Log.d(TAG, "Airspaces already cached for $countryCode, skipping download")
            return true
        }

        val isAlreadyDownloading = downloadInProgress.putIfAbsent(countryCode, true) != null
        if (isAlreadyDownloading) {
            Log.d(TAG, "Download already in progress for airspaces $countryCode")
            return false
        }

        try {
            // OpenAIP URL structure (example)
            val url = if (baseUrl.endsWith("/")) "${baseUrl}${countryCode.lowercase()}_asp.geojson"
                      else "${baseUrl}/${countryCode.lowercase()}_asp.geojson"
            Log.i(TAG, "Airspace download URL check: baseUrl=$baseUrl, finalUrl=$url")
            Log.d(TAG, "Starting airspace download for $countryCode from: $url")

            val success = diskCache.cacheFeaturesStream(countryCode) { appendFeature ->
                GeoJsonUtils.streamGeoJsonFeatures(url) { featureMap ->
                    val feature = MapOverlayCacheUtils.parseFeature(featureMap, "airspace")
                    if (feature != null && validateOverlayFeature(feature, countryCode)) {
                        appendFeature(feature)
                    }
                }
            }

            if (success) {
                Log.d(TAG, "Successfully stream cached airspaces for $countryCode")
                return true
            } else {
                Log.w(TAG, "No valid airspaces found or stream failed for $countryCode")
                clearCacheForRegion(countryCode)
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error caching airspace data for $countryCode: ${e.message}", e)
            clearCacheForRegion(countryCode)
            return false
        } finally {
            downloadInProgress.remove(countryCode)
        }
    }

    /**
     * Query nearby airspaces using Hilbert spatial indexing for memory efficiency
     */
    fun queryNearbyFeatures(countryCode: String, center: GeoPoint, maxDistanceMiles: Double, limit: Int = 1000): List<OverlayFeature> {
        return diskCache.queryNearby(countryCode, center, maxDistanceMiles, limit)
    }

    /**
     * Clear cache for a specific region/country
     */
    fun clearCacheForRegion(countryCode: String) {
        diskCache.clearCacheForRegion(countryCode)
    }

    /**
     * Validate that an OverlayFeature has valid data
     */
    private fun validateOverlayFeature(feature: OverlayFeature, countryCode: String): Boolean {
        try {
            val centroid = feature.centroid
            if (centroid.latitude < -90.0 || centroid.latitude > 90.0 ||
                centroid.longitude < -180.0 || centroid.longitude > 180.0) {
                return false
            }
            if (feature.feature.isEmpty()) return false
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Clear all cached airspace data
     */
    fun clearCache() {
        diskCache.clearAll()
        downloadInProgress.clear()
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): Map<String, Any> {
        return diskCache.getStats().mapKeys { 
            if (it.key == "cacheName") "type" else "airspace${it.key.replaceFirstChar { c -> c.uppercase() }}" 
        }
    }
}
