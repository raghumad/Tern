package com.ternparagliding.utils.cache
import com.ternparagliding.model.LocationType

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ternparagliding.model.Task
import com.ternparagliding.redux.TaskConstants
import com.ternparagliding.model.Waypoint
import org.osmdroid.util.GeoPoint
import java.io.*
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache manager for task data using FlexBuffers and Hilbert indexing
 * Mimics AirspaceCache pattern but optimized for task-centric data
 */
class TaskCache(
    context: Context,
    private val diskCache: SpatialDiskCache = SpatialDiskCache(context, "task", ROUTE_CACHE_HOURS)
) {

    companion object {
        const val ROUTE_CACHE_HOURS = Int.MAX_VALUE  // Permanent (Never automatically expire user-created tasks)
        private const val TAG = "TaskCache"
        private const val HILBERT_BITS_PRECISION = 32
        private const val MAX_DISTANCE_METERS_PER_MILE = 1609.34
    }

    /**
     * Check if task data is cached and not too old
     */
    fun isCached(taskId: String): Boolean {
        return diskCache.isCached(taskId)
    }

    /**
     * Get cached task
     */
    fun getCachedTask(taskId: String): Task? {
        val features = diskCache.getCachedFeatures(taskId) ?: return null
        if (features.isEmpty()) return null

        return reconstructTaskFromFeatures(taskId, features)
    }

    /**
     * Cache task data using FlexBuffers + Hilbert spatial indexing
     */
    fun cacheTask(task: Task) {
        try {
            if (task.waypoints.isEmpty()) {
                Log.w(TAG, "No waypoints to cache for task ${task.id}")
                return
            }

            // Convert task to overlay features for spatial indexing
            val taskFeatures = convertTaskToFeatures(task)

            // Delegate caching to SpatialDiskCache
            diskCache.cacheFeatures(task.id, taskFeatures)

            Log.d(TAG, "Cached task ${task.id} with ${task.waypoints.size} waypoints")

        } catch (e: Exception) {
            Log.e(TAG, "Error caching task ${task.id}", e)
        }
    }

    /**
     * Query tasks within distance of center point
     */
    fun queryNearbyTasks(center: GeoPoint, maxDistanceMiles: Double, maxTasks: Int = 10): List<Task> {
        try {
            val nearbyTasks = mutableListOf<Task>()
            val taskIds = diskCache.cacheIndex.keys.toList()

            for (taskId in taskIds) {
                if (nearbyTasks.size >= maxTasks) break

                // For tasks, we check if *any* waypoint is nearby.
                // SpatialDiskCache.queryNearby returns features (waypoints), not whole tasks.
                // So we can use queryNearby to find relevant task IDs, OR just iterate cached tasks.
                // Given tasks are "long" objects, spatial query on waypoints is better.
                
                // Use in-memory filtering to bypass potential SpatialDiskCache issues
                val allFeatures = diskCache.getCachedFeatures(taskId)
                if (allFeatures != null) {
                    val maxDistanceMeters = maxDistanceMiles * MAX_DISTANCE_METERS_PER_MILE
                    
                    val hasNearby = allFeatures.any { feature ->
                        try {
                            center.distanceToAsDouble(feature.centroid) <= maxDistanceMeters
                        } catch (e: Exception) {
                            false
                        }
                    }
                    
                    if (hasNearby) {
                        // If any waypoint is nearby, load the full task
                        val task = reconstructTaskFromFeatures(taskId, allFeatures)
                        if (task != null) {
                            nearbyTasks.add(task)
                        }
                    }
                }
            }

            Log.d(TAG, "Found ${nearbyTasks.size} tasks within ${maxDistanceMiles} miles")
            return nearbyTasks

        } catch (e: Exception) {
            Log.e(TAG, "Error querying nearby tasks", e)
            return emptyList()
        }
    }

    /**
     * Clear cached data for a specific task
     */
    fun clearCacheForTask(taskId: String) {
        diskCache.clearCacheForRegion(taskId)
    }

    /**
     * Clear all cached task data
     */
    fun clearCache() {
        diskCache.clearAll()
    }

    /**
     * Get all cached tasks
     */
    fun getAllCachedTasks(): List<Task> {
        val cachedTasks = mutableListOf<Task>()
        val taskIds = diskCache.cacheIndex.keys.toList()

        for (taskId in taskIds) {
            val task = getCachedTask(taskId)
            if (task != null) {
                cachedTasks.add(task)
            }
        }
        return cachedTasks
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): Map<String, Any> {
        return diskCache.getStats().mapKeys { 
            if (it.key == "cacheName") "type" else "task${it.key.replaceFirstChar { c -> c.uppercase() }}" 
        }
    }

    // ================= PRIVATE HELPERS =================

    /**
     * Reconstruct task from overlay features
     */
    private fun reconstructTaskFromFeatures(taskId: String, features: List<MapOverlayCacheUtils.OverlayFeature>): Task? {
        try {
            if (features.isEmpty()) return null

            // Expect a single LineString feature containing all task data
            val feature = features.first()
            val properties = feature.feature["properties"] as? Map<*, *>
            val taskName = properties?.get("taskName") as? String ?: "Reconstructed Task"
            val isVisible = properties?.get("isVisible") as? Boolean ?: true
            // Use the task id stored in the feature, not the [taskId] param:
            // SpatialDiskCache uppercases the region key, so queryNearbyTasks
            // passes an uppercased id. The stored property preserves the
            // original case so a round-tripped task keeps its identity.
            val realTaskId = properties?.get("taskId") as? String ?: taskId
            
            val waypointsList = properties?.get("waypoints") as? List<*>
            
            if (waypointsList != null) {
                val waypoints = waypointsList.mapNotNull { wpObj ->
                    if (wpObj is Map<*, *>) {
                        val id = wpObj["id"] as? String
                        val lat = (wpObj["lat"] as? Number)?.toDouble()
                        val lon = (wpObj["lon"] as? Number)?.toDouble()
                        val typeStr = wpObj["type"] as? String
                        val label = wpObj["label"] as? String
                        val radius = (wpObj["radius"] as? Number)?.toDouble()
                        val alt = (wpObj["alt"] as? Number)?.toDouble()
                        val openTime = wpObj["openTime"] as? String
                        val closeTime = wpObj["closeTime"] as? String
                        
                        if (id != null && lat != null && lon != null) {
                            val type = try {
                                LocationType.valueOf(typeStr ?: "TURNPOINT")
                            } catch (e: Exception) {
                                LocationType.TURNPOINT
                            }
                            
                            Waypoint(
                                id = id,
                                lat = lat,
                                lon = lon,
                                type = type,
                                label = label,
                                radius = radius,
                                alt = alt,
                                openTime = openTime,
                                closeTime = closeTime,
                                taskId = realTaskId
                            )
                        } else null
                    } else null
                }

                if (waypoints.isNotEmpty()) {
                    return Task(
                        id = realTaskId,
                        name = taskName,
                        waypoints = waypoints,
                        isVisible = isVisible
                    )
                }
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error reconstructing task from features: ${e.message}")
            return null
        }
    }

    /**
     * Convert task to overlay features for spatial indexing
     */
    private fun convertTaskToFeatures(task: Task): List<MapOverlayCacheUtils.OverlayFeature> {
        if (task.waypoints.isEmpty()) return emptyList()

        // Create a single LineString feature for the entire task
        val coordinates = task.waypoints.map { listOf(it.lon, it.lat) }
        
        // Serialize waypoints metadata
        val waypointsMetadata = task.waypoints.map { wp ->
            mapOf(
                "id" to wp.id,
                "lat" to wp.lat,
                "lon" to wp.lon,
                "type" to wp.type.name,
                "label" to (wp.label ?: ""),
                "radius" to (wp.radius ?: TaskConstants.FAI_DEFAULT_RADIUS_METERS),
                "alt" to (wp.alt ?: 0.0),
                "openTime" to (wp.openTime ?: ""),
                "closeTime" to (wp.closeTime ?: "")
            )
        }

        val featureData = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "LineString",
                "coordinates" to coordinates
            ),
            "properties" to mapOf(
                "taskId" to task.id,
                "taskName" to task.name,
                "isVisible" to task.isVisible,
                "waypoints" to waypointsMetadata
            )
        )

        // Compute centroid of the LineString for indexing
        @Suppress("UNCHECKED_CAST")
        val centroid = OverlayGeoJsonParser.computeCentroid(featureData["geometry"] as Map<String, Any>) 
            ?: GeoPoint(task.waypoints[0].lat, task.waypoints[0].lon) // Fallback to first point

        val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, HILBERT_BITS_PRECISION)

        return listOf(
            MapOverlayCacheUtils.OverlayFeature(
                internalId = task.id,
                feature = featureData,
                centroid = centroid,
                hilbertIndex = hilbertIndex,
                overlayType = "task"
            )
        )
    }
}
