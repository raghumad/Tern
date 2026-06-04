package com.ternparagliding.utils

import android.content.Context
import android.util.Log
import android.util.Xml
import com.ternparagliding.network.HttpClientProvider
import com.ternparagliding.utils.MapOverlayCacheUtils.OverlayFeature
import okhttp3.Request
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Thermal Hotspot Service - iOS Port
 * Fetches thermal hotspots from thermal.kk7.ch and caches them using SpatialDiskCache.
 *
 * Architecture:
 * - Queries hotspots in 2.5-degree bounding boxes (coarse grid)
 * - Persists to SpatialDiskCache (Hilbert + FlexBuffers)
 * - Pilot Story: "I want to see reliable thermalling spots even when offline"
 */
class ThermalHotspotService(private val context: Context) {

    companion object {
        private const val TAG = "ThermalHotspotService"
        private const val CACHE_NAME = "thermal_hotspots"
        private const val CACHE_HOURS = 720 // 30 days
        private const val BASE_URL = "https://thermal.kk7.ch/api/get_hotspots"
        
        // Coarse grid for region-based caching
        private const val GRID_SIZE = 0.5 
    }

    private val diskCache = SpatialDiskCache(context, CACHE_NAME, CACHE_HOURS)
    private val downloadInProgress = ConcurrentHashMap<String, Boolean>()

    /**
     * Fetch hotspots for a bounding box around a center point.
     * Uses coarse grid caching to reduce API calls and enable offline reuse.
     */
    suspend fun getHotspots(center: GeoPoint, rangeDegrees: Double = 1.25): List<OverlayFeature> {
        val regionKey = getRegionKey(center)
        
        // 1. Check disk cache first (Hilbert + FlexBuffers)
        if (diskCache.isCached(regionKey)) {
            Log.d(TAG, "Loading hotspots from cache for region: $regionKey")
            return diskCache.getCachedFeatures(regionKey) ?: emptyList()
        }

        // 2. Download if not in progress
        if (downloadInProgress.putIfAbsent(regionKey, true) == null) {
            try {
                Log.i(TAG, "Cache miss for region $regionKey. Starting download...")
                val hotspots = downloadAndParse(center, rangeDegrees)
                
                if (hotspots.isNotEmpty()) {
                    Log.i(TAG, "Fetched ${hotspots.size} hotspots. Caching to disk.")
                    diskCache.cacheFeatures(regionKey, hotspots)
                    return hotspots
                } else {
                    Log.w(TAG, "No hotspots found for region $regionKey")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update hotspots for $regionKey: ${e.message}", e)
            } finally {
                downloadInProgress.remove(regionKey)
            }
        } else {
            Log.d(TAG, "Download already in progress for $regionKey")
        }

        return emptyList()
    }

    /**
     * Generates a stable region key based on a coarse grid (0.5 degree)
     */
    private fun getRegionKey(center: GeoPoint): String {
        val lat = (center.latitude / GRID_SIZE).toInt() * GRID_SIZE
        val lon = (center.longitude / GRID_SIZE).toInt() * GRID_SIZE
        return "thermal_${lat}_${lon}".replace(".", "_")
    }

    private suspend fun downloadAndParse(center: GeoPoint, rangeDegrees: Double): List<OverlayFeature> {
        val west = center.longitude - rangeDegrees
        val south = center.latitude - rangeDegrees
        val east = center.longitude + rangeDegrees
        val north = center.latitude + rangeDegrees

        val url = "$BASE_URL?bounds=$west,$south,$east,$north&format=gpx"
        Log.d(TAG, "Executing KK7 API call: $url")

        val client = HttpClientProvider.getInstance(context)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Tern-Aviation-XC-Planner")
            .build()

        return try {
            // Use withContext(Dispatchers.IO) if called from main, but getHotspots is already suspend
            // OkHttp execute() is blocking, so we should ensure Dispatchers.IO
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "KK7 API failed: ${response.code} ${response.message}")
                        return@withContext emptyList<OverlayFeature>()
                    }
                    
                    val gpxData = response.body?.string() ?: return@withContext emptyList<OverlayFeature>()
                    parseGpx(gpxData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error fetching hotspots: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parses KK7 hotspots from GPX format.
     * Extracts probability from the <cmt> tag as per iOS logic.
     */
    private fun parseGpx(gpxData: String): List<OverlayFeature> {
        val features = mutableListOf<OverlayFeature>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(gpxData))

        var eventType = parser.eventType
        
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "wpt") {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                
                var probability = "0"
                var innerEvent = parser.next()
                
                // Seek to <cmt> or </wpt>
                while (innerEvent != XmlPullParser.END_DOCUMENT && 
                    !(innerEvent == XmlPullParser.END_TAG && parser.name == "wpt")) {
                    
                    if (innerEvent == XmlPullParser.START_TAG && parser.name == "cmt") {
                        val cmtText = parser.nextText()
                        // Port of iOS Regex logic
                        // Example cmt: "probability: 0.85, type: thermal"
                        probability = if (cmtText.contains("probability:")) {
                           cmtText.substringAfter("probability:").substringBefore(",").trim()
                        } else {
                            "0.5" // Default if cmt doesn't match format
                        }
                    }
                    innerEvent = parser.next()
                }

                val centroid = GeoPoint(lat, lon)
                val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, 16)
                
                features.add(OverlayFeature(
                    internalId = "thermal_${lat}_${lon}",
                    feature = mapOf(
                        "type" to "thermal",
                        "probability" to probability,
                        "name" to "Thermal Hotspot"
                    ),
                    centroid = centroid,
                    hilbertIndex = hilbertIndex,
                    overlayType = "thermal"
                ))
            }
            eventType = parser.next()
        }
        return features
    }
}
