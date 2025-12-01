package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.madanala.tern.model.Route
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
class RouteCache(context: Context) {

    companion object {
        const val ROUTE_CACHE_HOURS = 168  // 7 days
        private const val TAG = "RouteCache"
        private const val HILBERT_BITS_PRECISION = 32
        private const val MAX_DISTANCE_METERS_PER_MILE = 1609.34
    }

    // Delegate storage and indexing to generic SpatialDiskCache
    private val diskCache = SpatialDiskCache(context, "route", ROUTE_CACHE_HOURS)

    /**
     * Check if route data is cached and not too old
     */
    fun isCached(routeId: String): Boolean {
        return diskCache.isCached(routeId)
    }

    /**
     * Get cached route
     */
    fun getCachedRoute(routeId: String): Route? {
        val features = diskCache.getCachedFeatures(routeId) ?: return null
        if (features.isEmpty()) return null

        return reconstructRouteFromFeatures(routeId, features)
    }

    /**
     * Cache route data using FlexBuffers + Hilbert spatial indexing
     */
    fun cacheRoute(route: Route) {
        try {
            if (route.waypoints.isEmpty()) {
                Log.w(TAG, "No waypoints to cache for route ${route.id}")
                return
            }

            // Convert route to overlay features for spatial indexing
            val routeFeatures = convertRouteToFeatures(route)

            // Delegate caching to SpatialDiskCache
            diskCache.cacheFeatures(route.id, routeFeatures)

            Log.d(TAG, "Cached route ${route.id} with ${route.waypoints.size} waypoints")

        } catch (e: Exception) {
            Log.e(TAG, "Error caching route ${route.id}", e)
        }
    }

    /**
     * Query routes within distance of center point
     */
    fun queryNearbyRoutes(center: GeoPoint, maxDistanceMiles: Double, maxRoutes: Int = 10): List<Route> {
        try {
            val nearbyRoutes = mutableListOf<Route>()
            val routeIds = diskCache.cacheIndex.keys.toList()

            for (routeId in routeIds) {
                if (nearbyRoutes.size >= maxRoutes) break

                // For routes, we check if *any* waypoint is nearby.
                // SpatialDiskCache.queryNearby returns features (waypoints), not whole routes.
                // So we can use queryNearby to find relevant route IDs, OR just iterate cached routes.
                // Given routes are "long" objects, spatial query on waypoints is better.
                
                val nearbyWaypoints = diskCache.queryNearby(routeId, center, maxDistanceMiles)
                if (nearbyWaypoints.isNotEmpty()) {
                    // If any waypoint is nearby, load the full route
                    val route = getCachedRoute(routeId)
                    if (route != null) {
                        nearbyRoutes.add(route)
                    }
                }
            }

            Log.d(TAG, "Found ${nearbyRoutes.size} routes within ${maxDistanceMiles} miles")
            return nearbyRoutes

        } catch (e: Exception) {
            Log.e(TAG, "Error querying nearby routes", e)
            return emptyList()
        }
    }

    /**
     * Clear cached data for a specific route
     */
    fun clearCacheForRoute(routeId: String) {
        diskCache.clearCacheForRegion(routeId)
    }

    /**
     * Clear all cached route data
     */
    fun clearCache() {
        diskCache.clearAll()
    }

    /**
     * Get all cached routes
     */
    fun getAllCachedRoutes(): List<Route> {
        val cachedRoutes = mutableListOf<Route>()
        val routeIds = diskCache.cacheIndex.keys.toList()

        for (routeId in routeIds) {
            val route = getCachedRoute(routeId)
            if (route != null) {
                cachedRoutes.add(route)
            }
        }
        return cachedRoutes
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): Map<String, Any> {
        return diskCache.getStats().mapKeys { 
            if (it.key == "cacheName") "type" else "route${it.key.replaceFirstChar { c -> c.uppercase() }}" 
        }
    }

    // ================= PRIVATE HELPERS =================

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
            val isVisible = properties?.get("isVisible") as? Boolean ?: true

            // Extract waypoints from features
            val waypoints = features.mapNotNull { feature ->
                val props = feature.feature["properties"] as? Map<*, *>
                val waypointId = props?.get("waypointId") as? String
                val waypointTypeStr = (props?.get("waypointType") as? String)?.takeIf { it.isNotEmpty() } ?: "TURNPOINT"
                val label = props?.get("label") as? String

                if (waypointId != null) {
                    val waypointType = try {
                        Waypoint.Type.valueOf(waypointTypeStr)
                    } catch (e: Exception) {
                        Waypoint.Type.TURNPOINT
                    }
                    
                    val sequence = (props?.get("sequence") as? Number)?.toInt() ?: 0

                    Waypoint(
                        id = waypointId,
                        lat = feature.centroid.latitude,
                        lon = feature.centroid.longitude,
                        type = waypointType,
                        label = if (label.isNullOrEmpty()) null else label,
                        routeId = routeId
                    ) to sequence
                } else {
                    null
                }
            }.sortedBy { (_, sequence) ->
                sequence
            }.map { it.first }

            if (waypoints.isNotEmpty()) {
                return Route(
                    id = routeId,
                    name = routeName,
                    waypoints = waypoints,
                    isVisible = isVisible
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
            val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, HILBERT_BITS_PRECISION)

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
                    "routeName" to route.name,
                    "isVisible" to route.isVisible,
                    "sequence" to route.waypoints.indexOf(waypoint)
                )
            )

            MapOverlayCacheUtils.OverlayFeature(featureData, centroid, hilbertIndex, "route")
        }
    }
}
