package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache manager for airspace data
 */
class AirspaceCache(private val context: Context) {

    private val cacheDir: File = File(context.cacheDir, "airspace_cache")
    private val cacheIndexFile = File(cacheDir, "cache_index")
    private val cacheIndex = ConcurrentHashMap<String, Long>() // countryCode -> timestamp

    private val objectMapper = ObjectMapper()
    private val TAG = "AirspaceCache"

    init {
        cacheDir.mkdirs()
        loadCacheIndex()
    }

    /**
     * Check if airspace data for a country is cached and not too old
     * @param countryCode Two-letter country code
     * @param maxAgeHours Maximum age of cached data in hours (default 24 hours)
     * @return true if cached data exists and is fresh
     */
    fun isCached(countryCode: String, maxAgeHours: Int = 24): Boolean {
        val timestamp = cacheIndex[countryCode] ?: return false
        val ageHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60)
        return ageHours < maxAgeHours
    }

    /**
     * Get cached airspace data for a country
     * @param countryCode Two-letter country code
     * @return Cached NDGeoJSON string or null if not found
     */
    fun getCachedData(countryCode: String): String? {
        if (!isCached(countryCode)) {
            return null
        }

        return try {
            val cacheFile = File(cacheDir, "${countryCode}_airspace.ndgeojson")
            if (cacheFile.exists()) {
                cacheFile.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached data for $countryCode", e)
            null
        }
    }

    /**
     * Cache airspace data for a country
     * @param countryCode Two-letter country code
     * @param ndGeoJsonString The NDGeoJSON data to cache
     */
    fun cacheData(countryCode: String, ndGeoJsonString: String) {
        try {
            val cacheFile = File(cacheDir, "${countryCode}_airspace.ndgeojson")
            cacheFile.writeText(ndGeoJsonString)

            // Update cache index
            cacheIndex[countryCode] = System.currentTimeMillis()
            saveCacheIndex()

            Log.d(TAG, "Cached airspace data for $countryCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error caching data for $countryCode", e)
        }
    }

    /**
     * Clear all cached airspace data
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.endsWith("_airspace.ndgeojson")) {
                    file.delete()
                }
            }
            cacheIndex.clear()
            saveCacheIndex()
            Log.d(TAG, "Cleared all airspace cache")
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
            val cacheFile = File(cacheDir, "${countryCode}_airspace.ndgeojson")
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            cacheIndex.remove(countryCode)
            saveCacheIndex()
            Log.d(TAG, "Cleared cache for $countryCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for $countryCode", e)
        }
    }

    /**
     * Get cache statistics
     * @return Map with cache statistics
     */
    fun getCacheStats(): Map<String, Any> {
        val totalSize = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        val fileCount = cacheDir.listFiles()?.count { it.name.endsWith("_airspace.ndgeojson") } ?: 0

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
