package com.madanala.tern.utils

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

    fun injectPGSpots(context: Context, cache: PGSpotCache, countryCode: String, features: List<MapOverlayCacheUtils.OverlayFeature>) {
        val cacheDir = File(context.cacheDir, "pg_spot_cache")
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
        
        // 5. Update in-memory cache index
        try {
            cache.cacheIndex[countryCode] = System.currentTimeMillis()
        } catch (e: IllegalAccessException) {
            // Fallback to reflection if internal access fails (different module)
            val field = PGSpotCache::class.java.getDeclaredField("cacheIndex")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(cache) as java.util.concurrent.ConcurrentHashMap<String, Long>
            map[countryCode] = System.currentTimeMillis()
        }
    }

    fun injectAirspaces(context: Context, cache: AirspaceCache, countryCode: String, features: List<MapOverlayCacheUtils.OverlayFeature>) {
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

        // 5. Update in-memory cache index
        try {
            cache.cacheIndex[countryCode] = System.currentTimeMillis()
        } catch (e: IllegalAccessException) {
            // Fallback to reflection if internal access fails
            val field = AirspaceCache::class.java.getDeclaredField("cacheIndex")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(cache) as java.util.concurrent.ConcurrentHashMap<String, Long>
            map[countryCode] = System.currentTimeMillis()
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
        cacheIndex[countryCode] = System.currentTimeMillis()

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
