package com.madanala.tern.ui.overlays

import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.redux.WaypointSelection
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

    companion object {
        // Rendering constants for consistent visual appearance
        private const val ROUTE_LINE_WIDTH = 8f
        private const val WAYPOINT_BORDER_WIDTH = 3f
        private const val SELECTION_HIGHLIGHT_WIDTH = 6f
        private const val SELECTION_HIGHLIGHT_PADDING = 8f
        private const val ROUTE_SELECTION_HIGHLIGHT_WIDTH = 12f
        private const val LABEL_TEXT_SIZE = 32f
        private const val LABEL_OFFSET_Y = 10f

        // Waypoint sizes for different route types
        private const val SINGLE_WAYPOINT_RADIUS = 20f
        private const val MULTI_WAYPOINT_RADIUS = 12f

        // Touch interaction constants
        private const val SINGLE_WAYPOINT_TAP_RADIUS = 60f
        private const val MULTI_WAYPOINT_TAP_RADIUS = 40f

        // Waypoint type rendering constants
        private const val LAUNCH_TRIANGLE_SIZE_RATIO = 1.0f
        private const val LANDING_SQUARE_SIZE_RATIO = 1.0f
    }

    private var routeCache: RouteCache? = null
    private var currentRoutes: List<Route> = emptyList()
    private var currentSelectedWaypoint: WaypointSelection? = null
    private var currentSelectedRouteId: String? = null
    private val routeOverlays = mutableListOf<RouteOverlay>()

    // Overlay coordinator for Hilbert ordering and lifecycle management
    private var overlayCoordinator: OverlayCoordinator? = null

    // Paint objects for route rendering
    private val routePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = ROUTE_LINE_WIDTH
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
        strokeWidth = WAYPOINT_BORDER_WIDTH
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.BLACK
        textSize = LABEL_TEXT_SIZE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val selectionPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = SELECTION_HIGHLIGHT_WIDTH
        isAntiAlias = true
    }

    private val dragPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = SELECTION_HIGHLIGHT_WIDTH
        isAntiAlias = true
    }

    private val routeSelectionPaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.STROKE
        strokeWidth = ROUTE_SELECTION_HIGHLIGHT_WIDTH
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
        // Routes will be loaded via Redux state changes
    }

    override fun setOverlayCoordinator(coordinator: OverlayCoordinator) {
        this.overlayCoordinator = coordinator
        Log.d(TAG, "RouteOverlayManager connected to OverlayCoordinator for lifecycle management")
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "Route overlay manager detached")
        clearRouteOverlays()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        // Map movement handled by Redux state changes
        // Route display is determined by Redux state, not spatial queries

        // Notify overlay coordinator of map movement for distance-based zoning
        overlayCoordinator?.onMapMoved(center.latitude, center.longitude, zoom)
    }

    override fun onViewportChangedInternal(viewport: org.osmdroid.util.BoundingBox) {
        // Viewport changes handled by Redux state changes
        // Route display is determined by Redux state, not spatial queries

        // Notify overlay coordinator of viewport changes for memory management
        overlayCoordinator?.onViewportChanged(viewport)
    }

    override fun onReduxStateChanged(state: MapState) {
        try {
            Log.d(TAG, "Redux state changed - routes enabled: ${state.overlayState.routes.enabled}, route count: ${state.routes.size}, selected waypoint: ${state.selectedWaypoint}, selected route: ${state.selectedRouteId}")

            val config = state.overlayState.routes

            if (config.enabled) {
                // Single source of truth: Only display routes from Redux state
                if (state.routes != currentRoutes || state.selectedWaypoint != currentSelectedWaypoint || state.selectedRouteId != currentSelectedRouteId) {
                    currentRoutes = state.routes
                    currentSelectedWaypoint = state.selectedWaypoint
                    currentSelectedRouteId = state.selectedRouteId
                    updateRouteOverlays()
                    Log.d(TAG, "Routes updated from Redux state: ${currentRoutes.size} routes, selected waypoint: ${currentSelectedWaypoint}, selected route: ${currentSelectedRouteId}")

                    // Persistence as side effect: Cache routes for app restart recovery
                    currentRoutes.forEach { route ->
                        try {
                            if (routeCache?.isCached(route.id) != true) {
                                routeCache?.cacheRoute(route)
                                Log.d(TAG, "Persisted route to cache: ${route.id}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to cache route ${route.id}", e)
                        }
                    }
                }
            } else {
                // Clear routes when disabled
                clearRouteOverlays()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Redux state change in RouteOverlayManager", e)
        }
    }

    override fun clearOverlays() {
        clearRouteOverlays()
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
     * Enhanced performance statistics for RouteOverlayManager
     */
    override fun getPerformanceStats(): Map<String, Any> {
        val baseStats = super.getPerformanceStats().toMutableMap()
        val cacheStats = routeCache?.getCacheStats() ?: emptyMap()

        baseStats.putAll(cacheStats)
        baseStats["route_overlays_active"] = routeOverlays.size
        baseStats["current_routes_count"] = currentRoutes.size
        baseStats["has_selected_waypoint"] = currentSelectedWaypoint != null
        baseStats["has_selected_route"] = currentSelectedRouteId != null
        baseStats["overlay_coordinator_connected"] = overlayCoordinator != null

        return baseStats
    }

    /**
     * Inner class for individual route overlay rendering and interaction
     */
    private inner class RouteOverlay(private val route: Route) : Overlay() {

        override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
            if (e == null || mapView == null) return false

            // Handle waypoint selection logic
            return handleWaypointTap(e, mapView)
        }

        /**
         * Handle waypoint tap selection with improved logic
         */
        private fun handleWaypointTap(e: MotionEvent, mapView: MapView): Boolean {
            val projection = mapView.projection

            // Find tapped waypoint using spatial distance calculation
            val tappedWaypoint = findTappedWaypoint(e, projection)

            return if (tappedWaypoint != null) {
                handleWaypointSelection(tappedWaypoint)
                true // Consume the tap
            } else {
                // Tap not on waypoint - deselect if something was selected
                handleTapOutsideWaypoint()
            }
        }

        /**
         * Find waypoint under tap location using screen distance
         */
        private fun findTappedWaypoint(e: MotionEvent, projection: org.osmdroid.views.Projection): com.madanala.tern.model.Waypoint? {
            route.waypoints.forEach { waypoint ->
                val waypointPoint = GeoPoint(waypoint.lat, waypoint.lon)
                val screenPoint = projection.toPixels(waypointPoint, null)

                val screenDistance = calculateScreenDistance(e.x, e.y, screenPoint.x.toFloat(), screenPoint.y.toFloat())
                val tapRadius = getTapRadiusForRoute()

                if (screenDistance <= tapRadius) {
                    return waypoint
                }
            }
            return null
        }

        /**
         * Calculate Euclidean distance between two screen points
         */
        private fun calculateScreenDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        /**
         * Get appropriate tap radius based on route type
         */
        private fun getTapRadiusForRoute(): Float {
            return if (route.waypoints.size == 1) SINGLE_WAYPOINT_TAP_RADIUS else MULTI_WAYPOINT_TAP_RADIUS
        }

        /**
         * Handle waypoint selection/deselection logic
         */
        private fun handleWaypointSelection(waypoint: com.madanala.tern.model.Waypoint) {
            val currentSelection = currentSelectedWaypoint

            if (isSameWaypointSelected(currentSelection, waypoint)) {
                // Same waypoint tapped - deselect
                mapStore?.dispatch(com.madanala.tern.redux.MapAction.DeselectWaypoint)
                Log.d(TAG, "Deselected waypoint: ${waypoint.id}")
            } else {
                // Different waypoint tapped - select it
                mapStore?.dispatch(com.madanala.tern.redux.MapAction.SelectWaypoint(route.id, waypoint.id))
                Log.d(TAG, "Selected waypoint: ${waypoint.id} in route: ${route.id}")
            }
        }

        /**
         * Check if the same waypoint is already selected
         */
        private fun isSameWaypointSelected(selection: WaypointSelection?, waypoint: com.madanala.tern.model.Waypoint): Boolean {
            return selection?.routeId == route.id && selection.waypointId == waypoint.id
        }

        /**
         * Handle tap outside any waypoint
         */
        private fun handleTapOutsideWaypoint(): Boolean {
            return if (currentSelectedWaypoint != null) {
                mapStore?.dispatch(com.madanala.tern.redux.MapAction.DeselectWaypoint)
                Log.d(TAG, "Deselected waypoint (tap elsewhere)")
                true
            } else {
                false // Let other overlays handle the tap
            }
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            try {
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

                    // Check if this route is selected and draw highlight first
                    if (currentSelectedRouteId == route.id) {
                        canvas.drawPath(path, routeSelectionPaint)
                    }

                    canvas.drawPath(path, routePaint)
                }

                // Draw waypoints
                route.waypoints.forEach { waypoint ->
                    try {
                        val point = GeoPoint(waypoint.lat, waypoint.lon)
                        val screenPoint = projection.toPixels(point, null)

                        // Use larger radius for single-waypoint routes to make them more visible
                        val radius = if (route.waypoints.size == 1) SINGLE_WAYPOINT_RADIUS else MULTI_WAYPOINT_RADIUS

                        // Draw waypoint shape based on type
                        when (waypoint.type) {
                            com.madanala.tern.model.Waypoint.Type.LAUNCH -> {
                                // Draw triangle for launch
                                waypointBorderPaint.color = Color.GREEN
                                val trianglePath = Path()
                                val triangleSize = radius * LAUNCH_TRIANGLE_SIZE_RATIO
                                trianglePath.moveTo(screenPoint.x.toFloat(), screenPoint.y - triangleSize)
                                trianglePath.lineTo(screenPoint.x - triangleSize, screenPoint.y + triangleSize)
                                trianglePath.lineTo(screenPoint.x + triangleSize, screenPoint.y + triangleSize)
                                trianglePath.close()
                                canvas.drawPath(trianglePath, waypointPaint) // White fill
                                canvas.drawPath(trianglePath, waypointBorderPaint) // Green border
                            }
                            com.madanala.tern.model.Waypoint.Type.LANDING -> {
                                // Draw square for landing
                                waypointBorderPaint.color = Color.RED
                                val halfSize = radius * LANDING_SQUARE_SIZE_RATIO
                                val rect = RectF(
                                    screenPoint.x - halfSize, screenPoint.y - halfSize,
                                    screenPoint.x + halfSize, screenPoint.y + halfSize
                                )
                                canvas.drawRect(rect, waypointPaint) // White fill
                                canvas.drawRect(rect, waypointBorderPaint) // Red border
                            }
                            else -> {
                                // Draw circle for turnpoints
                                waypointBorderPaint.color = Color.BLUE
                                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, waypointPaint) // White fill
                                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, waypointBorderPaint) // Blue border
                            }
                        }

                        // Draw selection highlight if this waypoint is selected
                        currentSelectedWaypoint?.let { selection ->
                            if (selection.routeId == route.id && selection.waypointId == waypoint.id) {
                                val paint = if (selection.isDragging) dragPaint else selectionPaint
                                val highlightRadius = radius + SELECTION_HIGHLIGHT_PADDING
                                when (waypoint.type) {
                                    com.madanala.tern.model.Waypoint.Type.LAUNCH -> {
                                        // Draw triangular highlight for launch
                                        val trianglePath = Path()
                                        val triangleSize = highlightRadius
                                        trianglePath.moveTo(screenPoint.x.toFloat(), screenPoint.y - triangleSize)
                                        trianglePath.lineTo(screenPoint.x - triangleSize, screenPoint.y + triangleSize)
                                        trianglePath.lineTo(screenPoint.x + triangleSize, screenPoint.y + triangleSize)
                                        trianglePath.close()
                                        canvas.drawPath(trianglePath, paint)
                                    }
                                    com.madanala.tern.model.Waypoint.Type.LANDING -> {
                                        // Draw square highlight for landing
                                        val halfSize = highlightRadius
                                        canvas.drawRect(
                                            screenPoint.x - halfSize, screenPoint.y - halfSize,
                                            screenPoint.x + halfSize, screenPoint.y + halfSize,
                                            paint
                                        )
                                    }
                                    else -> {
                                        // Draw circular highlight for turnpoints
                                        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), highlightRadius, paint)
                                    }
                                }
                            }
                        }

                        // Draw label if present
                        waypoint.label?.let { label ->
                            canvas.drawText(label, screenPoint.x.toFloat(), screenPoint.y - radius - LABEL_OFFSET_Y, labelPaint)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error drawing waypoint ${waypoint.id} in route ${route.id}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing route overlay for route ${route.id}", e)
            }
        }
    }
}
