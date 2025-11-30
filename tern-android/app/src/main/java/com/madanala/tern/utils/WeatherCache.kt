package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.madanala.tern.model.WeatherForecast
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
 * Weather Cache - FlexBuffers + Hilbert Spatial Indexing
 * Stores weather forecasts spatially for efficient retrieval.
 */
class WeatherCache(context: Context) {

    companion object {
        const val WEATHER_CACHE_HOURS = 4 // Weather data expires quickly
        private const val TAG = "WeatherCache"
    }

    private val cacheDir: File = File(context.cacheDir, "weather_cache")
    private val cacheIndexFile = File(cacheDir, "cache_index")
    private val cacheIndex = ConcurrentHashMap<String, Long>() // region/routeId -> timestamp
    private val spatialIndexCache = ConcurrentHashMap<String, MapOverlayCacheUtils.SpatialIndex>()
    private val memoryMappedBuffers = ConcurrentHashMap<String, MappedByteBuffer>()
    
    private val objectMapper = jacksonObjectMapper()

    init {
        cacheDir.mkdirs()
        loadCacheIndex()
    }

    /**
     * Cache weather forecast for a specific location (e.g. Route centroid)
     */
    fun cacheWeather(id: String, location: GeoPoint, forecast: WeatherForecast) {
        try {
            // Convert WeatherForecast to Map for OverlayFeature
            val forecastMap = objectMapper.convertValue(forecast, object : TypeReference<Map<String, Any>>() {})
            
            // Create OverlayFeature
            val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(location, 16)
            val feature = OverlayFeature(
                feature = forecastMap,
                centroid = location,
                hilbertIndex = hilbertIndex,
                overlayType = "weather"
            )

            // Cache as a single-item list (or append if we supported appending)
            // For simplicity, we treat each "id" (e.g. routeId) as a separate cache file/region
            // In a real grid system, we'd group by region/tile. 
            // Here, we follow the countryCode pattern but use id.
            cacheWeatherFeatures(id, listOf(feature))

        } catch (e: Exception) {
            Log.e(TAG, "Error caching weather for $id", e)
        }
    }

    /**
     * Query nearby weather forecasts
     */
    fun queryNearbyWeather(id: String, center: GeoPoint, maxDistanceMiles: Double): List<WeatherForecast> {
        try {
            println("DEBUG: Querying weather for $id at $center radius $maxDistanceMiles")
            // Get spatial index
            val spatialIndex = getSpatialIndex(id)
            if (spatialIndex == null) {
                println("DEBUG: Spatial index not found for $id")
                return emptyList()
            }
            
            // Get memory mapped buffer
            val mappedBuffer = getMemoryMappedBuffer(id)
            if (mappedBuffer == null) {
                println("DEBUG: Memory mapped buffer not found for $id")
                return emptyList()
            }

            // Compute Hilbert center index
            val centerIndex = MapOverlayCacheUtils.computeHilbertIndex(center, spatialIndex.bits)
            
            // Calculate range
            val maxDistanceMeters = maxDistanceMiles * 1609.34
            val range = (maxDistanceMeters / 1000.0 * 1000.0).toLong().coerceAtLeast(1000)

            println("DEBUG: Hilbert query: center=$centerIndex range=$range")

            // Find indices
            val relevantEntries = spatialIndex.findNearbyIndices(centerIndex, range)
            println("DEBUG: Found ${relevantEntries.size} relevant entries")

            // Deserialize
            return relevantEntries.mapNotNull { entry ->
                try {
                    val featureBytes = ByteArray(entry.byteLength)
                    synchronized(mappedBuffer) {
                        mappedBuffer.position(entry.byteOffset)
                        mappedBuffer.get(featureBytes, 0, entry.byteLength)
                    }

                    val featureJson = String(featureBytes, Charsets.UTF_8)
                    println("DEBUG: Read feature JSON: $featureJson") 
                    val featureData: Map<String, Any> = objectMapper.readValue(featureJson, object : TypeReference<Map<String, Any>>() {})
                    
                    @Suppress("UNCHECKED_CAST")
                    val featureMap = featureData["feature"] as? Map<String, Any>
                    
                    if (featureMap != null) {
                        objectMapper.convertValue(featureMap, WeatherForecast::class.java)
                    } else {
                        println("DEBUG: Feature map is null")
                        null
                    }
                } catch (e: Exception) {
                    println("DEBUG: Error reading weather feature: ${e.message}")
                    e.printStackTrace()
                    null
                }
            }

        } catch (e: Exception) {
            println("DEBUG: Error querying weather for $id: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun cacheWeatherFeatures(id: String, features: List<OverlayFeature>) {
        try {
            val (spatialIndex, flexBuffersData) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(features)

            val flexCacheFile = File(cacheDir, "${id}_weather.flex")
            flexCacheFile.writeBytes(flexBuffersData)

            val indexFile = File(cacheDir, "${id}_weather.idx")
            val indexData = objectMapper.writeValueAsBytes(spatialIndex)
            indexFile.writeBytes(indexData)

            spatialIndexCache[id] = spatialIndex
            createMemoryMappedBuffer(id, flexCacheFile)

            cacheIndex[id] = System.currentTimeMillis()
            saveCacheIndex()

        } catch (e: Exception) {
            Log.e(TAG, "Error caching weather features for $id", e)
        }
    }

    private fun getSpatialIndex(id: String): MapOverlayCacheUtils.SpatialIndex? {
        spatialIndexCache[id]?.let { return it }
        return try {
            val indexFile = File(cacheDir, "${id}_weather.idx")
            if (indexFile.exists()) {
                val indexData = indexFile.readBytes()
                val indexJson = String(indexData, Charsets.UTF_8)
                val spatialIndex = objectMapper.readValue(indexJson, MapOverlayCacheUtils.SpatialIndex::class.java)
                spatialIndexCache[id] = spatialIndex
                spatialIndex
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getMemoryMappedBuffer(id: String): MappedByteBuffer? {
        memoryMappedBuffers[id]?.let { return it }
        return try {
            val dataFile = File(cacheDir, "${id}_weather.flex")
            if (!dataFile.exists()) return null
            RandomAccessFile(dataFile, "r").use { randomAccessFile ->
                randomAccessFile.channel.use { fileChannel ->
                    val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, dataFile.length())
                    memoryMappedBuffers[id] = buffer
                    buffer
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun createMemoryMappedBuffer(id: String, flexCacheFile: File) {
        try {
            RandomAccessFile(flexCacheFile, "r").use { randomAccessFile ->
                randomAccessFile.channel.use { fileChannel ->
                    val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, flexCacheFile.length())
                    memoryMappedBuffers[id] = buffer
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating memory-mapped buffer for $id", e)
        }
    }

    private fun loadCacheIndex() {
        try {
            if (cacheIndexFile.exists()) {
                FileInputStream(cacheIndexFile).use { fis ->
                    ObjectInputStream(fis).use { ois ->
                        @Suppress("UNCHECKED_CAST")
                        val loadedIndex = ois.readObject() as? Map<String, Long>
                        if (loadedIndex != null) cacheIndex.putAll(loadedIndex)
                    }
                }
            }
        } catch (e: Exception) {
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
    
    fun clearCache() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        cacheIndex.clear()
        spatialIndexCache.clear()
        memoryMappedBuffers.clear()
    }
}
