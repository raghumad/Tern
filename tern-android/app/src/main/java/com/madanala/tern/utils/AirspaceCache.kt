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
 * Cache manager for airspace data using FlexBuffers and Hilbert indexing
 */
class AirspaceCache(private val context: Context) {

    companion object {
        // Cache validity periods (in hours) for different data types
        const val AIRSPACE_CACHE_HOURS = 720  // 30 days for airspaces
        const val WEATHER_CACHE_HOURS = 2     // 2 hours for weather data
        const val WAYPOINT_CACHE_HOURS = 168  // 7 days for waypoints
    }

    private val cacheDir: File = File(context.cacheDir, "airspace_cache")
    private val cacheIndexFile = File(cacheDir, "cache_index")
    private val cacheIndex = ConcurrentHashMap<String, Long>() // countryCode -> timestamp
    private val spatialIndexCache = ConcurrentHashMap<String, MapOverlayCacheUtils.SpatialIndex>() // countryCode -> spatial index
    private val memoryMappedBuffers = ConcurrentHashMap<String, MappedByteBuffer>() // countryCode -> memory mapped buffer
    private val downloadInProgress = ConcurrentHashMap<String, Boolean>() // countryCode -> download flag

    private val objectMapper = ObjectMapper()
    private val TAG = "AirspaceCache"

    init {
        cacheDir.mkdirs()
        loadCacheIndex()
    }

    /**
     * Check if airspace data for a country is cached and not too old
     * Airspace data has a longer cache validity of 30 days since it doesn't change frequently
     * @param countryCode Two-letter country code
     * @param maxAgeHours Maximum age of cached data in hours (default AIRSPACE_CACHE_HOURS = 30 days for airspaces)
     * @return true if cached data exists and is fresh
     */
    fun isCached(countryCode: String, maxAgeHours: Int = AIRSPACE_CACHE_HOURS): Boolean {
        val timestamp = cacheIndex[countryCode] ?: return false
        val ageHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60)
        return ageHours < maxAgeHours
    }

    /**
     * Get cached airspace features for a country
     * @param countryCode Two-letter country code
     * @return List of OverlayFeature or null if not found
     */
    fun getCachedFeatures(countryCode: String): List<OverlayFeature>? {
        return try {
            val cacheFile = File(cacheDir, "${countryCode}_airspace.flex")
            Log.d(TAG, "Checking for cached airspace file: ${cacheFile.absolutePath}")

            if (cacheFile.exists()) {
                Log.d(TAG, "Cache file exists for $countryCode, size: ${cacheFile.length()} bytes")

                val data = cacheFile.readBytes()
                Log.d(TAG, "Read ${data.size} bytes from cache file")

                val features = MapOverlayCacheUtils.deserializeFlexBuffersToFeatures(data)

                if (features != null) {
                    Log.d(TAG, "Successfully deserialized ${features.size} airspace features for $countryCode")

                    // Update cache index if file exists (handles transition from old cache validity)
                    if (!isCached(countryCode)) {
                        cacheIndex[countryCode] = System.currentTimeMillis()
                        saveCacheIndex()
                        Log.d(TAG, "Updated cache index for existing file: $countryCode")
                    }

                    features
                } else {
                    Log.w(TAG, "Deserialization returned null for $countryCode")
                    null
                }
            } else {
                Log.d(TAG, "No cache file found for $countryCode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached features for $countryCode", e)
            null
        }
    }

    /**
     * Cache airspace data for a country
     * @param countryCode Two-letter country code
     * @param ndGeoJsonString The NDGeoJSON data to cache
     */
    fun cacheData(countryCode: String, ndGeoJsonString: String) {
        Log.d(TAG, "Starting cacheData for $countryCode, input size: ${ndGeoJsonString.length}")

        // Prevent duplicate caching operations
        if (downloadInProgress.putIfAbsent(countryCode, true) == true) {
            Log.d(TAG, "Caching already in progress for $countryCode, skipping duplicate")
            return
        }

        try {
            Log.d(TAG, "Parsing NDGeoJSON for $countryCode")
            // Parse to features
            val features = MapOverlayCacheUtils.parseNdGeoJsonToFeatures(ndGeoJsonString)
            Log.d(TAG, "Parsed ${features.size} features for $countryCode")

            if (features.isNotEmpty()) {
                Log.d(TAG, "Creating spatial index and serializing ${features.size} features")
                // Create spatial index and serialize features with byte offsets
                val (spatialIndex, data) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(features)

                Log.d(TAG, "Serialization complete, data size: ${data.size} bytes")

                // Save FlexBuffer data
                val flexCacheFile = File(cacheDir, "${countryCode}_airspace.flex")
                Log.d(TAG, "Writing FlexBuffer data to: ${flexCacheFile.absolutePath}")
                flexCacheFile.writeBytes(data)
                Log.d(TAG, "FlexBuffer file written successfully, size: ${flexCacheFile.length()}")

                // Save spatial index
                val indexFile = File(cacheDir, "${countryCode}_airspace.idx")
                Log.d(TAG, "Writing spatial index to: ${indexFile.absolutePath}")
                val indexData = objectMapper.writeValueAsBytes(spatialIndex)
                indexFile.writeBytes(indexData)
                Log.d(TAG, "Spatial index file written successfully, size: ${indexFile.length()}")

                // Cache spatial index in memory
                spatialIndexCache[countryCode] = spatialIndex

                // Update cache index
                cacheIndex[countryCode] = System.currentTimeMillis()
                saveCacheIndex()

                // Remove old NDGeoJSON file if it exists
                val oldCacheFile = File(cacheDir, "${countryCode}_airspace.ndgeojson")
                if (oldCacheFile.exists()) {
                    oldCacheFile.delete()
                    Log.d(TAG, "Removed old NDGeoJSON cache file for $countryCode")
                }

                Log.d(TAG, "✅ Successfully cached ${features.size} airspace features for $countryCode")
            } else {
                Log.w(TAG, "No valid airspace features found for $countryCode after filtering")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error caching data for $countryCode", e)
        } finally {
            // Clear the download flag
            downloadInProgress.remove(countryCode)
            Log.d(TAG, "Cleared download flag for $countryCode")
        }
    }

    /**
     * Query nearby airspace features using Hilbert spatial index with memory-mapped I/O
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
                    feature["properties"] as? Map<String, Any> ?: emptyMap()
                    var latitude = centroidData?.get("latitude") as? Double ?: featureData["lat"] as? Double
                    var longitude = centroidData?.get("longitude") as? Double ?: featureData["lon"] as? Double
                    var hilbertIndex = featureData["hilbertIndex"] as? Long ?: featureData["hilbert"] as? Long
                    val overlayType = featureData["overlayType"] as? String ?: "airspace"

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
            val indexFile = File(cacheDir, "${countryCode}_airspace.idx")
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

        // Create memory-mapped buffer with proper resource management
        return try {
            val dataFile = File(cacheDir, "${countryCode}_airspace.flex")
            if (!dataFile.exists()) return null

            // Use proper resource management - RandomAccessFile and FileChannel will be auto-closed
            RandomAccessFile(dataFile, "r").use { randomAccessFile ->
                randomAccessFile.channel.use { fileChannel ->
                    val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, dataFile.length())

                    // Cache the memory-mapped buffer for fast access
                    memoryMappedBuffers[countryCode] = buffer

                    Log.v(TAG, "✅ Created memory-mapped buffer for airspaces $countryCode (${dataFile.length()} bytes)")
                    buffer
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating memory-mapped buffer for $countryCode", e)
            null
        }
    }

    /**
     * Clear all cached airspace data
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.endsWith("_airspace.flex") || file.name.endsWith("_airspace.idx")) {
                    file.delete()
                }
            }
            cacheIndex.clear()
            spatialIndexCache.clear()
            memoryMappedBuffers.clear()
            saveCacheIndex()
            Log.d(TAG, "Cleared all airspace cache (FlexBuffers and indices)")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    /**
     * Clear cached data for a specific country
     * @param countryCode Two-letter country code
     */
    fun clearCacheForCountry(countryCode: String) {
        try {
            val flexFile = File(cacheDir, "${countryCode}_airspace.flex")
            if (flexFile.exists()) {
                flexFile.delete()
            }
            val indexFile = File(cacheDir, "${countryCode}_airspace.idx")
            if (indexFile.exists()) {
                indexFile.delete()
            }
            // Also clean up any remaining old NDGeoJSON files
            val ndgeoFile = File(cacheDir, "${countryCode}_airspace.ndgeojson")
            if (ndgeoFile.exists()) {
                ndgeoFile.delete()
            }
            cacheIndex.remove(countryCode)
            spatialIndexCache.remove(countryCode)
            memoryMappedBuffers.remove(countryCode)
            saveCacheIndex()
            Log.d(TAG, "Cleared cache for $countryCode (FlexBuffers and indices)")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for $countryCode", e)
        }
    }

    /**
     * Get cache statistics
     * @return Map with cache statistics
     */
    fun getCacheStats(): Map<String, Any> {
        val flexFiles = cacheDir.listFiles()?.filter { it.name.endsWith("_airspace.flex") } ?: emptyList()
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
