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
class RouteCache(
    context: Context,
    private val diskCache: SpatialDiskCache = SpatialDiskCache(context, "route", ROUTE_CACHE_HOURS)
) {

    companion object {
        const val ROUTE_CACHE_HOURS = 168  // 7 days
        private const val TAG = "RouteCache"
        private const val HILBERT_BITS_PRECISION = 32
        private const val MAX_DISTANCE_METERS_PER_MILE = 1609.34
    }

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
                
                // Use in-memory filtering to bypass potential SpatialDiskCache issues
                val allFeatures = diskCache.getCachedFeatures(routeId)
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
                        // If any waypoint is nearby, load the full route
                        val route = reconstructRouteFromFeatures(routeId, allFeatures)
                        if (route != null) {
                            nearbyRoutes.add(route)
                        }
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

            // Expect a single LineString feature containing all route data
            val feature = features.first()
            val properties = feature.feature["properties"] as? Map<*, *>
            val routeName = properties?.get("routeName") as? String ?: "Reconstructed Route"
            val isVisible = properties?.get("isVisible") as? Boolean ?: true
            
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
                                Waypoint.Type.valueOf(typeStr ?: "TURNPOINT")
                            } catch (e: Exception) {
                                Waypoint.Type.TURNPOINT
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
                                routeId = routeId
                            )
                        } else null
                    } else null
                }
                
                if (waypoints.isNotEmpty()) {
                    return Route(
                        id = routeId,
                        name = routeName,
                        waypoints = waypoints,
                        isVisible = isVisible
                    )
                }
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
        if (route.waypoints.isEmpty()) return emptyList()

        // Create a single LineString feature for the entire route
        val coordinates = route.waypoints.map { listOf(it.lon, it.lat) }
        
        // Serialize waypoints metadata
        val waypointsMetadata = route.waypoints.map { wp ->
            mapOf(
                "id" to wp.id,
                "lat" to wp.lat,
                "lon" to wp.lon,
                "type" to wp.type.name,
                "label" to (wp.label ?: ""),
                "radius" to (wp.radius ?: RouteConstants.FAI_DEFAULT_RADIUS_METERS),
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
                "routeId" to route.id,
                "routeName" to route.name,
                "isVisible" to route.isVisible,
                "waypoints" to waypointsMetadata
            )
        )

        // Compute centroid of the LineString for indexing
        @Suppress("UNCHECKED_CAST")
        val centroid = MapOverlayCacheUtils.computeCentroid(featureData["geometry"] as Map<String, Any>) 
            ?: GeoPoint(route.waypoints[0].lat, route.waypoints[0].lon) // Fallback to first point

        val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, HILBERT_BITS_PRECISION)

        return listOf(
            MapOverlayCacheUtils.OverlayFeature(
                id = route.id,
                feature = featureData,
                centroid = centroid,
                hilbertIndex = hilbertIndex,
                overlayType = "route"
            )
        )
    }
}
