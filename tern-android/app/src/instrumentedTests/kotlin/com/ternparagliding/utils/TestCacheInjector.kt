package com.ternparagliding.utils
import com.ternparagliding.utils.cache.AirspaceCache
import com.ternparagliding.utils.cache.MapOverlayCacheUtils
import com.ternparagliding.utils.cache.PGSpotCache
import com.ternparagliding.utils.cache.SpatialDiskCache

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectOutputStream
import java.io.FileInputStream
import java.io.ObjectInputStream

/**
 * Helper class to inject test data into the app's cache directories.
 * This allows tests to populate the cache without using test-specific hooks in production code.
 *
 * Writes the cache files in the SAME on-disk format the production write paths
 * use (binary TSI2 `.idx` whose header carries the data bbox, Hilbert-ordered
 * `.flex`), so injected data is self-consistent — the bbox the v2 query filter
 * reads comes from the same index records, and can never desync.
 */
object TestCacheInjector {

    fun injectPGSpots(context: Context, cache: PGSpotCache, countryCodeRaw: String, features: List<MapOverlayCacheUtils.OverlayFeature>) {
        val countryCode = countryCodeRaw.uppercase()
        val cacheDir = File(context.cacheDir, "pgspots_cache")
        writeCacheFiles(cacheDir, countryCode, "pgspots", features)
        invalidateInMemory(PGSpotCache::class.java, cache, countryCode)
    }

    fun injectAirspaces(context: Context, cache: AirspaceCache, countryCodeRaw: String, features: List<MapOverlayCacheUtils.OverlayFeature>) {
        val countryCode = countryCodeRaw.uppercase()
        val cacheDir = File(context.cacheDir, "airspace_cache")
        writeCacheFiles(cacheDir, countryCode, "airspace", features)
        invalidateInMemory(AirspaceCache::class.java, cache, countryCode)
    }

    /** Write the .flex + binary .idx + cache_index for a region, like the real write paths. */
    private fun writeCacheFiles(
        cacheDir: File,
        countryCode: String,
        cacheName: String,
        features: List<MapOverlayCacheUtils.OverlayFeature>,
    ) {
        if (!cacheDir.exists()) cacheDir.mkdirs()

        // createSpatialIndexAndSerialize emits the .flex in Hilbert order with
        // matching offsets; serializeIndexBinary writes the TSI2 index whose
        // header bbox is derived from those same records.
        val (spatialIndex, flexBuffersData) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(features)

        File(cacheDir, "${countryCode}_$cacheName.flex").writeBytes(flexBuffersData)
        File(cacheDir, "${countryCode}_$cacheName.idx").writeBytes(
            MapOverlayCacheUtils.serializeIndexBinary(spatialIndex.entries, spatialIndex.bits, System.currentTimeMillis()),
        )
        updateCacheIndex(cacheDir, countryCode)
    }

    /** Drop the live cache's in-memory state for [countryCode] so it re-reads from disk. */
    @Suppress("UNCHECKED_CAST")
    private fun invalidateInMemory(cacheClass: Class<*>, cache: Any, countryCode: String) {
        try {
            val diskCacheField = cacheClass.getDeclaredField("diskCache")
            diskCacheField.isAccessible = true
            val diskCache = diskCacheField.get(cache)

            fun mutableMapField(name: String): MutableMap<String, Any> {
                val f = SpatialDiskCache::class.java.getDeclaredField(name)
                f.isAccessible = true
                return f.get(diskCache) as MutableMap<String, Any>
            }

            (mutableMapField("cacheIndex"))[countryCode] = System.currentTimeMillis()
            mutableMapField("spatialIndexCache").remove(countryCode)
            mutableMapField("memoryMappedBuffers").remove(countryCode)
            mutableMapField("regionHeaders").remove(countryCode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateCacheIndex(cacheDir: File, countryCode: String) {
        val cacheIndexFile = File(cacheDir, "cache_index")
        val cacheIndex = mutableMapOf<String, Long>()

        // Read existing index if present
        if (cacheIndexFile.exists()) {
            try {
                FileInputStream(cacheIndexFile).use { fis ->
                    ObjectInputStream(fis).use { ois ->
                        val loadedIndex = ois.readObject() as? Map<String, Long>
                        if (loadedIndex != null) {
                            cacheIndex.putAll(loadedIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore errors, start fresh
            }
        }

        // Update timestamp
        cacheIndex[countryCode.uppercase()] = System.currentTimeMillis()

        // Write back
        try {
            FileOutputStream(cacheIndexFile).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(cacheIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
