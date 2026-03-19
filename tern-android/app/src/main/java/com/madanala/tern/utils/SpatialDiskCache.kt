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
    
    // LRU Cache for memory mapped buffers to prevent OOM
    private val MAX_OPEN_BUFFERS = 5
    private val memoryMappedBuffers = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, MappedByteBuffer>(MAX_OPEN_BUFFERS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MappedByteBuffer>): Boolean {
                val remove = size > MAX_OPEN_BUFFERS
                if (remove) {
                    trackDeallocation("MappedBuffer:$cacheName", eldest.value.capacity().toLong())
                }
                return remove
            }
        }
    )
    
    private val objectMapper = ObjectMapper()

    init {
        cacheDir.mkdirs()
        loadCacheIndex()
    }

    // ... (existing code)

    private fun getSpatialIndex(regionIdRaw: String): MapOverlayCacheUtils.SpatialIndex? {
        val regionId = regionIdRaw.uppercase()
        spatialIndexCache[regionId]?.let { return it }

        return try {
            val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")
            if (indexFile.exists()) {
                val indexData = indexFile.readBytes()
                val indexJson = String(indexData, Charsets.UTF_8)
                val spatialIndex = objectMapper.readValue(indexJson, MapOverlayCacheUtils.SpatialIndex::class.java)
                spatialIndexCache[regionId] = spatialIndex
                trackAllocation("SpatialIndex:$cacheName", indexData.size.toLong())
                spatialIndex
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading spatial index for $cacheName/$regionId", e)
            null
        }
    }

    private fun createMemoryMappedBuffer(regionIdRaw: String, file: File) {
        val regionId = regionIdRaw.uppercase()
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.channel.use { channel ->
                    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    memoryMappedBuffers[regionId] = buffer
                    trackAllocation("MappedBuffer:$cacheName", file.length())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating memory map for $cacheName/$regionId", e)
        }
    }

    /**
     * Check if data for a region is cached and fresh
     */
    fun isCached(regionIdRaw: String): Boolean {
        val regionId = regionIdRaw.uppercase()
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
    private fun validateCacheIntegrity(regionIdRaw: String): Boolean {
        val regionId = regionIdRaw.uppercase()
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
    fun cacheFeatures(regionIdRaw: String, features: List<OverlayFeature>) {
        val regionId = regionIdRaw.uppercase()
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
     * Cache features for a region by streaming them directly from a source
     */
    suspend fun cacheFeaturesStream(regionIdRaw: String, streamAction: suspend (appendFeature: (OverlayFeature) -> Unit) -> Boolean): Boolean {
        val regionId = regionIdRaw.uppercase()
        try {
            val flexCacheFile = File(cacheDir, "${regionId}_$cacheName.flex")
            val indexEntries = mutableListOf<MapOverlayCacheUtils.HilbertIndexEntry>()
            var processedCount = 0
            
            FileOutputStream(flexCacheFile).use { fos ->
                var currentOffset = 0
                val builder = com.google.flatbuffers.FlexBuffersBuilder(1024)
                
                val appendFeature: (OverlayFeature) -> Unit = { feature ->
                    builder.clear()
                    val mapStart = builder.startMap()
                    
                    val featureMapStart = builder.startMap()
                    MapOverlayCacheUtils.serializeMap(builder, feature.feature)
                    builder.endMap("feature", featureMapStart)
                    
                    val centroidMapStart = builder.startMap()
                    builder.putFloat("latitude", feature.centroid.latitude.toFloat())
                    builder.putFloat("longitude", feature.centroid.longitude.toFloat())
                    builder.endMap("centroid", centroidMapStart)
                    
                    builder.putInt("hilbertIndex", feature.hilbertIndex)
                    builder.putString("overlayType", feature.overlayType)
                    feature.id?.let { builder.putString("id", it) }
                    
                    builder.endMap(null, mapStart)
                    val buffer = builder.finish()
                    
                    val length = buffer.remaining()
                    val lengthBytes = java.nio.ByteBuffer.allocate(4).putInt(length).array()
                    
                    fos.write(lengthBytes)
                    
                    val data = ByteArray(length)
                    buffer.get(data)
                    fos.write(data)
                    
                    indexEntries.add(MapOverlayCacheUtils.HilbertIndexEntry(feature.hilbertIndex, currentOffset, 4 + length))
                    currentOffset += 4 + length
                    processedCount++
                }
                
                val success = streamAction(appendFeature)
                if (!success || processedCount == 0) {
                    return false
                }
            }
            
            val spatialIndex = MapOverlayCacheUtils.SpatialIndex(indexEntries.sortedBy { it.hilbertIndex })
            
            val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")
            val indexData = objectMapper.writeValueAsBytes(spatialIndex)
            indexFile.writeBytes(indexData)

            spatialIndexCache[regionId] = spatialIndex
            
            memoryMappedBuffers.remove(regionId) 
            createMemoryMappedBuffer(regionId, flexCacheFile)

            cacheIndex[regionId] = System.currentTimeMillis()
            saveCacheIndex()

            Log.d(TAG, "Successfully stream cached $processedCount features for $cacheName/$regionId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error stream caching features for $cacheName/$regionId", e)
            return false
        }
    }

    /**
     * Get all cached features for a region (without spatial filtering)
     */
    fun getCachedFeatures(regionIdRaw: String): List<OverlayFeature>? {
        val regionId = regionIdRaw.uppercase()
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
     * Query nearby features using Hilbert spatial indexing with Lazy Hydration limit
     * @param regionIdRaw Region/Country code
     * @param center Current map center
     * @param maxDistanceMiles Search radius
     * @param limit Maximum number of features to hydrate (Budget-aware)
     * @return List of hydrated OverlayFeatures
     */
    fun queryNearby(
        regionIdRaw: String, 
        center: GeoPoint, 
        maxDistanceMiles: Double,
        limit: Int = 1000 // Default generous limit for non-budgeted calls
    ): List<OverlayFeature> {
        val regionId = regionIdRaw.uppercase()
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

            // Find relevant indices from Hilbert curve (approximate spatial proximity)
            val relevantEntries = spatialIndex.findNearbyIndices(centerIndex, range)

            // OPTIMIZATION: If too many entries, sort them by actual distance using peekCentroid
            // before full hydration to keep JVM heap clean.
            val processedEntries = if (relevantEntries.size > limit) {
                relevantEntries.mapNotNull { entry ->
                    synchronized(mappedBuffer) {
                        mappedBuffer.position(entry.byteOffset)
                        val centroid = MapOverlayCacheUtils.peekCentroid(mappedBuffer)
                        if (centroid != null) {
                            entry to center.distanceToAsDouble(centroid)
                        } else null
                    }
                }.sortedBy { it.second }
                 .take(limit)
                 .map { it.first }
            } else {
                relevantEntries
            }

            // Hydrate only the thinned/relevant features
            val nearbyFeatures = processedEntries.mapNotNull { entry ->
                try {
                    if (entry.byteLength <= 4) return@mapNotNull null

                    val featureBytes = ByteArray(entry.byteLength)
                    synchronized(mappedBuffer) {
                        mappedBuffer.position(entry.byteOffset)
                        mappedBuffer.get(featureBytes, 0, entry.byteLength)
                    }

                    val overlayFeature = MapOverlayCacheUtils.deserializeSingleFlexBufferFeature(java.nio.ByteBuffer.wrap(featureBytes))
                    
                    // Final distance validation (if not already sorted/filtered)
                    val distanceMeters = center.distanceToAsDouble(overlayFeature?.centroid ?: GeoPoint(0.0, 0.0))
                    if (overlayFeature != null && distanceMeters <= maxDistanceMeters) {
                        overlayFeature
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading feature at offset ${entry.byteOffset}: ${e.message}")
                    null
                }
            }

            return if (nearbyFeatures.size > limit) nearbyFeatures.take(limit) else nearbyFeatures

        } catch (e: Exception) {
            Log.e(TAG, "Error querying nearby features for $cacheName/$regionId", e)
            return emptyList()
        }
    }

    /**
     * Clear cache for a specific region
     */
    fun clearCacheForRegion(regionIdRaw: String) {
        val regionId = regionIdRaw.uppercase()
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
    private fun getMemoryMappedBuffer(regionIdRaw: String): MappedByteBuffer? {
        val regionId = regionIdRaw.uppercase()
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
