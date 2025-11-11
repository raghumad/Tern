package com.madanala.tern.ui.overlays

import android.graphics.*
import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.route.Route
import com.madanala.tern.utils.RouteCache
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Overlay manager for route visualization
 * Extends BaseOverlayManager for Redux integration and memory management
 */
class RouteOverlayManager(
    mapStore: MapStore?
) : BaseOverlayManager(OverlayType.ROUTES, mapStore) {

    private var routeCache: RouteCache? = null
    private var currentRoutes: List<Route> = emptyList()
    private val routeOverlays = mutableListOf<RouteOverlay>()

    // Paint objects for route rendering
    private val routePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val waypointPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val waypointBorderPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.BLACK
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    init {
        // Initialize route cache
        mapView?.context?.let { context ->
            routeCache = RouteCache(context)
        }
    }

    override fun onOverlayAttached() {
        Log.d(TAG, "Route overlay manager attached")
        // Load initial routes if any
        loadNearbyRoutes()
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "Route overlay manager detached")
        clearRouteOverlays()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        // Reload routes when map moves significantly
        loadNearbyRoutes()
    }

    override fun onViewportChangedInternal(viewport: org.osmdroid.util.BoundingBox) {
        // Debounced viewport changes handled by base class
        loadNearbyRoutes()
    }

    override fun onReduxStateChanged(state: MapState) {
        Log.d(TAG, "Redux state changed - routes enabled: ${state.overlayState.routes.enabled}, route count: ${state.routes.size}")

        val config = state.overlayState.routes

        if (config.enabled) {
            // Check if routes have changed in Redux state
            if (state.routes != currentRoutes) {
                currentRoutes = state.routes
                updateRouteOverlays()
                Log.d(TAG, "Routes updated from Redux state: ${currentRoutes.size} routes")

                // Also cache any new routes
                currentRoutes.forEach { route ->
                    if (routeCache?.isCached(route.id) != true) {
                        routeCache?.cacheRoute(route)
                        Log.d(TAG, "Cached new route: ${route.id}")
                    }
                }
            }

            // Also load nearby routes from cache (for routes not in Redux state)
            loadNearbyRoutes()
        } else {
            // Clear routes when disabled
            clearRouteOverlays()
        }
    }

    override fun clearOverlays() {
        clearRouteOverlays()
    }

    /**
     * Load routes near the current map center
     */
    private fun loadNearbyRoutes() {
        if (!isEnabled()) return

        val mapView = mapView ?: return
        val routeCache = routeCache ?: return

        try {
            val center = GeoPoint(mapView.mapCenter)
            val zoom = mapView.zoomLevelDouble

            // Calculate search radius based on zoom level
            // Higher zoom = smaller search radius for better performance
            val searchRadiusMiles = when {
                zoom > 12 -> 50.0   // Detailed view
                zoom > 10 -> 150.0  // Regional view
                zoom > 8 -> 300.0   // Continental view
                else -> 500.0       // Global view
            }

            // Query nearby routes (max 10 as per requirements)
            val nearbyRoutes = routeCache.queryNearbyRoutes(center, searchRadiusMiles, 10)

            if (nearbyRoutes != currentRoutes) {
                currentRoutes = nearbyRoutes
                updateRouteOverlays()
                Log.d(TAG, "Loaded ${nearbyRoutes.size} routes within ${searchRadiusMiles} miles")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading nearby routes", e)
        }
    }

    /**
     * Update route overlays on map
     */
    private fun updateRouteOverlays() {
        val mapView = mapView ?: return

        // Clear existing overlays
        clearRouteOverlays()

        // Create new overlays for current routes
        currentRoutes.forEach { route ->
            val routeOverlay = RouteOverlay(route)
            routeOverlays.add(routeOverlay)
            mapView.overlays.add(routeOverlay)
        }

        // Force map redraw
        mapView.invalidate()
    }

    /**
     * Clear all route overlays
     */
    private fun clearRouteOverlays() {
        val mapView = mapView ?: return

        routeOverlays.forEach { overlay ->
            mapView.overlays.remove(overlay)
        }
        routeOverlays.clear()
        mapView.invalidate()
    }

    /**
     * Add a new route to the cache and display
     */
    fun addRoute(route: Route) {
        try {
            routeCache?.cacheRoute(route)

            // Reload routes to include the new one
            loadNearbyRoutes()

            Log.d(TAG, "Added route: ${route.name} with ${route.waypoints.size} waypoints")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding route", e)
        }
    }

    /**
     * Remove a route from cache and display
     */
    fun removeRoute(routeId: String) {
        try {
            routeCache?.clearCacheForRoute(routeId)

            // Reload routes to reflect removal
            loadNearbyRoutes()

            Log.d(TAG, "Removed route: $routeId")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing route", e)
        }
    }

    /**
     * Get current routes
     */
    fun getCurrentRoutes(): List<Route> = currentRoutes.toList()

    /**
     * Get route cache statistics
     */
    fun getRouteCacheStats(): Map<String, Any> {
        return routeCache?.getCacheStats() ?: emptyMap()
    }

    /**
     * Inner class for individual route overlay rendering
     */
    private inner class RouteOverlay(private val route: Route) : Overlay() {

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return

            val projection = mapView.projection

            // Draw route line
            if (route.waypoints.size >= 2) {
                val path = Path()

                route.waypoints.forEachIndexed { index, waypoint ->
                    val point = GeoPoint(waypoint.lat, waypoint.lon)
                    val screenPoint = projection.toPixels(point, null)

                    if (index == 0) {
                        path.moveTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
                    } else {
                        path.lineTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
                    }
                }

                canvas.drawPath(path, routePaint)
            }

            // Draw waypoints
            route.waypoints.forEach { waypoint ->
                val point = GeoPoint(waypoint.lat, waypoint.lon)
                val screenPoint = projection.toPixels(point, null)

                // Use larger radius for single-waypoint routes to make them more visible
                val radius = if (route.waypoints.size == 1) 20f else 12f

                // Draw waypoint circle with white fill
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, waypointPaint)

                // Draw colored border based on waypoint type
                when (waypoint.type) {
                    com.madanala.tern.model.Waypoint.Type.LAUNCH -> {
                        waypointBorderPaint.color = Color.GREEN
                        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, waypointBorderPaint)

                        // Draw triangle for launch
                        val trianglePath = Path()
                        trianglePath.moveTo(screenPoint.x.toFloat(), screenPoint.y - radius)
                        trianglePath.lineTo(screenPoint.x - radius, screenPoint.y + radius)
                        trianglePath.lineTo(screenPoint.x + radius, screenPoint.y + radius)
                        trianglePath.close()
                        canvas.drawPath(trianglePath, waypointBorderPaint)
                    }
                    com.madanala.tern.model.Waypoint.Type.LANDING -> {
                        waypointBorderPaint.color = Color.RED
                        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, waypointBorderPaint)

                        // Draw square for landing
                        val halfSize = radius * 0.7f
                        canvas.drawRect(
                            screenPoint.x - halfSize, screenPoint.y - halfSize,
                            screenPoint.x + halfSize, screenPoint.y + halfSize,
                            waypointBorderPaint
                        )
                    }
                    else -> {
                        // Blue border for turnpoints
                        waypointBorderPaint.color = Color.BLUE
                        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, waypointBorderPaint)
                    }
                }

                // Draw label if present
                waypoint.label?.let { label ->
                    canvas.drawText(label, screenPoint.x.toFloat(), screenPoint.y - radius - 10, labelPaint)
                }
            }
        }
    }
}
