@file:Suppress("UNCHECKED_CAST", "SENSELESS_COMPARISON")
package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import com.madanala.tern.utils.GeoJsonUtils
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
    internal val cacheIndex = ConcurrentHashMap<String, Long>() // countryCode -> timestamp
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
        val isFresh = ageHours < maxAgeHours

        if (!isFresh) {
            Log.d(TAG, "Airspace cache stale for $countryCode (${ageHours}h old, max ${maxAgeHours}h)")
            return false
        }

        // Validate cache integrity (same as PGSpotCache)
        return validateCacheIntegrity(countryCode)
    }

    /**
     * Validate cache integrity for a country (same as PGSpotCache)
     */
    private fun validateCacheIntegrity(countryCode: String): Boolean {
        val cacheFile = File(cacheDir, "${countryCode}_airspace.flex")
        val indexFile = File(cacheDir, "${countryCode}_airspace.idx")

        // Check if both files exist
        val filesExist = cacheFile.exists() && indexFile.exists()
        if (!filesExist) {
            Log.d(TAG, "Airspace cache files missing for $countryCode")
            return false
        }

        // Check if files are readable
        val filesReadable = cacheFile.canRead() && indexFile.canRead()
        if (!filesReadable) {
            Log.w(TAG, "Airspace cache files not readable for $countryCode")
            return false
        }

        // Check if files have reasonable sizes (not empty or corrupted)
        val cacheFileSize = cacheFile.length()
        val indexFileSize = indexFile.length()

        if (cacheFileSize < 100) { // Less than 100 bytes is likely corrupted
            Log.w(TAG, "Airspace cache file too small for $countryCode (${cacheFileSize} bytes)")
            return false
        }

        if (indexFileSize < 50) { // Less than 50 bytes is likely corrupted
            Log.w(TAG, "Airspace index file too small for $countryCode (${indexFileSize} bytes)")
            return false
        }

        // Try to load spatial index to verify it's not corrupted
        try {
            val indexData = indexFile.readBytes()
            val indexJson = String(indexData, Charsets.UTF_8)
            val spatialIndex = objectMapper.readValue(indexJson, MapOverlayCacheUtils.SpatialIndex::class.java)

            if (spatialIndex.bits <= 0 || spatialIndex.entries.isEmpty()) {
                Log.w(TAG, "Airspace spatial index corrupted for $countryCode")
                return false
            }

            return true

        } catch (e: Exception) {
            Log.w(TAG, "Error validating airspace cache integrity for $countryCode: ${e.message}")
            return false
        }
    }

    /**
     * Get cached airspace features for a country
     * @param countryCode Two-letter country code
     * @return List of OverlayFeature or null if not found
     */
    fun getCachedFeatures(countryCode: String): List<OverlayFeature>? {
        return try {
            val cacheFile = File(cacheDir, "${countryCode}_airspace.flex")
            if (cacheFile.exists()) {
                val data = cacheFile.readBytes()
                val features = MapOverlayCacheUtils.deserializeFlexBuffersToFeatures(data)

                if (features != null) {
                    // Update cache index if file exists (handles transition from old cache validity)
                    if (!isCached(countryCode)) {
                        cacheIndex[countryCode] = System.currentTimeMillis()
                        saveCacheIndex()
                    }
                    features
                } else {
                    Log.w(TAG, "Failed to deserialize cached airspace data for $countryCode")
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached airspace features for $countryCode", e)
            null
        }
    }

    /**
     * Download and cache airspace data for a country
     * @param countryCode Two-letter country code
     * @return true if successful, false otherwise
     */
    suspend fun downloadAndCache(countryCode: String): Boolean {
        if (isCached(countryCode)) {
            Log.d(TAG, "Airspaces already cached for $countryCode")
            return true
        }

        return try {
            // Use the official OpenAIP Google Storage bucket
            val airspaceUrl = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/${countryCode.lowercase()}_asp.geojson"
            Log.d(TAG, "Downloading airspace data for $countryCode from $airspaceUrl")

            val airspaceData = GeoJsonUtils.downloadGeoJson(airspaceUrl)
            if (airspaceData != null) {
                cacheData(countryCode, airspaceData)
                true
            } else {
                Log.w(TAG, "Failed to download airspace data for $countryCode")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading airspace data for $countryCode", e)
            false
        }
    }

    /**
     * Cache airspace data for a country using PGSpotCache pattern
     * @param countryCode Two-letter country code
     * @param ndGeoJsonString The NDGeoJSON data to cache
     */
    fun cacheData(countryCode: String, ndGeoJsonString: String) {
        // Use atomic operation to prevent race conditions (same as PGSpotCache)
        val isAlreadyDownloading = downloadInProgress.putIfAbsent(countryCode, true) != null

        if (isAlreadyDownloading) {
            Log.d(TAG, "Caching already in progress for $countryCode, skipping duplicate")
            return
        }

        try {
            // VALIDATE: Check downloaded content before processing (same as PGSpotCache)
            if (!validateGeoJsonContent(ndGeoJsonString, countryCode)) {
                Log.w(TAG, "Downloaded NDGeoJSON content failed validation for $countryCode")
                return
            }

            // Parse NDGeoJSON to features (PGSpotCache uses standard GeoJSON, we use NDGeoJSON)
            val features = MapOverlayCacheUtils.parseNdGeoJsonToFeatures(ndGeoJsonString)
            Log.d(TAG, "Parsed ${features.size} features for $countryCode")

            // VALIDATE: Check that parsed features are valid before caching (same as PGSpotCache)
            val validFeatures = features.filter { feature ->
                validateOverlayFeature(feature, countryCode)
            }

            if (validFeatures.isNotEmpty()) {
                Log.d(TAG, "Validated ${validFeatures.size}/${features.size} airspace features for $countryCode")

                // Cache as FlexBuffers + Hilbert spatial indexing (same as PGSpotCache)
                cacheAirspaceFeatures(countryCode, validFeatures)
                Log.d(TAG, "Successfully cached ${validFeatures.size} airspace features for $countryCode")
            } else {
                Log.w(TAG, "No valid airspace features found for $countryCode after validation")
                // Clean up any partial cache files
                clearCacheForCountry(countryCode)
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "Airspace caching cancelled for $countryCode")
            // Clean up any partial cache on cancellation
            clearCacheForCountry(countryCode)
            // Re-throw cancellation to propagate properly
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error caching airspace data for $countryCode: ${e.message}", e)
            // Clean up any partial cache on error
            clearCacheForCountry(countryCode)
        } finally {
            // Always clear the download flag
            downloadInProgress.remove(countryCode)
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
        // Production implementation only - no test hooks

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
                    // Safely extract nested maps and numeric fields
                    val rawFeature = featureData["feature"]
                    @Suppress("UNCHECKED_CAST")
                    val feature = when {
                        rawFeature is Map<*, *> -> rawFeature as Map<String, Any>
                        else -> featureData
                    }

                    val centroidRaw = featureData["centroid"]
                    var centroidData = if (centroidRaw is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        centroidRaw as Map<String, Any>
                    } else null

                    val overlayType = (featureData["overlayType"] as? String) ?: "airspace"

                    // Extract numeric latitude/longitude/hilbert safely from either centroid or top-level keys
                    val latCandidate = centroidData?.get("latitude") ?: featureData["lat"]
                    val lonCandidate = centroidData?.get("longitude") ?: featureData["lon"]
                    val hilbertCandidate = featureData["hilbertIndex"] ?: featureData["hilbert"]

                    var latitude = (latCandidate as? Number)?.toDouble()
                    var longitude = (lonCandidate as? Number)?.toDouble()
                    var hilbertIndex = when (hilbertCandidate) {
                        is Number -> hilbertCandidate.toLong()
                        is String -> hilbertCandidate.toLongOrNull()
                        else -> null
                    }

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
     * Validate that downloaded NDGeoJSON content is valid (same as PGSpotCache pattern)
     */
    private fun validateGeoJsonContent(content: String, countryCode: String): Boolean {
        if (content.isEmpty()) {
            Log.w(TAG, "NDGeoJSON content is empty for $countryCode")
            return false
        }

        // Check minimum size
        if (content.length < 50) {
            Log.w(TAG, "NDGeoJSON content too small (${content.length} bytes) for $countryCode")
            return false
        }

        // For NDGeoJSON, check if it has valid line-delimited JSON structure
        val lines = content.lines()
        if (lines.isEmpty()) {
            Log.w(TAG, "NDGeoJSON has no lines for $countryCode")
            return false
        }

        var validLines = 0
        var invalidLines = 0

        // Check first few lines and some samples
        val linesToCheck = minOf(20, lines.size) // Check up to 20 lines

        for (i in 0 until linesToCheck) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            if (isValidGeoJsonLine(line)) {
                validLines++
            } else {
                invalidLines++
            }
        }

        // Calculate validity ratio
        val totalChecked = validLines + invalidLines
        val validityRatio = if (totalChecked > 0) validLines.toDouble() / totalChecked else 0.0

        Log.d(TAG, "NDGeoJSON validation for $countryCode: $validLines valid, $invalidLines invalid (${String.format("%.1f%%", validityRatio * 100)})")

        // Require at least 70% valid lines for acceptance (NDGeoJSON can have some empty lines)
        return validityRatio >= 0.7
    }

    /**
     * Check if a single line is valid GeoJSON (same as PGSpotCache)
     */
    private fun isValidGeoJsonLine(line: String): Boolean {
        if (line.isEmpty()) return false

        return try {
            line.trim().startsWith("{") && objectMapper.readTree(line) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validate that an OverlayFeature has valid data (same as PGSpotCache)
     */
    private fun validateOverlayFeature(feature: OverlayFeature, countryCode: String): Boolean {
        try {
            // Check if centroid is valid
            val centroid = feature.centroid
            if (centroid.latitude < -90.0 || centroid.latitude > 90.0 ||
                centroid.longitude < -180.0 || centroid.longitude > 180.0) {
                Log.w(TAG, "Invalid centroid coordinates for airspace in $countryCode: ${centroid.latitude}, ${centroid.longitude}")
                return false
            }

            // Check if feature data exists
            if (feature.feature.isEmpty()) {
                Log.w(TAG, "Empty feature data for airspace in $countryCode")
                return false
            }

            // Check if feature has required geometry
            val geometry = feature.feature["geometry"]
            if (geometry == null || geometry !is Map<*, *>) {
                Log.w(TAG, "Missing or invalid geometry for airspace in $countryCode")
                return false
            }

            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error validating airspace feature in $countryCode: ${e.message}")
            return false
        }
    }

    /**
     * Cache airspace features as FlexBuffers + Hilbert spatial indexing (same as PGSpotCache)
     */
    private fun cacheAirspaceFeatures(countryCode: String, features: List<OverlayFeature>) {
        try {
            if (features.isEmpty()) {
                Log.w(TAG, "No airspace features to cache for $countryCode")
                return
            }

            // Create Hilbert spatial index + FlexBuffers data (same as PGSpotCache)
            val (spatialIndex, flexBuffersData) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(features)

            // Save binary FlexBuffers data
            val flexCacheFile = File(cacheDir, "${countryCode}_airspace.flex")
            flexCacheFile.writeBytes(flexBuffersData)

            // Save spatial index metadata
            val indexFile = File(cacheDir, "${countryCode}_airspace.idx")
            val indexData = objectMapper.writeValueAsBytes(spatialIndex)
            indexFile.writeBytes(indexData)

            // Cache spatial index in memory
            spatialIndexCache[countryCode] = spatialIndex

            // Memory-map file for zero-copy I/O
            try {
                createMemoryMappedBuffer(countryCode, flexCacheFile)
                Log.v(TAG, "✅ Memory-mapped airspace file for $countryCode (${flexCacheFile.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to memory-map airspace for $countryCode", e)
            }

            // Update cache validity timestamp
            cacheIndex[countryCode] = System.currentTimeMillis()
            saveCacheIndex()

            Log.d(TAG, "Cached ${features.size} airspace features for $countryCode")

        } catch (e: Exception) {
            Log.e(TAG, "Error caching airspace features for $countryCode", e)
        }
    }

    /**
     * Create memory-mapped buffer for airspace data (same as PGSpotCache)
     */
    private fun createMemoryMappedBuffer(countryCode: String, flexCacheFile: File) {
        try {
            RandomAccessFile(flexCacheFile, "r").use { randomAccessFile ->
                randomAccessFile.channel.use { fileChannel ->
                    val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, flexCacheFile.length())
                    memoryMappedBuffers[countryCode] = buffer
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating memory-mapped buffer for airspace $countryCode", e)
            throw e
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
