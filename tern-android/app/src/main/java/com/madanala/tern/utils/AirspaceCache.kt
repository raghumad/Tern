package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import org.osmdroid.util.GeoPoint
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache manager for airspace data using FlexBuffers and Hilbert indexing
 */
class AirspaceCache(context: Context) {

    companion object {
        const val AIRSPACE_CACHE_HOURS = 336 // 14 days
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
    private var baseUrl = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f"

    @androidx.annotation.VisibleForTesting
    fun setBaseUrlForTesting(url: String) {
        baseUrl = url
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
            val url = "$baseUrl/${countryCode.lowercase()}_asp.geojson"
            Log.d(TAG, "Starting airspace download for $countryCode from: $url")

            val geoJsonString = GeoJsonUtils.downloadGeoJson(url)

            if (geoJsonString != null) {
                Log.d(TAG, "Downloaded ${geoJsonString.length} bytes of airspace data for $countryCode")

                // Use NDGeoJSON parser for airspace data (OpenAIP format) if detected, otherwise standard GeoJSON
                val features = if (GeoJsonUtils.isNdGeoJson(geoJsonString)) {
                    MapOverlayCacheUtils.parseNdGeoJsonToFeatures(geoJsonString, "airspace")
                } else {
                    MapOverlayCacheUtils.parseGeoJsonToFeatures(geoJsonString, "airspace")
                }
                Log.d(TAG, "Parsed ${features.size} airspaces for $countryCode")

                val validFeatures = features.filter { feature ->
                    validateOverlayFeature(feature, countryCode)
                }

                if (validFeatures.isNotEmpty()) {
                    // Delegate caching to SpatialDiskCache
                    diskCache.cacheFeatures(countryCode, validFeatures)
                    Log.d(TAG, "Successfully cached ${validFeatures.size} airspaces for $countryCode")
                    return true
                } else {
                    Log.w(TAG, "No valid airspaces found for $countryCode after validation")
                    clearCacheForCountry(countryCode)
                }
            } else {
                Log.w(TAG, "Airspace download returned null/empty for $countryCode")
            }

            return false

        } catch (e: Exception) {
            Log.e(TAG, "Error caching airspace data for $countryCode: ${e.message}", e)
            clearCacheForCountry(countryCode)
            return false
        } finally {
            downloadInProgress.remove(countryCode)
        }
    }

    /**
     * Query nearby airspaces
     * Note: Uses in-memory filtering to bypass potential SpatialDiskCache issues.
     */
    fun queryNearbyFeatures(countryCode: String, center: GeoPoint, maxDistanceMiles: Double): List<OverlayFeature> {
        // Retrieve all features for the country
        val allFeatures = diskCache.getCachedFeatures(countryCode)
        
        if (allFeatures == null) {
            // If cache read failed (e.g. deserialization error or file missing despite isCached=true),
            // we should invalidate the cache so it can be re-downloaded.
            if (isCached(countryCode)) {
                Log.w(TAG, "Failed to read cached features for $countryCode. Invalidating cache.")
                clearCacheForCountry(countryCode)
            }
            return emptyList()
        }
        
        val maxDistanceMeters = maxDistanceMiles * 1609.34
        
        // Filter by distance in memory
        return allFeatures.filter { feature ->
            try {
                // For airspaces (polygons), centroid distance is a good enough approximation for "nearby"
                // The actual intersection check happens in the overlay manager/renderer
                center.distanceToAsDouble(feature.centroid) <= maxDistanceMeters
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Clear cache for a specific country
     */
    private fun clearCacheForCountry(countryCode: String) {
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
