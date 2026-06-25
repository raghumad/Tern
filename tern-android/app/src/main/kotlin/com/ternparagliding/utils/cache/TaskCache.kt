package com.ternparagliding.utils.cache

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ternparagliding.model.Task
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
    private val diskCache: SpatialDiskCache = SpatialDiskCache(context, "task", TASK_CACHE_HOURS)
) {

    companion object {
        const val TASK_CACHE_HOURS = Int.MAX_VALUE  // Permanent (Never automatically expire user-created tasks)
        private const val TAG = "TaskCache"
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
    // The pure Task <-> persisted-feature transforms live in TaskCacheCodec
    // (Context-free, so the round-trip is unit-testable).

    private fun reconstructTaskFromFeatures(taskId: String, features: List<MapOverlayCacheUtils.OverlayFeature>): Task? =
        TaskCacheCodec.reconstructTaskFromFeatures(taskId, features)

    private fun convertTaskToFeatures(task: Task): List<MapOverlayCacheUtils.OverlayFeature> =
        TaskCacheCodec.convertTaskToFeatures(task)
}
