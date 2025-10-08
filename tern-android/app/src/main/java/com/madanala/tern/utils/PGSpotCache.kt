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

    // Cache validity (longer than weather, shorter than airspaces)
    companion object {
        const val PG_SPOT_CACHE_HOURS = 168  // 7 days - PG spots don't change often
        private const val TAG = "PGSpotCache"
    }

    private val cacheDir: File = File(context.cacheDir, "pg_spot_cache")
    private val cacheIndexFile = File(cacheDir, "cache_index")
    private val cacheIndex = ConcurrentHashMap<String, Long>() // countryCode -> timestamp
    private val spatialIndexCache = ConcurrentHashMap<String, MapOverlayCacheUtils.SpatialIndex>() // countryCode -> spatial index
    private val memoryMappedBuffers = ConcurrentHashMap<String, MappedByteBuffer>() // countryCode -> memory mapped buffer
    private val downloadInProgress = ConcurrentHashMap<String, Boolean>() // countryCode -> download flag

    private val objectMapper = ObjectMapper()

    init {
        cacheDir.mkdirs()
        loadCacheIndex()
    }

    /**
     * Check if cached PG spot data exists and is fresh for country
     */
    fun isCached(countryCode: String): Boolean {
        val timestamp = cacheIndex[countryCode] ?: return false
        val ageHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60)
        val isFresh = ageHours < PG_SPOT_CACHE_HOURS

        if (!isFresh) {
            Log.d(TAG, "PG spots cache stale for $countryCode (${ageHours}h old, max ${PG_SPOT_CACHE_HOURS}h)")
            return false
        }

        // Validate cache integrity
        return validateCacheIntegrity(countryCode)
    }

    /**
     * Validate cache integrity for a country
     */
    private fun validateCacheIntegrity(countryCode: String): Boolean {
        val cacheFile = File(cacheDir, "${countryCode}_pgspots.flex")
        val indexFile = File(cacheDir, "${countryCode}_pgspots.idx")

        // Check if both files exist
        val filesExist = cacheFile.exists() && indexFile.exists()
        if (!filesExist) {
            Log.d(TAG, "PG spots cache files missing for $countryCode")
            return false
        }

        // Check if files are readable
        val filesReadable = cacheFile.canRead() && indexFile.canRead()
        if (!filesReadable) {
            Log.w(TAG, "PG spots cache files not readable for $countryCode")
            return false
        }

        // Check if files have reasonable sizes (not empty or corrupted)
        val cacheFileSize = cacheFile.length()
        val indexFileSize = indexFile.length()

        if (cacheFileSize < 100) { // Less than 100 bytes is likely corrupted
            Log.w(TAG, "PG spots cache file too small for $countryCode (${cacheFileSize} bytes)")
            return false
        }

        if (indexFileSize < 50) { // Less than 50 bytes is likely corrupted
            Log.w(TAG, "PG spots index file too small for $countryCode (${indexFileSize} bytes)")
            return false
        }

        // Try to load spatial index to verify it's not corrupted
        try {
            val indexData = indexFile.readBytes()
            val indexJson = String(indexData, Charsets.UTF_8)
            val spatialIndex = objectMapper.readValue(indexJson, MapOverlayCacheUtils.SpatialIndex::class.java)

            if (spatialIndex.bits <= 0 || spatialIndex.entries.isEmpty()) {
                Log.w(TAG, "PG spots spatial index corrupted for $countryCode")
                return false
            }

            return true

        } catch (e: Exception) {
            Log.w(TAG, "Error validating PG spots cache integrity for $countryCode: ${e.message}")
            return false
        }
    }

    /**
     * Get fully loaded cached PG spots for a country
     * Returns all features (use queryNearbyPGSpots for spatial filtering)
     */
    fun getCachedPGSpots(countryCode: String): List<OverlayFeature>? {
        return try {
            val cacheFile = File(cacheDir, "${countryCode}_pgspots.flex")
            if (cacheFile.exists()) {
                val data = cacheFile.readBytes()
                val features = MapOverlayCacheUtils.deserializeFlexBuffersToFeatures(data)

                // Update cache index if file exists (transition handling)
                if (!isCached(countryCode)) {
                    cacheIndex[countryCode] = System.currentTimeMillis()
                    saveCacheIndex()
                    Log.d(TAG, "Updated cache index for existing PG spots: $countryCode")
                }

                Log.d(TAG, "Loaded ${features.size} cached PG spots for $countryCode")
                features
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached PG spots for $countryCode", e)
            null
        }
    }

    /**
     * Cache PG spots data from standard GeoJSON API
     * Downloads from paraglidingearth.com, caches as FlexBuffers + Hilbert
     */
    suspend fun cachePGSpotsData(countryCode: String): List<OverlayFeature>? {
        // Use atomic operation to prevent race conditions
        val isAlreadyDownloading = downloadInProgress.putIfAbsent(countryCode, true) != null

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

                // Parse standard GeoJSON to features
                val features = MapOverlayCacheUtils.parseGeoJsonToFeatures(geoJsonString, "pgspot")
                Log.d(TAG, "Parsed ${features.size} PG spots for $countryCode")

                // VALIDATE: Check that parsed features are valid before caching
                val validFeatures = features.filter { feature ->
                    validateOverlayFeature(feature, countryCode)
                }

                if (validFeatures.isNotEmpty()) {
                    Log.d(TAG, "Validated ${validFeatures.size}/${features.size} PG spot features for $countryCode")

                    // Cache as FlexBuffers + Hilbert spatial indexing
                    cachePGSpotsFeatures(countryCode, validFeatures)

                    Log.d(TAG, "Successfully cached ${validFeatures.size} PG spots for $countryCode")
                    return validFeatures
                } else {
                    Log.w(TAG, "No valid PG spots found for $countryCode after validation")
                    // Clean up any partial cache files
                    clearCacheForCountry(countryCode)
                }
            } else {
                Log.w(TAG, "PG spots download returned null/empty for $countryCode - API may not have data for this country")
            }

            return null

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "PG spots download cancelled for $countryCode")
            // Clean up any partial cache on cancellation
            clearCacheForCountry(countryCode)
            // Re-throw cancellation to propagate properly
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error caching PG spots data for $countryCode: ${e.message}", e)
            // Clean up any partial cache on error
            clearCacheForCountry(countryCode)
            return null
        } finally {
            // Always clear the download flag
            downloadInProgress.remove(countryCode)
        }
    }

    /**
     * Query nearby PG spots using Hilbert spatial indexing
     * Zero-copy performance with memory-mapped I/O
     */
    fun queryNearbyPGSpots(countryCode: String, center: GeoPoint, maxDistanceMiles: Double): List<OverlayFeature> {
        try {
            // Verify cached data exists
            if (!isCached(countryCode)) {
                Log.d(TAG, "No cached PG spots for $countryCode, need to download first")
                return emptyList()
            }

            // Get spatial index for Hilbert lookups
            val spatialIndex = getSpatialIndex(countryCode) ?: run {
                Log.w(TAG, "No spatial index for PG spots $countryCode")
                return emptyList()
            }

            // Memory-mapped I/O for zero-copy performance
            val mappedBuffer = getMemoryMappedBuffer(countryCode) ?: run {
                Log.w(TAG, "No memory-mapped buffer for PG spots $countryCode")
                return emptyList()
            }

            // Compute Hilbert center index for spatial query
            val centerIndex = MapOverlayCacheUtils.computeHilbertIndex(center, spatialIndex.bits)

            // Calculate range based on distance (larger area = larger Hilbert range)
            val maxDistanceMeters = maxDistanceMiles * 1609.34
            val range = (maxDistanceMeters / 1000.0 * 1000.0).toLong().coerceAtLeast(1000)

            // Spatial query: find relevant Hilbert index entries
            val relevantEntries = spatialIndex.findNearbyIndices(centerIndex, range)

            // Deserialize and filter features using memory-mapped buffer
            val nearbySpots = relevantEntries.mapNotNull { entry ->
                try {
                    // Zero-copy I/O: direct read from memory-mapped file
                    val featureBytes = ByteArray(entry.byteLength)
                    synchronized(mappedBuffer) {
                        mappedBuffer.position(entry.byteOffset)
                        mappedBuffer.get(featureBytes, 0, entry.byteLength)
                    }

                    val featureJson = String(featureBytes, Charsets.UTF_8)
                    val featureData: Map<String, Any> = objectMapper.readValue(featureJson, object : TypeReference<Map<String, Any>>() {})

                    // Extract feature info (aviation resilience - handle missing data)
                    @Suppress("UNCHECKED_CAST")
                    val feature = featureData["feature"] as? Map<String, Any> ?: featureData
                    @Suppress("UNCHECKED_CAST")
                    var centroidData = featureData["centroid"] as? Map<String, Any>
                    var latitude = centroidData?.get("latitude") as? Double ?: featureData["lat"] as? Double
                    var longitude = centroidData?.get("longitude") as? Double ?: featureData["lon"] as? Double
                    // Use hilbert index from spatial index entry (not from feature JSON)
                    val hilbertIndex = entry.hilbertIndex
                    val overlayType = featureData["overlayType"] as? String ?: "pgspot"

                    // Build OverlayFeature with proper validation
                    if (latitude != null && longitude != null) {
                        val centroid = GeoPoint(latitude, longitude)
                        val overlayFeature = OverlayFeature(feature, centroid, hilbertIndex, overlayType)

                        // Final distance validation (Hilbert is approximate)
                        val distance = center.distanceToAsDouble(centroid)
                        if (distance <= maxDistanceMeters) overlayFeature else null
                    } else {
                        Log.w(TAG, "Invalid PG spot data: lat=$latitude, lon=$longitude")
                        null
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Error reading PG spot feature at offset ${entry.byteOffset}: ${e.message}", e)
                    null
                }
            }

            Log.d(TAG, "🎯 Queried ${nearbySpots.size} PG spots within $maxDistanceMiles miles of $center")
            return nearbySpots

        } catch (e: Exception) {
            Log.e(TAG, "Error querying near PG spots for $countryCode", e)
            return emptyList()
        }
    }

    /**
     * CACHE FEATURES AS FLEXBUFFERS + HILBERT SPATIAL INDEXING
     * Core aviation architecture: All map data uses this format for performance
     */
    private fun cachePGSpotsFeatures(countryCode: String, features: List<OverlayFeature>) {
        try {
            if (features.isEmpty()) {
                Log.w(TAG, "No PG spot features to cache for $countryCode")
                return
            }

            // 🗂️ STEP 1: CREATE HILBERT SPATIAL INDEX + FLEXBUFFERS DATA
            val (spatialIndex, flexBuffersData) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(features)

            // 💾 STEP 2: SAVE BINARY FLEXBUFFERS DATA
            val flexCacheFile = File(cacheDir, "${countryCode}_pgspots.flex")
            flexCacheFile.writeBytes(flexBuffersData)

            // 🔍 STEP 3: SAVE SPATIAL INDEX METADATA
            val indexFile = File(cacheDir, "${countryCode}_pgspots.idx")
            val indexData = objectMapper.writeValueAsBytes(spatialIndex)
            indexFile.writeBytes(indexData)

            // 🧠 STEP 4: CACHE SPATIAL INDEX IN MEMORY
            spatialIndexCache[countryCode] = spatialIndex

            // 🔧 STEP 5: MEMORY-MAP FILE FOR ZERO-COPY I/O
            try {
                createMemoryMappedBuffer(countryCode, flexCacheFile)
                Log.v(TAG, "✅ Memory-mapped PG spots file for $countryCode (${flexCacheFile.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to memory-map PG spots for $countryCode", e)
            }

            // ⏰ STEP 6: UPDATE CACHE VALIDITY TIMESTAMP
            cacheIndex[countryCode] = System.currentTimeMillis()
            saveCacheIndex()

            Log.d(TAG, "Cached ${features.size} PG spot features for $countryCode")

        } catch (e: Exception) {
            Log.e(TAG, "Error caching PG spot features for $countryCode", e)
        }
    }

    /**
     * Get spatial index for country (loads from disk if needed)
     */
    private fun getSpatialIndex(countryCode: String): MapOverlayCacheUtils.SpatialIndex? {
        // Memory cache first
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
            Log.e(TAG, "Error loading spatial index for PG spots $countryCode", e)
            null
        }
    }

    /**
     * Get memory-mapped buffer for country (creates if needed)
     */
    private fun getMemoryMappedBuffer(countryCode: String): MappedByteBuffer? {
        // Memory cache first
        memoryMappedBuffers[countryCode]?.let { return it }

        // Create memory-mapped buffer with proper resource management
        return try {
            val dataFile = File(cacheDir, "${countryCode}_pgspots.flex")
            if (!dataFile.exists()) return null

            // Use proper resource management - RandomAccessFile and FileChannel will be auto-closed
            RandomAccessFile(dataFile, "r").use { randomAccessFile ->
                randomAccessFile.channel.use { fileChannel ->
                    val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, dataFile.length())

                    // Cache the memory-mapped buffer for fast access
                    memoryMappedBuffers[countryCode] = buffer

                    Log.v(TAG, "✅ Created memory-mapped buffer for PG spots $countryCode (${dataFile.length()} bytes)")
                    buffer
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating memory-mapped buffer for PG spots $countryCode", e)
            null
        }
    }

    /**
     * Clear cache for a specific country
     */
    private fun clearCacheForCountry(countryCode: String) {
        try {
            val cacheFile = File(cacheDir, "${countryCode}_pgspots.flex")
            val indexFile = File(cacheDir, "${countryCode}_pgspots.idx")

            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d(TAG, "Deleted cache file for $countryCode")
            }
            if (indexFile.exists()) {
                indexFile.delete()
                Log.d(TAG, "Deleted index file for $countryCode")
            }

            // Clear from memory caches
            cacheIndex.remove(countryCode)
            spatialIndexCache.remove(countryCode)
            memoryMappedBuffers.remove(countryCode)

            saveCacheIndex()
            Log.d(TAG, "Cleared cache for country: $countryCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for country $countryCode", e)
        }
    }


    /**
     * Validate that an OverlayFeature has valid data
     */
    private fun validateOverlayFeature(feature: OverlayFeature, countryCode: String): Boolean {
        try {
            // Check if centroid is valid
            val centroid = feature.centroid
            if (centroid.latitude < -90.0 || centroid.latitude > 90.0 ||
                centroid.longitude < -180.0 || centroid.longitude > 180.0) {
                Log.w(TAG, "Invalid centroid coordinates for PG spot in $countryCode: ${centroid.latitude}, ${centroid.longitude}")
                return false
            }

            // Check if feature data exists
            if (feature.feature.isEmpty()) {
                Log.w(TAG, "Empty feature data for PG spot in $countryCode")
                return false
            }

            // Check if feature has required geometry
            val geometry = feature.feature["geometry"]
            if (geometry == null || geometry !is Map<*, *>) {
                Log.w(TAG, "Missing or invalid geometry for PG spot in $countryCode")
                return false
            }

            // For Point geometries (PG spots), ensure coordinates exist
            val geometryType = geometry["type"] as? String
            if (geometryType == "Point") {
                val coordinates = geometry["coordinates"] as? List<*>
                if (coordinates == null || coordinates.size < 2) {
                    Log.w(TAG, "Missing coordinates for Point geometry PG spot in $countryCode")
                    return false
                }
            }

            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error validating PG spot feature in $countryCode: ${e.message}")
            return false
        }
    }

    /**
     * Clean up corrupted cache files and memory references
     */
    fun cleanupCorruptedCache() {
        try {
            var cleanedCount = 0
            cacheIndex.keys.toList().forEach { countryCode ->
                if (!validateCacheIntegrity(countryCode)) {
                    Log.w(TAG, "Cleaning up corrupted cache for $countryCode")
                    clearCacheForCountry(countryCode)
                    cleanedCount++
                }
            }

            if (cleanedCount > 0) {
                Log.d(TAG, "Cleaned up $cleanedCount corrupted cache entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during corrupted cache cleanup", e)
        }
    }

    /**
     * Clear all cached PG spots data
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.contains("_pgspots")) {
                    file.delete()
                }
            }
            cacheIndex.clear()
            spatialIndexCache.clear()
            memoryMappedBuffers.clear()
            saveCacheIndex()
            Log.d(TAG, "Cleared all PG spots cache (FlexBuffers and indices)")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing PG spots cache", e)
        }
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): Map<String, Any> {
        val flexFiles = cacheDir.listFiles()?.filter { it.name.endsWith("_pgspots.flex") } ?: emptyList()
        val totalSize = flexFiles.sumOf { it.length() }
        val fileCount = flexFiles.size

        return mapOf(
            "pgSpotsTotalFiles" to fileCount,
            "pgSpotsTotalSizeBytes" to totalSize,
            "pgSpotsTotalSizeMB" to String.format("%.2f", totalSize / (1024.0 * 1024.0)),
            "pgSpotsCountries" to cacheIndex.keys.toList()
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
            Log.e(TAG, "Error loading PG spots cache index", e)
            cacheIndex.clear()
        }
    }

    /**
     * Create memory-mapped buffer for PG spots data
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
            Log.e(TAG, "Error creating memory-mapped buffer for PG spots $countryCode", e)
            throw e
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
            Log.e(TAG, "Error saving PG spots cache index", e)
        }
    }
}
