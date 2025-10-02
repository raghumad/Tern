package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.*
import java.nio.*
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import org.osmdroid.util.GeoPoint

/**
 * Cache manager for PG spots data using FlexBuffers and Hilbert indexing
 */
class PGSpotsCache(private val context: Context) {

    companion object {
        // Cache validity periods (in hours) for different data types
        const val AIRSPACE_CACHE_HOURS = 720  // 30 days for airspaces
        const val PG_SPOTS_CACHE_HOURS = 168  // 7 days for PG spots
        const val WEATHER_CACHE_HOURS = 2     // 2 hours for weather data
        const val WAYPOINT_CACHE_HOURS = 168  // 7 days for waypoints
    }

    private val cacheDir: File = File(context.cacheDir, "pgspots_cache")
    private val cacheIndexFile = File(cacheDir, "cache_index")
    private val cacheIndex = ConcurrentHashMap<String, Long>() // countryCode -> timestamp
    private val spatialIndexCache = ConcurrentHashMap<String, MapOverlayCacheUtils.SpatialIndex>() // countryCode -> spatial index
    private val memoryMappedBuffers = ConcurrentHashMap<String, MappedByteBuffer>() // countryCode -> memory mapped buffer

    private val objectMapper = ObjectMapper()
    private val TAG = "PGSpotsCache"

    init {
        cacheDir.mkdirs()
        loadCacheIndex()
    }

    /**
     * Check if PG spots data for a country is cached and not too old
     * PG spots data cached for 7 days since it updates moderately frequently
     * @param countryCode Two-letter country code
     * @param maxAgeHours Maximum age of cached data in hours (default PG_SPOTS_CACHE_HOURS = 7 days)
     * @return true if cached data exists and is fresh
     */
    fun isCached(countryCode: String, maxAgeHours: Int = PG_SPOTS_CACHE_HOURS): Boolean {
        val timestamp = cacheIndex[countryCode] ?: return false
        val ageHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60)
        return ageHours < maxAgeHours
    }

    /**
     * Get cached PG spots features for a country
     * @param countryCode Two-letter country code
     * @return List of OverlayFeature or null if not found
     */
    fun getCachedFeatures(countryCode: String): List<OverlayFeature>? {
        return try {
            val cacheFile = File(cacheDir, "${countryCode}_pgspots.flex")
            if (cacheFile.exists()) {
                val data = cacheFile.readBytes()
                val features = MapOverlayCacheUtils.deserializeFlexBuffersToFeatures(data)

                // Update cache index if file exists (handles transition from old cache validity)
                if (!isCached(countryCode)) {
                    cacheIndex[countryCode] = System.currentTimeMillis()
                    saveCacheIndex()
                    Log.d(TAG, "Updated cache index for existing file: $countryCode")
                }

                features
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached features for $countryCode", e)
            null
        }
    }

    /**
     * Download and cache PG spots data for a country
     * @param countryCode Two-letter country code
     * @return List of OverlayFeature or null if failed
     */
    fun downloadAndCacheData(countryCode: String): List<OverlayFeature>? {
        return try {
            // Import the network client
            val httpClient = com.madanala.tern.network.HttpClientProvider.getInstance(context)

            // For now, return empty list as placeholder (replace with actual API call)
            // This would download from ParaglidingEarth API endpoint
            val features: List<OverlayFeature> = emptyList() // Placeholder

            if (features.isNotEmpty()) {
                // Create spatial index and serialize features with byte offsets
                val (spatialIndex, data) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(features)

                // Save FlexBuffer data
                val flexCacheFile = File(cacheDir, "${countryCode}_pgspots.flex")
                flexCacheFile.writeBytes(data)

                // Save spatial index
                val indexFile = File(cacheDir, "${countryCode}_pgspots.idx")
                val indexData = objectMapper.writeValueAsBytes(spatialIndex)
                indexFile.writeBytes(indexData)

                // Cache spatial index in memory
                spatialIndexCache[countryCode] = spatialIndex

                // Update cache index
                cacheIndex[countryCode] = System.currentTimeMillis()
                saveCacheIndex()

                Log.d(TAG, "Cached ${features.size} PG spots features for $countryCode with Hilbert spatial index")
            } else {
                Log.w(TAG, "No PG spots features found for $countryCode")
            }

            features
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading PG spots data for $countryCode", e)
            null
        }
    }

    /**
     * Query nearby PG spots features using Hilbert spatial index with memory-mapped I/O
     * @param countryCode Two-letter country code
     * @param center Center point
     * @param maxDistanceMiles Max distance in miles
     * @return List of nearby OverlayFeature
     */
    fun queryNearbyFeatures(countryCode: String, center: GeoPoint, maxDistanceMiles: Double): List<OverlayFeature> {
        try {
            // Get or load spatial index
            val spatialIndex = getSpatialIndex(countryCode) ?: return emptyList()

            // Get memory-mapped buffer for the FlexBuffer file
            val mappedBuffer = getMemoryMappedBuffer(countryCode) ?: return emptyList()

            // Compute center Hilbert index
            val centerIndex = MapOverlayCacheUtils.computeHilbertIndex(center, spatialIndex.bits)

            // Define Hilbert range for query (adjust based on distance)
            val maxDistanceMeters = maxDistanceMiles * 1609.34
            // Rough approximation: larger distance needs larger Hilbert range
            val range = (maxDistanceMeters / 1000.0 * 1000.0).toLong().coerceAtLeast(1000)

            // Find relevant index entries
            val relevantEntries = spatialIndex.findNearbyIndices(centerIndex, range)

            // Read and deserialize only the relevant features using memory-mapped buffer
            var processedCount = 0
            val nearbyFeatures = relevantEntries.mapNotNull { entry ->
                try {
                    processedCount++

                    // Read the specific byte range for this feature from memory-mapped buffer
                    val featureBytes = ByteArray(entry.byteLength)
                    synchronized(mappedBuffer) {
                        mappedBuffer.position(entry.byteOffset)
                        mappedBuffer.get(featureBytes, 0, entry.byteLength)
                    }

                    val featureJson = String(featureBytes, Charsets.UTF_8)

                    val featureData: Map<String, Any> = objectMapper.readValue(featureJson, object : TypeReference<Map<String, Any>>() {})

                    // Check if this is the new format (with "feature" key) or old format (raw feature)
                    @Suppress("UNCHECKED_CAST")
                    val feature = featureData["feature"] as? Map<String, Any> ?: featureData
                    @Suppress("UNCHECKED_CAST")
                    var centroidData = featureData["centroid"] as? Map<String, Any>
                    @Suppress("UNCHECKED_CAST")
                    val properties = feature["properties"] as? Map<String, Any> ?: emptyMap()
                    var latitude = centroidData?.get("latitude") as? Double ?: featureData["lat"] as? Double
                    var longitude = centroidData?.get("longitude") as? Double ?: featureData["lon"] as? Double
                    var hilbertIndex = featureData["hilbertIndex"] as? Long ?: featureData["hilbert"] as? Long
                    val overlayType = featureData["overlayType"] as? String ?: "pgspot"

                    // If centroid data is missing (old cache format or raw feature), compute it from geometry
                    if (centroidData == null && feature != null) {
                        val geometry = feature["geometry"] as? Map<String, Any>
                        if (geometry != null) {
                            val computedCentroid = MapOverlayCacheUtils.computeCentroid(geometry)
                            if (computedCentroid != null) {
                                centroidData = mapOf(
                                    "latitude" to computedCentroid.latitude,
                                    "longitude" to computedCentroid.longitude
                                )
                                latitude = computedCentroid.latitude
                                longitude = computedCentroid.longitude
                            }
                        }
                    } else if (centroidData != null) {
                        // Use existing centroid data
                        latitude = centroidData["latitude"] as? Double
                        longitude = centroidData["longitude"] as? Double
                    }

                    // Compute hilbert index if missing (regardless of whether centroid was computed or already present)
                    if (hilbertIndex == null && latitude != null && longitude != null) {
                        val centroidPoint = GeoPoint(latitude, longitude)
                        hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroidPoint, spatialIndex.bits)
                    }

                    if (feature == null || centroidData == null || latitude == null || longitude == null || hilbertIndex == null) {
                        Log.w(TAG, "Missing required data in feature: feature=$feature, centroid=$centroidData, lat=$latitude, lon=$longitude, hilbert=$hilbertIndex")
                        return@mapNotNull null
                    }

                    val centroid = GeoPoint(latitude, longitude)
                    val overlayFeature = OverlayFeature(feature, centroid, hilbertIndex, overlayType)

                    // Final distance check (Hilbert is approximate)
                    val distance = center.distanceToAsDouble(centroid)
                    if (distance <= maxDistanceMeters) overlayFeature else null
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading feature at offset ${entry.byteOffset}: ${e.message}", e)
                    null
                }
            }

            return nearbyFeatures

        } catch (e: Exception) {
            Log.e(TAG, "Error querying nearby features for $countryCode", e)
            return emptyList()
        }
    }

    /**
     * Get or load spatial index for a country
     */
    private fun getSpatialIndex(countryCode: String): MapOverlayCacheUtils.SpatialIndex? {
        // Check in-memory cache first
        spatialIndexCache[countryCode]?.let { return it }

        // Load from disk
        return try {
            val indexFile = File(cacheDir, "${countryCode}_pgspots.idx")
            if (indexFile.exists()) {
                val indexData = indexFile.readBytes()
                val indexJson = String(indexData, Charsets.UTF_8)
                val spatialIndex = objectMapper.readValue(indexJson, MapOverlayCacheUtils.SpatialIndex::class.java)
                spatialIndexCache[countryCode] = spatialIndex
                spatialIndex
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading spatial index for $countryCode", e)
            null
        }
    }

    /**
     * Get or create memory-mapped buffer for a country's FlexBuffer file
     */
    private fun getMemoryMappedBuffer(countryCode: String): MappedByteBuffer? {
        // Check in-memory cache first
        memoryMappedBuffers[countryCode]?.let { return it }

        // Create memory-mapped buffer
        return try {
            val dataFile = File(cacheDir, "${countryCode}_pgspots.flex")
            if (!dataFile.exists()) return null

            val randomAccessFile = RandomAccessFile(dataFile, "r")
            val fileChannel = randomAccessFile.channel
            val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, dataFile.length())

            memoryMappedBuffers[countryCode] = buffer
            buffer
        } catch (e: Exception) {
            Log.e(TAG, "Error creating memory-mapped buffer for $countryCode", e)
            null
        }
    }

    /**
     * Clear all cached PG spots data
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.endsWith("_pgspots.flex") || file.name.endsWith("_pgspots.idx")) {
                    file.delete()
                }
            }
            cacheIndex.clear()
            spatialIndexCache.clear()
            memoryMappedBuffers.clear()
            saveCacheIndex()
            Log.d(TAG, "Cleared all PG spots cache (FlexBuffers and indices)")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    /**
     * Get cache statistics
     * @return Map with cache statistics
     */
    fun getCacheStats(): Map<String, Any> {
        val flexFiles = cacheDir.listFiles()?.filter { it.name.endsWith("_pgspots.flex") } ?: emptyList()
        val totalSize = flexFiles.sumOf { it.length() }
        val fileCount = flexFiles.size

        return mapOf(
            "totalFiles" to fileCount,
            "totalSizeBytes" to totalSize,
            "totalSizeMB" to String.format("%.2f", totalSize / (1024.0 * 1024.0)),
            "countries" to cacheIndex.keys.toList()
        )
    }

    /**
     * Load cache index from disk
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadCacheIndex() {
        try {
            if (cacheIndexFile.exists()) {
                FileInputStream(cacheIndexFile).use { fis ->
                    ObjectInputStream(fis).use { ois ->
                        val loadedIndex = ois.readObject() as? Map<String, Long>
                        if (loadedIndex != null) {
                            cacheIndex.putAll(loadedIndex)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache index", e)
            // Reset cache index if corrupted
            cacheIndex.clear()
        }
    }

    /**
     * Save cache index to disk
     */
    private fun saveCacheIndex() {
        try {
            FileOutputStream(cacheIndexFile).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(cacheIndex.toMap())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cache index", e)
        }
    }
}
