package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import org.osmdroid.util.GeoPoint
import java.io.*
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic Spatial Disk Cache using FlexBuffers + Hilbert Spatial Indexing.
 *
 * This class encapsulates the core "Aviation-grade" caching logic used across the application:
 * - Binary storage (simulating FlexBuffers)
 * - Hilbert curve spatial indexing
 * - Memory-mapped I/O for zero-copy performance
 * - Cache integrity validation
 *
 * @param context Application context
 * @param cacheName Unique name for this cache (e.g., "pgspot", "airspace")
 * @param expirationHours Cache validity period in hours
 */
class SpatialDiskCache(
    context: Context,
    private val cacheName: String,
    private val expirationHours: Int
) {

    companion object {
        private const val TAG = "SpatialDiskCache"
    }

    private val cacheDir: File = File(context.cacheDir, "${cacheName}_cache")
    private val cacheIndexFile = File(cacheDir, "cache_index")
    internal val cacheIndex = ConcurrentHashMap<String, Long>() // regionId -> timestamp
    private val spatialIndexCache = ConcurrentHashMap<String, MapOverlayCacheUtils.SpatialIndex>() // regionId -> spatial index
    private val memoryMappedBuffers = ConcurrentHashMap<String, MappedByteBuffer>() // regionId -> memory mapped buffer
    
    private val objectMapper = ObjectMapper()

    init {
        cacheDir.mkdirs()
        loadCacheIndex()
    }

    /**
     * Check if data for a region is cached and fresh
     */
    fun isCached(regionId: String): Boolean {
        val timestamp = cacheIndex[regionId] ?: return false
        val ageHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60)
        val isFresh = ageHours < expirationHours

        if (!isFresh) {
            Log.d(TAG, "Cache stale for $cacheName/$regionId (${ageHours}h old, max ${expirationHours}h)")
            return false
        }

        return validateCacheIntegrity(regionId)
    }

    /**
     * Validate cache integrity for a region
     */
    private fun validateCacheIntegrity(regionId: String): Boolean {
        val cacheFile = File(cacheDir, "${regionId}_$cacheName.flex")
        val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")

        if (!cacheFile.exists() || !indexFile.exists()) {
            return false
        }

        if (!cacheFile.canRead() || !indexFile.canRead()) {
            Log.w(TAG, "Cache files not readable for $cacheName/$regionId")
            return false
        }

        if (cacheFile.length() < 100 || indexFile.length() < 50) {
            Log.w(TAG, "Cache files too small for $cacheName/$regionId")
            return false
        }

        // Try to load spatial index to verify it's not corrupted
        try {
            val indexData = indexFile.readBytes()
            val indexJson = String(indexData, Charsets.UTF_8)
            val spatialIndex = objectMapper.readValue(indexJson, MapOverlayCacheUtils.SpatialIndex::class.java)

            if (spatialIndex.bits <= 0 || spatialIndex.entries.isEmpty()) {
                Log.w(TAG, "Spatial index corrupted for $cacheName/$regionId")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error validating cache integrity for $cacheName/$regionId: ${e.message}")
            return false
        }
    }

    /**
     * Cache features for a region
     */
    fun cacheFeatures(regionId: String, features: List<OverlayFeature>) {
        try {
            if (features.isEmpty()) {
                Log.w(TAG, "No features to cache for $cacheName/$regionId")
                return
            }

            // Create Hilbert spatial index + FlexBuffers data
            val (spatialIndex, flexBuffersData) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(features)

            // Save binary FlexBuffers data
            val flexCacheFile = File(cacheDir, "${regionId}_$cacheName.flex")
            flexCacheFile.writeBytes(flexBuffersData)

            // Save spatial index metadata
            val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")
            val indexData = objectMapper.writeValueAsBytes(spatialIndex)
            indexFile.writeBytes(indexData)

            // Update in-memory caches
            spatialIndexCache[regionId] = spatialIndex
            
            // Re-create memory map
            memoryMappedBuffers.remove(regionId) // Clear old buffer
            createMemoryMappedBuffer(regionId, flexCacheFile)

            // Update timestamp
            cacheIndex[regionId] = System.currentTimeMillis()
            saveCacheIndex()

            Log.d(TAG, "Cached ${features.size} features for $cacheName/$regionId")

        } catch (e: Exception) {
            Log.e(TAG, "Error caching features for $cacheName/$regionId", e)
        }
    }

    /**
     * Get all cached features for a region (without spatial filtering)
     */
    fun getCachedFeatures(regionId: String): List<OverlayFeature>? {
        return try {
            val cacheFile = File(cacheDir, "${regionId}_$cacheName.flex")
            if (cacheFile.exists()) {
                val data = cacheFile.readBytes()
                val features = MapOverlayCacheUtils.deserializeFlexBuffersToFeatures(data)
                
                // Update timestamp if valid
                if (features.isNotEmpty() && !isCached(regionId)) {
                    cacheIndex[regionId] = System.currentTimeMillis()
                    saveCacheIndex()
                }
                features
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached features for $cacheName/$regionId", e)
            null
        }
    }

    /**
     * Query nearby features using Hilbert spatial indexing
     */
    fun queryNearby(regionId: String, center: GeoPoint, maxDistanceMiles: Double): List<OverlayFeature> {
        try {
            // Verify cached data exists
            if (!isCached(regionId)) {
                return emptyList()
            }

            val spatialIndex = getSpatialIndex(regionId) ?: return emptyList()
            val mappedBuffer = getMemoryMappedBuffer(regionId) ?: return emptyList()

            // Compute Hilbert center index
            val centerIndex = MapOverlayCacheUtils.computeHilbertIndex(center, spatialIndex.bits)

            // Calculate range
            val maxDistanceMeters = maxDistanceMiles * 1609.34
            val range = (maxDistanceMeters / 1000.0 * 1000.0).toLong().coerceAtLeast(1000)

            // Find relevant indices
            val relevantEntries = spatialIndex.findNearbyIndices(centerIndex, range)

            // Deserialize relevant features
            val nearbyFeatures = relevantEntries.mapNotNull { entry ->
                try {
                    val featureBytes = ByteArray(entry.byteLength)
                    synchronized(mappedBuffer) {
                        mappedBuffer.position(entry.byteOffset)
                        mappedBuffer.get(featureBytes, 0, entry.byteLength)
                    }

                    val featureJson = String(featureBytes, Charsets.UTF_8)
                    val featureData: Map<String, Any> = objectMapper.readValue(featureJson, object : TypeReference<Map<String, Any>>() {})

                    // Extract feature info (handling both formats for backward compatibility)
                    @Suppress("UNCHECKED_CAST")
                    val feature = featureData["feature"] as? Map<String, Any> ?: featureData
                    @Suppress("UNCHECKED_CAST")
                    val centroidData = featureData["centroid"] as? Map<String, Any>
                    val latitude = centroidData?.get("latitude") as? Double ?: featureData["lat"] as? Double
                    val longitude = centroidData?.get("longitude") as? Double ?: featureData["lon"] as? Double
                    val hilbertIndex = entry.hilbertIndex
                    val overlayType = featureData["overlayType"] as? String ?: cacheName

                    if (latitude != null && longitude != null) {
                        val centroid = GeoPoint(latitude, longitude)
                        val overlayFeature = OverlayFeature(feature, centroid, hilbertIndex, overlayType)

                        // Final distance validation
                        if (center.distanceToAsDouble(centroid) <= maxDistanceMeters) overlayFeature else null
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading feature at offset ${entry.byteOffset}: ${e.message}")
                    null
                }
            }

            return nearbyFeatures

        } catch (e: Exception) {
            Log.e(TAG, "Error querying nearby features for $cacheName/$regionId", e)
            return emptyList()
        }
    }

    /**
     * Clear cache for a specific region
     */
    fun clearCacheForRegion(regionId: String) {
        try {
            val cacheFile = File(cacheDir, "${regionId}_$cacheName.flex")
            val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")
            
            if (cacheFile.exists()) cacheFile.delete()
            if (indexFile.exists()) indexFile.delete()
            
            // Also clean up potential NDGeoJSON files (legacy)
            val ndgeoFile = File(cacheDir, "${regionId}_$cacheName.ndgeojson")
            if (ndgeoFile.exists()) ndgeoFile.delete()

            cacheIndex.remove(regionId)
            spatialIndexCache.remove(regionId)
            memoryMappedBuffers.remove(regionId)
            saveCacheIndex()
            
            Log.d(TAG, "Cleared cache for $cacheName/$regionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for $cacheName/$regionId", e)
        }
    }

    /**
     * Clear all cache
     */
    fun clearAll() {
        try {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            cacheIndex.clear()
            spatialIndexCache.clear()
            memoryMappedBuffers.clear()
            saveCacheIndex()
            Log.d(TAG, "Cleared all $cacheName cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all $cacheName cache", e)
        }
    }
    
    /**
     * Get cache statistics
     */
    fun getStats(): Map<String, Any> {
        val flexFiles = cacheDir.listFiles()?.filter { it.name.endsWith("_$cacheName.flex") } ?: emptyList()
        val totalSize = flexFiles.sumOf { it.length() }
        
        return mapOf(
            "cacheName" to cacheName,
            "totalFiles" to flexFiles.size,
            "totalSizeBytes" to totalSize,
            "totalSizeMB" to String.format("%.2f", totalSize / (1024.0 * 1024.0)),
            "cachedRegions" to cacheIndex.keys.toList()
        )
    }

    // ================= PRIVATE HELPERS =================

    private fun getSpatialIndex(regionId: String): MapOverlayCacheUtils.SpatialIndex? {
        spatialIndexCache[regionId]?.let { return it }

        return try {
            val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")
            if (indexFile.exists()) {
                val indexData = indexFile.readBytes()
                val indexJson = String(indexData, Charsets.UTF_8)
                val spatialIndex = objectMapper.readValue(indexJson, MapOverlayCacheUtils.SpatialIndex::class.java)
                spatialIndexCache[regionId] = spatialIndex
                spatialIndex
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading spatial index for $cacheName/$regionId", e)
            null
        }
    }

    private fun getMemoryMappedBuffer(regionId: String): MappedByteBuffer? {
        memoryMappedBuffers[regionId]?.let { return it }

        return try {
            val dataFile = File(cacheDir, "${regionId}_$cacheName.flex")
            if (!dataFile.exists()) return null
            createMemoryMappedBuffer(regionId, dataFile)
            memoryMappedBuffers[regionId]
        } catch (e: Exception) {
            Log.e(TAG, "Error getting memory mapped buffer for $cacheName/$regionId", e)
            null
        }
    }

    private fun createMemoryMappedBuffer(regionId: String, file: File) {
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.channel.use { channel ->
                    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    memoryMappedBuffers[regionId] = buffer
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating memory map for $cacheName/$regionId", e)
        }
    }

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
            cacheIndex.clear()
        }
    }

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
