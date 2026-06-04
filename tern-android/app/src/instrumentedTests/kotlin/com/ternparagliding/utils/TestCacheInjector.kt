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
 */
object TestCacheInjector {

    fun injectPGSpots(context: Context, cache: PGSpotCache, countryCodeRaw: String, features: List<MapOverlayCacheUtils.OverlayFeature>) {
        val countryCode = countryCodeRaw.uppercase()
        val cacheDir = File(context.cacheDir, "pgspots_cache") // Updated to match SpatialDiskCache naming
        if (!cacheDir.exists()) cacheDir.mkdirs()

        // 1. Create Spatial Index and Serialize Features
        val (spatialIndex, flexBuffersData) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(features)

        // 2. Write .flex file
        val flexFile = File(cacheDir, "${countryCode}_pgspots.flex")
        flexFile.writeBytes(flexBuffersData)

        // 3. Write .idx file
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val indexFile = File(cacheDir, "${countryCode}_pgspots.idx")
        indexFile.writeBytes(mapper.writeValueAsBytes(spatialIndex))

        // 4. Update cache_index on disk
        updateCacheIndex(cacheDir, countryCode)
        
        // 5. Update in-memory cache index and clear internal caches to force reload
        try {
            // Access 'diskCache' field in PGSpotCache
            val diskCacheField = PGSpotCache::class.java.getDeclaredField("diskCache")
            diskCacheField.isAccessible = true
            val diskCache = diskCacheField.get(cache)

            // Access 'cacheIndex' in SpatialDiskCache
            val cacheIndexField = SpatialDiskCache::class.java.getDeclaredField("cacheIndex")
            cacheIndexField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cacheIndexMap = cacheIndexField.get(diskCache) as MutableMap<String, Long>
            cacheIndexMap[countryCode] = System.currentTimeMillis()
            
            // Clear spatialIndexCache to force reload from disk
            val spatialIndexField = SpatialDiskCache::class.java.getDeclaredField("spatialIndexCache")
            spatialIndexField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val spatialIndexMap = spatialIndexField.get(diskCache) as MutableMap<String, Any>
            spatialIndexMap.remove(countryCode)

            // Clear memoryMappedBuffers to force reload from disk
            val buffersField = SpatialDiskCache::class.java.getDeclaredField("memoryMappedBuffers")
            buffersField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val buffersMap = buffersField.get(diskCache) as MutableMap<String, Any>
            buffersMap.remove(countryCode)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun injectAirspaces(context: Context, cache: AirspaceCache, countryCodeRaw: String, features: List<MapOverlayCacheUtils.OverlayFeature>) {
        val countryCode = countryCodeRaw.uppercase()
        val cacheDir = File(context.cacheDir, "airspace_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        // 1. Create Spatial Index and Serialize Features
        val (spatialIndex, flexBuffersData) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(features)

        // 2. Write .flex file
        val flexFile = File(cacheDir, "${countryCode}_airspace.flex")
        flexFile.writeBytes(flexBuffersData)

        // 3. Write .idx file
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val indexFile = File(cacheDir, "${countryCode}_airspace.idx")
        indexFile.writeBytes(mapper.writeValueAsBytes(spatialIndex))

        // 4. Update cache_index on disk
        updateCacheIndex(cacheDir, countryCode)

        // 5. Update in-memory cache index and clear internal caches to force reload
        try {
            // Access 'diskCache' field in AirspaceCache
            val diskCacheField = AirspaceCache::class.java.getDeclaredField("diskCache")
            diskCacheField.isAccessible = true
            val diskCache = diskCacheField.get(cache)

            // Access 'cacheIndex' in SpatialDiskCache
            val cacheIndexField = SpatialDiskCache::class.java.getDeclaredField("cacheIndex")
            cacheIndexField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cacheIndexMap = cacheIndexField.get(diskCache) as MutableMap<String, Long>
            cacheIndexMap[countryCode] = System.currentTimeMillis()
            
            // Clear spatialIndexCache to force reload from disk
            val spatialIndexField = SpatialDiskCache::class.java.getDeclaredField("spatialIndexCache")
            spatialIndexField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val spatialIndexMap = spatialIndexField.get(diskCache) as MutableMap<String, Any>
            spatialIndexMap.remove(countryCode)

            // Clear memoryMappedBuffers to force reload from disk
            val buffersField = SpatialDiskCache::class.java.getDeclaredField("memoryMappedBuffers")
            buffersField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val buffersMap = buffersField.get(diskCache) as MutableMap<String, Any>
            buffersMap.remove(countryCode)
            
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
