package com.ternparagliding.utils.cache
import com.ternparagliding.utils.diagnostics.trackAllocation
import com.ternparagliding.utils.diagnostics.trackDeallocation

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature
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

    // regionId -> parsed binary .idx header (data bbox + count), cached so a
    // caller can skip far-away regions by bbox without parsing the whole index.
    // v2: the bbox lives IN the .idx header (derived from the records at write
    // time), so it can never desync from the data — no separate sidecar.
    private val regionHeaders = ConcurrentHashMap<String, MapOverlayCacheUtils.IndexHeader>()
    private val schemaVersionFile = File(cacheDir, "schema_version")
    
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

    /**
     * Drop this cache's on-disk data if it was written by an older schema (e.g.
     * before per-region bounds + populated centroids), forcing a one-time
     * re-download. Safe per cache type: each lives in its own dir, so clearing
     * airspace/PG-spot does NOT touch routes (user data) or weather/hotspots.
     * Callers that hold re-downloadable data invoke this; routes must not.
     */
    fun clearIfSchemaChanged(currentVersion: Int) {
        val stored = try {
            if (schemaVersionFile.exists()) schemaVersionFile.readText().trim().toIntOrNull() else null
        } catch (e: Exception) {
            null
        }
        if (stored != currentVersion) {
            Log.i(TAG, "Cache schema $stored != $currentVersion for $cacheName — clearing for re-download")
            clearAll()
            try {
                schemaVersionFile.writeText(currentVersion.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Could not write schema version for $cacheName: ${e.message}")
            }
        }
    }

    /**
     * Read just the binary `.idx` header (data bbox + count, ~56 B) for a
     * region, cached. Returns null for a missing index or a legacy JSON `.idx`
     * (which has no header bbox — such a region is queried conservatively).
     */
    private fun readHeader(regionIdRaw: String): MapOverlayCacheUtils.IndexHeader? {
        val regionId = regionIdRaw.uppercase()
        regionHeaders[regionId]?.let { return it }
        return try {
            val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")
            if (!indexFile.exists()) return null
            RandomAccessFile(indexFile, "r").use { raf ->
                val n = minOf(MapOverlayCacheUtils.INDEX_HEADER_BYTES.toLong(), raf.length()).toInt()
                if (n < 4) return null
                val head = ByteArray(n)
                raf.readFully(head)
                val header = MapOverlayCacheUtils.readIndexHeader(head) ?: return null
                regionHeaders[regionId] = header
                header
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Cached data bbox for a region, or null if unknown (legacy JSON index / not cached). */
    fun getRegionBounds(regionIdRaw: String): MapOverlayCacheUtils.RegionBounds? =
        readHeader(regionIdRaw)?.bounds

    // ... (existing code)

    private fun getSpatialIndex(regionIdRaw: String): MapOverlayCacheUtils.SpatialIndex? {
        val regionId = regionIdRaw.uppercase()
        spatialIndexCache[regionId]?.let { return it }

        return try {
            val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")
            if (!indexFile.exists()) return null
            val indexData = indexFile.readBytes()
            val spatialIndex = if (MapOverlayCacheUtils.isBinaryIndex(indexData)) {
                MapOverlayCacheUtils.readIndexHeader(indexData)?.let { regionHeaders[regionId] = it }
                MapOverlayCacheUtils.deserializeIndexBinary(indexData)
            } else {
                // Legacy JSON index (e.g. a route not yet re-written): read it
                // directly — no header bbox, lazily upgraded to binary on next write.
                objectMapper.readValue(
                    String(indexData, Charsets.UTF_8),
                    MapOverlayCacheUtils.SpatialIndex::class.java,
                )
            } ?: return null
            spatialIndexCache[regionId] = spatialIndex
            trackAllocation("SpatialIndex:$cacheName", indexData.size.toLong())
            spatialIndex
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

        // Try to load spatial index to verify it's not corrupted (accept either
        // the binary TSI2 format or a legacy JSON index — the latter stays
        // readable so a format bump never strands user data like routes).
        try {
            val indexData = indexFile.readBytes()
            if (MapOverlayCacheUtils.isBinaryIndex(indexData)) {
                val header = MapOverlayCacheUtils.readIndexHeader(indexData)
                if (header == null || header.bits <= 0 || header.count <= 0) {
                    Log.w(TAG, "Binary spatial index corrupted for $cacheName/$regionId")
                    return false
                }
                return true
            }
            val spatialIndex = objectMapper.readValue(
                String(indexData, Charsets.UTF_8),
                MapOverlayCacheUtils.SpatialIndex::class.java,
            )
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

            // Save the spatial index in the binary TSI2 format. createSpatialIndex
            // AndSerialize already emitted the .flex in Hilbert order with matching
            // offsets, and serializeIndexBinary derives the header bbox from the
            // records — so the bbox can never disagree with the data.
            val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")
            val indexData = MapOverlayCacheUtils.serializeIndexBinary(
                spatialIndex.entries, spatialIndex.bits, System.currentTimeMillis(),
            )
            indexFile.writeBytes(indexData)

            // Update in-memory caches
            spatialIndexCache[regionId] = spatialIndex
            MapOverlayCacheUtils.readIndexHeader(indexData)?.let { regionHeaders[regionId] = it }

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

            // v2: buffer each feature's serialized bytes (+ hilbert/centroid), then
            // write the .flex in Hilbert order so a query's survivors (a contiguous
            // Hilbert interval) land in a contiguous byte range — sequential mmap
            // reads. Buffering ~the .flex size in RAM (≈31 MB for US) is acceptable.
            class Pending(val hilbert: Long, val bytes: ByteArray, val cLat: Double, val cLon: Double)
            val pending = ArrayList<Pending>()
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
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                pending.add(Pending(feature.hilbertIndex, data, feature.centroid.latitude, feature.centroid.longitude))
            }

            val success = streamAction(appendFeature)
            if (!success || pending.isEmpty()) {
                return false
            }

            // Sort by Hilbert index, then write length-prefixed records in that
            // order, recording matching offsets for the index.
            pending.sortBy { it.hilbert }
            val indexEntries = ArrayList<MapOverlayCacheUtils.HilbertIndexEntry>(pending.size)
            FileOutputStream(flexCacheFile).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    var currentOffset = 0
                    val lenBuf = java.nio.ByteBuffer.allocate(4)
                    for (p in pending) {
                        lenBuf.clear()
                        bos.write(lenBuf.putInt(p.bytes.size).array())
                        bos.write(p.bytes)
                        indexEntries.add(
                            MapOverlayCacheUtils.HilbertIndexEntry(p.hilbert, currentOffset, 4 + p.bytes.size, p.cLat, p.cLon),
                        )
                        currentOffset += 4 + p.bytes.size
                    }
                }
            }

            val spatialIndex = MapOverlayCacheUtils.SpatialIndex(indexEntries) // already Hilbert-sorted

            val indexFile = File(cacheDir, "${regionId}_$cacheName.idx")
            val indexData = MapOverlayCacheUtils.serializeIndexBinary(
                spatialIndex.entries, spatialIndex.bits, System.currentTimeMillis(),
            )
            indexFile.writeBytes(indexData)

            spatialIndexCache[regionId] = spatialIndex
            MapOverlayCacheUtils.readIndexHeader(indexData)?.let { regionHeaders[regionId] = it }

            memoryMappedBuffers.remove(regionId)
            createMemoryMappedBuffer(regionId, flexCacheFile)

            cacheIndex[regionId] = System.currentTimeMillis()
            saveCacheIndex()

            Log.d(TAG, "Successfully stream cached ${pending.size} features for $cacheName/$regionId")
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
    /** Great-circle distance in metres. Inlined (no GeoPoint allocation) so the
     *  per-entry radius filter in [queryNearby] stays allocation-free in the hot
     *  path — it runs over every cached feature on each overlay requery. */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

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

            val maxDistanceMeters = maxDistanceMiles * 1609.34

            // Hilbert-range query: cover the radius box with a small set of
            // Hilbert intervals, binary-search the sorted index for each, then
            // haversine-refine to the exact radius. O(log N + k) — touches ~k
            // features, not all N. (The single-window version this replaced
            // dropped features across curve folds; the full-scan version that
            // fixed that was O(N). Interval-cover gets both: fast AND complete —
            // see docs/design/hilbert-spatial-query-restore.md.) Legacy entries
            // without a carried centroid fall back to peeking the mapped buffer.
            val survivors = MapOverlayCacheUtils.queryHilbertRange(
                sortedEntries = spatialIndex.entries,
                bits = spatialIndex.bits,
                center = center,
                radiusMeters = maxDistanceMeters,
                limit = limit,
            ) { entry ->
                synchronized(mappedBuffer) {
                    mappedBuffer.position(entry.byteOffset)
                    MapOverlayCacheUtils.peekCentroid(mappedBuffer)
                }
            }

            // Hydrate only the in-radius, budgeted survivors.
            val nearbyFeatures = survivors.mapNotNull { entry ->
                try {
                    val featureBytes = ByteArray(entry.byteLength)
                    synchronized(mappedBuffer) {
                        mappedBuffer.position(entry.byteOffset)
                        mappedBuffer.get(featureBytes, 0, entry.byteLength)
                    }
                    MapOverlayCacheUtils.deserializeSingleFlexBufferFeature(java.nio.ByteBuffer.wrap(featureBytes))
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
            regionHeaders.remove(regionId)
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
            regionHeaders.clear()
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
