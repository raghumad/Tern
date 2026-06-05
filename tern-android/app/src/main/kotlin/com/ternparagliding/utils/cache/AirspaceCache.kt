package com.ternparagliding.utils.cache

import android.content.Context
import android.util.Log
import com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature
import org.osmdroid.util.GeoPoint
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache manager for airspace data using FlexBuffers and Hilbert indexing
 */
class AirspaceCache(context: Context) {

    companion object {
        const val AIRSPACE_CACHE_HOURS = 2160 // 3 months (90 days)
        private const val TAG = "AirspaceCache"

        /**
         * Bump to invalidate on-disk airspace caches once (forces re-download in
         * the current format). v2 added per-region bounds + populated centroids;
         * v3 (=3) switched to the binary TSI2 index (bbox in the header, no
         * sidecar) + Hilbert-ordered .flex. Safe — airspace data is
         * re-downloadable; only the airspace dir is cleared, never routes.
         */
        const val CACHE_SCHEMA_VERSION = 3
    }

    // Delegate storage and indexing to generic SpatialDiskCache
    private val diskCache = SpatialDiskCache(context, "airspace", AIRSPACE_CACHE_HOURS)
    private val downloadInProgress = ConcurrentHashMap<String, Boolean>()

    init {
        diskCache.clearIfSchemaChanged(CACHE_SCHEMA_VERSION)
    }

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
                    val feature = OverlayGeoJsonParser.parseFeature(featureMap, "airspace")
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
     * Query nearby airspaces across ALL cached countries, without needing to
     * know (geocode) which country the centre is in. The render path uses this
     * so it never blocks on a slow network reverse-geocode — whatever airspace
     * is already on disk near the centre paints immediately (and it naturally
     * shows both countries' airspace near a border). Country *detection* still
     * drives the download path, for fetching countries not yet cached.
     */
    fun queryAllCachedNearby(center: GeoPoint, maxDistanceMiles: Double, limit: Int = 1000): List<OverlayFeature> {
        val countries = diskCache.cacheIndex.keys.toList()
        if (countries.isEmpty()) return emptyList()
        // Skip countries whose data bbox is nowhere near the centre, WITHOUT
        // loading their index. So DC queries only US, Annecy only FR/CH/IT, etc.
        // A country with unknown bounds (legacy, pre-v2) is queried conservatively.
        val queryBounds = MapOverlayCacheUtils.RegionBounds.ofRadius(center, maxDistanceMiles * 1609.34)
        val canFilter = !queryBounds.crossesAntimeridian()
        val t0 = System.currentTimeMillis()
        val queried = ArrayList<String>()
        val result = countries.flatMap { country ->
            val bounds = diskCache.getRegionBounds(country)
            if (canFilter && bounds != null && !bounds.intersects(queryBounds)) {
                emptyList()
            } else {
                queried.add(country)
                diskCache.queryNearby(country, center, maxDistanceMiles, limit)
            }
        }
        Log.d(
            TAG,
            "queryAllCachedNearby @ ${center.latitude},${center.longitude}: queried=$queried " +
                "(of ${countries.size} cached) total=${result.size} in ${System.currentTimeMillis() - t0}ms",
        )
        return result
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
