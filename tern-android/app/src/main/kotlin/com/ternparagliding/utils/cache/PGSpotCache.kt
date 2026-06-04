package com.ternparagliding.utils.cache

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PG SPOT CACHE - FlexBuffers + Hilbert Spatial Indexing
 * Aviation-grade caching system for paragliding site data
 *
 * Architecture follows AirspaceCache pattern but handles standard GeoJSON:
 * - FlexBuffers for compressed binary storage
 * - Hilbert curve spatial indexing for fast locality queries
 * - Memory-mapped I/O for zero-copy performance
 * - Aviation resilience with graceful degradation
 */
class PGSpotCache(context: Context) {

    companion object {
        const val PG_SPOT_CACHE_HOURS = 4320  // 6 months (180 days)
        private const val TAG = "PGSpotCache"
        private const val USER_AGENT = "Tern-Paragliding-App/1.0"

        // Base URL for API - moved to companion for global test redirection
        @Volatile
        var baseUrl = "https://www.paraglidingearth.com/api/geojson"
            private set

        @androidx.annotation.VisibleForTesting
        fun setBaseUrlForTesting(url: String) {
            baseUrl = url
        }

        @androidx.annotation.VisibleForTesting
        fun resetBaseUrlForTesting() {
            baseUrl = "https://www.paraglidingearth.com/api/geojson"
        }
    }

    // Delegate storage and indexing to generic SpatialDiskCache
    private val diskCache = SpatialDiskCache(context, "pgspots", PG_SPOT_CACHE_HOURS)
    private val downloadInProgress = ConcurrentHashMap<String, Boolean>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Check if cached PG spot data exists and is fresh for country
     */
    fun isCached(countryCode: String): Boolean {
        return diskCache.isCached(countryCode)
    }

    /**
     * Get fully loaded cached PG spots for a country
     */
    fun getCachedPGSpots(countryCode: String): List<OverlayFeature>? {
        return diskCache.getCachedFeatures(countryCode)
    }


    /**
     * Cache PG spots data from standard GeoJSON API
     */
    suspend fun downloadAndCache(countryCode: String): List<OverlayFeature>? {
        Log.d(TAG, "Attempting to download PG spots for country: $countryCode")

        if (isCached(countryCode)) {
            Log.d(TAG, "PG spots already cached for $countryCode, skipping download")
            return getCachedPGSpots(countryCode)
        }

        val isAlreadyDownloading = downloadInProgress.putIfAbsent(countryCode, true) != null
        Log.d(TAG, "Download status for $countryCode: isAlreadyDownloading=$isAlreadyDownloading")

        if (isAlreadyDownloading) {
            Log.d(TAG, "Download already in progress for PG spots $countryCode, skipping duplicate")
            return null
        }

        try {
            // New PG Earth API structure (v2)
            // Example: https://www.paraglidingearth.com/api/geojson/getCountrySites.php?iso=us
            val url = if (baseUrl.endsWith("/")) "${baseUrl}getCountrySites.php?iso=${countryCode.lowercase()}"
                      else "${baseUrl}/getCountrySites.php?iso=${countryCode.lowercase()}"
            Log.d(TAG, "Starting PG spots download for $countryCode from: $url")

            // Implement exponential backoff for polite API usage
            var backoffMs = 1000L
            var geoJsonString: String? = null
            var lastError: Exception? = null

            for (attempt in 1..3) {
                try {
                    geoJsonString = GeoJsonUtils.downloadGeoJson(url, USER_AGENT)
                    if (geoJsonString != null) break
                    
                    Log.w(TAG, "Download attempt $attempt returned null for $countryCode, retrying in ${backoffMs}ms...")
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Download attempt $attempt failed for $countryCode: ${e.message}, retrying in ${backoffMs}ms...")
                }
                
                kotlinx.coroutines.delay(backoffMs)
                backoffMs *= 2
            }

            if (geoJsonString != null) {
                Log.d(TAG, "Downloaded ${geoJsonString.length} bytes of PG spots data for $countryCode")
                if (geoJsonString.length < 500) {
                    Log.d(TAG, "PG Spot Data Preview: $geoJsonString")
                }

                // Use dynamic parsing to support both standard GeoJSON and NDGeoJSON
                val features = if (GeoJsonUtils.isNdGeoJson(geoJsonString)) {
                    OverlayGeoJsonParser.parseNdGeoJsonToFeatures(geoJsonString, "pgspot")
                } else {
                    OverlayGeoJsonParser.parseGeoJsonToFeatures(geoJsonString, "pgspot")
                }
                Log.d(TAG, "Parsed ${features.size} PG spots for $countryCode")

                val validFeatures = features.filter { feature ->
                    validateOverlayFeature(feature, countryCode)
                }

                if (validFeatures.isNotEmpty()) {
                    Log.d(TAG, "Validated ${validFeatures.size}/${features.size} PG spot features for $countryCode")
                    
                    // Delegate caching to SpatialDiskCache
                    diskCache.cacheFeatures(countryCode, validFeatures)

                    Log.d(TAG, "Successfully cached ${validFeatures.size} PG spots for $countryCode")
                    return validFeatures
                } else {
                    Log.w(TAG, "No valid PG spots found for $countryCode after validation. Raw features: ${features.size}")
                    clearCacheForRegion(countryCode)
                }
            } else {
                Log.w(TAG, "PG spots download returned null/empty for $countryCode")
            }

            return null

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "PG spots download cancelled for $countryCode")
            clearCacheForRegion(countryCode)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error caching PG spots data for $countryCode: ${e.message}", e)
            clearCacheForRegion(countryCode)
            return null
        } finally {
            downloadInProgress.remove(countryCode)
        }
    }

    /**
     * Query nearby PG spots using Hilbert spatial indexing for memory efficiency
     */
    fun queryNearbyPGSpots(countryCode: String, center: GeoPoint, maxDistanceMiles: Double, limit: Int = 1000): List<OverlayFeature> {
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
                Log.w(TAG, "Invalid centroid coordinates for PG spot in $countryCode: ${centroid.latitude}, ${centroid.longitude}")
                return false
            }

            if (feature.feature.isEmpty()) {
                Log.w(TAG, "Empty feature data for PG spot in $countryCode")
                return false
            }

            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error validating PG spot feature in $countryCode: ${e.message}")
            return false
        }
    }

    /**
     * Clean up corrupted cache files
     */
    fun cleanupCorruptedCache() {
        // SpatialDiskCache handles integrity checks on access, but we can iterate if needed.
        // For now, we rely on isCached() checks.
        diskCache.cacheIndex.keys.toList().forEach { countryCode ->
            if (!diskCache.isCached(countryCode)) {
                Log.w(TAG, "Cleaning up corrupted cache for $countryCode")
                clearCacheForRegion(countryCode)
            }
        }
    }

    /**
     * Check for modified sites and invalidate affected caches to trigger a rebuild
     * @param days Number of days to look back for modifications (default 7)
     */
    suspend fun checkForUpdates(days: Int = 7) {
        try {
            val url = if (baseUrl.endsWith("/")) "${baseUrl}getModifiedSites.php?days=$days"
                      else "${baseUrl}/getModifiedSites.php?days=$days"
            Log.i(TAG, "Checking for incremental PG spot updates from: $url")

            val geoJsonString = GeoJsonUtils.downloadGeoJson(url, USER_AGENT)
            if (geoJsonString == null) return

            // Parse features to find which countries have new/modified data
            val features = if (GeoJsonUtils.isNdGeoJson(geoJsonString)) {
                OverlayGeoJsonParser.parseNdGeoJsonToFeatures(geoJsonString, "pgspot")
            } else {
                OverlayGeoJsonParser.parseGeoJsonToFeatures(geoJsonString, "pgspot")
            }

            if (features.isEmpty()) {
                Log.d(TAG, "No modified PG spots found in last $days days")
                return
            }

            // Identify affected countries using properties["countryCode"]
            val affectedCountries = features.mapNotNull { feature ->
                // Accessing properties via Jackson map returned by GeoJsonUtils
                @Suppress("UNCHECKED_CAST")
                val properties = feature.feature["properties"] as? Map<String, Any>
                properties?.get("countryCode") as? String
            }.toSet()

            Log.i(TAG, "Detected ${features.size} modified sites across ${affectedCountries.size} countries: $affectedCountries")

            // Invalidate caches for affected countries to trigger full rebuild on next access
            affectedCountries.forEach { countryCode ->
                val upperCode = countryCode.uppercase()
                if (isCached(upperCode)) {
                    Log.i(TAG, "Invalidating PG spot cache for $upperCode due to remote modifications")
                    clearCacheForRegion(upperCode)
                    
                    // PROACTIVE: Trigger background download for active regions (those already cached)
                    // We don't wait for it here
                    scope.launch {
                        downloadAndCache(upperCode)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking for PG spot updates: ${e.message}")
        }
    }

    /**
     * Clear all cached PG spots data
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
            if (it.key == "cacheName") "type" else "pgSpots${it.key.replaceFirstChar { c -> c.uppercase() }}" 
        }
    }
}
