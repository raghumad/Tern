package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
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
        const val PG_SPOT_CACHE_HOURS = 168  // 7 days
        private const val TAG = "PGSpotCache"
    }

    // Delegate storage and indexing to generic SpatialDiskCache
    private val diskCache = SpatialDiskCache(context, "pgspots", PG_SPOT_CACHE_HOURS)
    private val downloadInProgress = ConcurrentHashMap<String, Boolean>()

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
            val url = "https://www.paraglidingearth.com/api/geojson/getCountrySites.php?iso=${countryCode.lowercase()}&style=detailed"
            Log.d(TAG, "Starting PG spots download for $countryCode from: $url")

            val geoJsonString = GeoJsonUtils.downloadGeoJson(url)

            if (geoJsonString != null) {
                Log.d(TAG, "Downloaded ${geoJsonString.length} bytes of PG spots data for $countryCode")

                val features = MapOverlayCacheUtils.parseGeoJsonToFeatures(geoJsonString, "pgspot")
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
                    Log.w(TAG, "No valid PG spots found for $countryCode after validation")
                    clearCacheForCountry(countryCode)
                }
            } else {
                Log.w(TAG, "PG spots download returned null/empty for $countryCode")
            }

            return null

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "PG spots download cancelled for $countryCode")
            clearCacheForCountry(countryCode)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error caching PG spots data for $countryCode: ${e.message}", e)
            clearCacheForCountry(countryCode)
            return null
        } finally {
            downloadInProgress.remove(countryCode)
        }
    }

    /**
     * Query nearby PG spots using Hilbert spatial indexing
     */
    fun queryNearbyPGSpots(countryCode: String, center: GeoPoint, maxDistanceMiles: Double): List<OverlayFeature> {
        return diskCache.queryNearby(countryCode, center, maxDistanceMiles)
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
                clearCacheForCountry(countryCode)
            }
        }
    }

    /**
     * Clear all cached PG spots data
     */
    fun clearCache() {
        diskCache.clearAll()
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
