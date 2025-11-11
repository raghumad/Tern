package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.madanala.tern.route.Route
import com.madanala.tern.model.Waypoint
import org.osmdroid.util.GeoPoint
import java.io.*
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache manager for route data using FlexBuffers and Hilbert indexing
 * Mimics AirspaceCache pattern but optimized for route-centric data
 */
class RouteCache(private val context: Context) {

    companion object {
        // Cache validity periods (in hours)
        const val ROUTE_CACHE_HOURS = 168  // 7 days for routes (less critical than airspaces)
    }

    private val cacheDir: File = File(context.cacheDir, "route_cache")
    private val cacheIndexFile = File(cacheDir, "route_cache_index")
    private val cacheIndex = ConcurrentHashMap<String, Long>() // routeId -> timestamp
    private val spatialIndexCache = ConcurrentHashMap<String, MapOverlayCacheUtils.SpatialIndex>() // routeId -> spatial index
    private val memoryMappedBuffers = ConcurrentHashMap<String, MappedByteBuffer>() // routeId -> memory mapped buffer

    private val objectMapper = ObjectMapper()
    private val TAG = "RouteCache"

    init {
        cacheDir.mkdirs()
        loadCacheIndex()
    }

    /**
     * Check if route data is cached and not too old
     * @param routeId Route identifier
     * @param maxAgeHours Maximum age of cached data in hours
     * @return true if cached data exists and is fresh
     */
    fun isCached(routeId: String, maxAgeHours: Int = ROUTE_CACHE_HOURS): Boolean {
        val timestamp = cacheIndex[routeId] ?: return false
        val ageHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60)
        val isFresh = ageHours < maxAgeHours

        if (!isFresh) {
            Log.d(TAG, "Route cache stale for $routeId (${ageHours}h old, max ${maxAgeHours}h)")
            return false
        }

        return validateCacheIntegrity(routeId)
    }

    /**
     * Validate cache integrity for a route
     */
    private fun validateCacheIntegrity(routeId: String): Boolean {
        val cacheFile = File(cacheDir, "${routeId}_route.flex")
        val indexFile = File(cacheDir, "${routeId}_route.idx")

        val filesExist = cacheFile.exists() && indexFile.exists()
        if (!filesExist) {
            Log.d(TAG, "Route cache files missing for $routeId")
            return false
        }

        val filesReadable = cacheFile.canRead() && indexFile.canRead()
        if (!filesReadable) {
            Log.w(TAG, "Route cache files not readable for $routeId")
            return false
        }

        val cacheFileSize = cacheFile.length()
        val indexFileSize = indexFile.length()

        if (cacheFileSize < 100) {
            Log.w(TAG, "Route cache file too small for $routeId (${cacheFileSize} bytes)")
            return false
        }

        if (indexFileSize < 50) {
            Log.w(TAG, "Route index file too small for $routeId (${indexFileSize} bytes)")
            return false
        }

        try {
            val indexData = indexFile.readBytes()
            val indexJson = String(indexData, Charsets.UTF_8)
            val spatialIndex = objectMapper.readValue(indexJson, MapOverlayCacheUtils.SpatialIndex::class.java)

            if (spatialIndex.bits <= 0 || spatialIndex.entries.isEmpty()) {
                Log.w(TAG, "Route spatial index corrupted for $routeId")
                return false
            }

            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error validating route cache integrity for $routeId: ${e.message}")
            return false
        }
    }

    /**
     * Get cached route
     * @param routeId Route identifier
     * @return Route or null if not found
     */
    fun getCachedRoute(routeId: String): Route? {
        return try {
            val cacheFile = File(cacheDir, "${routeId}_route.flex")
            if (cacheFile.exists()) {
                val data = cacheFile.readBytes()
                val features = MapOverlayCacheUtils.deserializeFlexBuffersToFeatures(data)

                if (features.isNotEmpty()) {
                    val route = reconstructRouteFromFeatures(routeId, features)
                    if (route != null) {
                        if (!isCached(routeId)) {
                            cacheIndex[routeId] = System.currentTimeMillis()
                            saveCacheIndex()
                        }
                        route
                    } else {
                        Log.w(TAG, "Failed to reconstruct route from cached features for $routeId")
                        null
                    }
                } else {
                    Log.w(TAG, "No features found in cached route data for $routeId")
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached route for $routeId", e)
            null
        }
    }

    /**
     * Cache route data using FlexBuffers + Hilbert spatial indexing
     * @param route Route to cache
     */
    fun cacheRoute(route: Route) {
        try {
            if (route.waypoints.isEmpty()) {
                Log.w(TAG, "No waypoints to cache for route ${route.id}")
                return
            }

            // Convert route to overlay features for spatial indexing
            val routeFeatures = convertRouteToFeatures(route)

            // Create Hilbert spatial index + FlexBuffers data
            val (spatialIndex, flexBuffersData) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(routeFeatures)

            // Save binary FlexBuffers data
            val flexCacheFile = File(cacheDir, "${route.id}_route.flex")
            flexCacheFile.writeBytes(flexBuffersData)

            // Save spatial index metadata
            val indexFile = File(cacheDir, "${route.id}_route.idx")
            val indexData = objectMapper.writeValueAsBytes(spatialIndex)
            indexFile.writeBytes(indexData)

            // Cache spatial index in memory
            spatialIndexCache[route.id] = spatialIndex

            // Memory-map file for zero-copy I/O
            try {
                createMemoryMappedBuffer(route.id, flexCacheFile)
                Log.v(TAG, "✅ Memory-mapped route file for ${route.id} (${flexCacheFile.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to memory-map route for ${route.id}", e)
            }

            // Update cache validity timestamp
            cacheIndex[route.id] = System.currentTimeMillis()
            saveCacheIndex()

            Log.d(TAG, "Cached route ${route.id} with ${route.waypoints.size} waypoints")

        } catch (e: Exception) {
            Log.e(TAG, "Error caching route ${route.id}", e)
        }
    }

    /**
     * Query routes within distance of center point
     * @param center Center point
     * @param maxDistanceMiles Max distance in miles
     * @param maxRoutes Maximum number of routes to return (default 10)
     * @return List of nearby routes
     */
    fun queryNearbyRoutes(center: GeoPoint, maxDistanceMiles: Double, maxRoutes: Int = 10): List<Route> {
        try {
            val nearbyRoutes = mutableListOf<Route>()

            // Get all cached route IDs
            val routeIds = cacheIndex.keys.toList()

            for (routeId in routeIds) {
                if (nearbyRoutes.size >= maxRoutes) break

                try {
                    val route = getCachedRoute(routeId)
                    if (route != null && isRouteWithinDistance(route, center, maxDistanceMiles)) {
                        nearbyRoutes.add(route)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying route $routeId: ${e.message}")
                }
            }

            Log.d(TAG, "Found ${nearbyRoutes.size} routes within ${maxDistanceMiles} miles of ${center.latitude}, ${center.longitude}")
            return nearbyRoutes

        } catch (e: Exception) {
            Log.e(TAG, "Error querying nearby routes", e)
            return emptyList()
        }
    }

    /**
     * Check if a route is within distance of center point
     */
    private fun isRouteWithinDistance(route: Route, center: GeoPoint, maxDistanceMiles: Double): Boolean {
        val maxDistanceMeters = maxDistanceMiles * 1609.34

        // Check if any waypoint is within distance
        return route.waypoints.any { waypoint ->
            val waypointPoint = GeoPoint(waypoint.lat, waypoint.lon)
            center.distanceToAsDouble(waypointPoint) <= maxDistanceMeters
        }
    }

    /**
     * Reconstruct route from overlay features
     */
    private fun reconstructRouteFromFeatures(routeId: String, features: List<MapOverlayCacheUtils.OverlayFeature>): Route? {
        try {
            if (features.isEmpty()) return null

            // Extract route metadata from first feature
            val firstFeature = features.first()
            val properties = firstFeature.feature["properties"] as? Map<*, *>
            val routeName = properties?.get("routeName") as? String ?: "Reconstructed Route"

            // Extract waypoints from features
            val waypoints = features.mapNotNull { feature ->
                val props = feature.feature["properties"] as? Map<*, *>
                val waypointId = props?.get("waypointId") as? String
                val waypointTypeStr = props?.get("waypointType") as? String
                val label = props?.get("label") as? String

                if (waypointId != null && waypointTypeStr != null) {
                    val waypointType = try {
                        Waypoint.Type.valueOf(waypointTypeStr)
                    } catch (e: Exception) {
                        Waypoint.Type.TURNPOINT // Default fallback
                    }

                    Waypoint(
                        id = waypointId,
                        lat = feature.centroid.latitude,
                        lon = feature.centroid.longitude,
                        type = waypointType,
                        label = if (label.isNullOrEmpty()) null else label,
                        routeId = routeId
                    )
                } else {
                    null
                }
            }.sortedBy { waypoint ->
                // Sort by Hilbert index to maintain original order approximately
                MapOverlayCacheUtils.computeHilbertIndex(GeoPoint(waypoint.lat, waypoint.lon), 32)
            }

            if (waypoints.isNotEmpty()) {
                return Route(
                    id = routeId,
                    name = routeName,
                    waypoints = waypoints
                )
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error reconstructing route from features: ${e.message}")
            return null
        }
    }

    /**
     * Convert route to overlay features for spatial indexing
     */
    private fun convertRouteToFeatures(route: Route): List<MapOverlayCacheUtils.OverlayFeature> {
        return route.waypoints.map { waypoint ->
            val centroid = GeoPoint(waypoint.lat, waypoint.lon)
            val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, 32) // Use 32 bits for routes

            // Create feature data for waypoint
            val featureData = mapOf(
                "type" to "Feature",
                "geometry" to mapOf(
                    "type" to "Point",
                    "coordinates" to listOf(waypoint.lon, waypoint.lat)
                ),
                "properties" to mapOf(
                    "waypointId" to waypoint.id,
                    "routeId" to route.id,
                    "waypointType" to waypoint.type.name,
                    "label" to (waypoint.label ?: ""),
                    "routeName" to route.name
                )
            )

            MapOverlayCacheUtils.OverlayFeature(featureData, centroid, hilbertIndex, "route")
        }
    }

    /**
     * Clear cached data for a specific route
     * @param routeId Route identifier
     */
    fun clearCacheForRoute(routeId: String) {
        try {
            val flexFile = File(cacheDir, "${routeId}_route.flex")
            if (flexFile.exists()) {
                flexFile.delete()
            }
            val indexFile = File(cacheDir, "${routeId}_route.idx")
            if (indexFile.exists()) {
                indexFile.delete()
            }
            cacheIndex.remove(routeId)
            spatialIndexCache.remove(routeId)
            memoryMappedBuffers.remove(routeId)
            saveCacheIndex()
            Log.d(TAG, "Cleared cache for route $routeId")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for route $routeId", e)
        }
    }

    /**
     * Clear all cached route data
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.endsWith("_route.flex") || file.name.endsWith("_route.idx")) {
                    file.delete()
                }
            }
            cacheIndex.clear()
            spatialIndexCache.clear()
            memoryMappedBuffers.clear()
            saveCacheIndex()
            Log.d(TAG, "Cleared all route cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing route cache", e)
        }
    }

    /**
     * Create memory-mapped buffer for route data
     */
    private fun createMemoryMappedBuffer(routeId: String, flexCacheFile: File) {
        try {
            val randomAccessFile = java.io.RandomAccessFile(flexCacheFile, "r")
            randomAccessFile.use { raf ->
                raf.channel.use { fileChannel ->
                    val buffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, flexCacheFile.length())
                    memoryMappedBuffers[routeId] = buffer
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating memory-mapped buffer for route $routeId", e)
            throw e
        }
    }

    /**
     * Get all cached routes
     * @return List of all cached routes
     */
    fun getAllCachedRoutes(): List<Route> {
        return try {
            val cachedRoutes = mutableListOf<Route>()
            val routeIds = cacheIndex.keys.toList()

            for (routeId in routeIds) {
                try {
                    val route = getCachedRoute(routeId)
                    if (route != null) {
                        cachedRoutes.add(route)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error loading cached route $routeId: ${e.message}")
                }
            }

            Log.d(TAG, "Loaded ${cachedRoutes.size} cached routes")
            cachedRoutes
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all cached routes", e)
            emptyList()
        }
    }

    /**
     * Get cache statistics
     * @return Map with cache statistics
     */
    fun getCacheStats(): Map<String, Any> {
        val flexFiles = cacheDir.listFiles()?.filter { it.name.endsWith("_route.flex") } ?: emptyList()
        val totalSize = flexFiles.sumOf { it.length() }
        val fileCount = flexFiles.size

        return mapOf(
            "totalRoutes" to fileCount,
            "totalSizeBytes" to totalSize,
            "totalSizeMB" to String.format("%.2f", totalSize / (1024.0 * 1024.0)),
            "routeIds" to cacheIndex.keys.toList()
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
            Log.e(TAG, "Error loading route cache index", e)
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
            Log.e(TAG, "Error saving route cache index", e)
        }
    }
}
