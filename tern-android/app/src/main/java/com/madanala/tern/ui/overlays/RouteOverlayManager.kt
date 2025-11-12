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
        private const val LABEL_TEXT_SIZE = 32f
        private const val LABEL_OFFSET_Y = 10f

        // Waypoint sizes for different route types
        private const val SINGLE_WAYPOINT_RADIUS = 20f
        private const val MULTI_WAYPOINT_RADIUS = 12f

        // Touch interaction constants
        private const val SINGLE_WAYPOINT_TAP_RADIUS = 60f
        private const val MULTI_WAYPOINT_TAP_RADIUS = 40f

        // Waypoint type rendering constants
        private const val LAUNCH_TRIANGLE_SIZE_RATIO = 0.7f
        private const val LANDING_SQUARE_SIZE_RATIO = 0.7f
    }

    private var routeCache: RouteCache? = null
    private var currentRoutes: List<Route> = emptyList()
    private var currentSelectedWaypoint: WaypointSelection? = null
    private val routeOverlays = mutableListOf<RouteOverlay>()

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

    override fun onOverlayDetached() {
        Log.d(TAG, "Route overlay manager detached")
        clearRouteOverlays()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        // Map movement handled by Redux state changes
        // Route display is determined by Redux state, not spatial queries
    }

    override fun onViewportChangedInternal(viewport: org.osmdroid.util.BoundingBox) {
        // Viewport changes handled by Redux state changes
        // Route display is determined by Redux state, not spatial queries
    }

    override fun onReduxStateChanged(state: MapState) {
        Log.d(TAG, "Redux state changed - routes enabled: ${state.overlayState.routes.enabled}, route count: ${state.routes.size}, selected: ${state.selectedWaypoint}")

        val config = state.overlayState.routes

        if (config.enabled) {
            // Single source of truth: Only display routes from Redux state
            if (state.routes != currentRoutes || state.selectedWaypoint != currentSelectedWaypoint) {
                currentRoutes = state.routes
                currentSelectedWaypoint = state.selectedWaypoint
                updateRouteOverlays()
                Log.d(TAG, "Routes updated from Redux state: ${currentRoutes.size} routes, selected: ${currentSelectedWaypoint}")

                // Persistence as side effect: Cache routes for app restart recovery
                currentRoutes.forEach { route ->
                    if (routeCache?.isCached(route.id) != true) {
                        routeCache?.cacheRoute(route)
                        Log.d(TAG, "Persisted route to cache: ${route.id}")
                    }
                }
            }
        } else {
            // Clear routes when disabled
            clearRouteOverlays()
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
     * Inner class for individual route overlay rendering and interaction
     */
    private inner class RouteOverlay(private val route: Route) : Overlay() {

        override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
            if (e == null || mapView == null) return false

            val projection = mapView.projection

            // Check if tap is on a waypoint (using screen pixel distance)
            route.waypoints.forEach { waypoint ->
                val waypointPoint = GeoPoint(waypoint.lat, waypoint.lon)
                val screenPoint = projection.toPixels(waypointPoint, null)

                // Calculate screen distance from tap to waypoint
                val dx = e.x - screenPoint.x
                val dy = e.y - screenPoint.y
                val screenDistance = kotlin.math.sqrt(dx * dx + dy * dy)

                // Use larger tap radius for single-waypoint routes (in pixels)
                val tapRadius = if (route.waypoints.size == 1) SINGLE_WAYPOINT_TAP_RADIUS else MULTI_WAYPOINT_TAP_RADIUS

                if (screenDistance <= tapRadius) {
                    // Waypoint tapped - dispatch selection action
                    val currentSelection = currentSelectedWaypoint

                    if (currentSelection?.routeId == route.id && currentSelection.waypointId == waypoint.id) {
                        // Same waypoint tapped - deselect
                        mapStore?.dispatch(com.madanala.tern.redux.MapAction.DeselectWaypoint)
                        Log.d(TAG, "Deselected waypoint: ${waypoint.id}")
                    } else {
                        // Different waypoint tapped - select it
                        mapStore?.dispatch(com.madanala.tern.redux.MapAction.SelectWaypoint(route.id, waypoint.id))
                        Log.d(TAG, "Selected waypoint: ${waypoint.id} in route: ${route.id}")
                    }

                    return true // Consume the tap
                }
            }

            // Tap not on waypoint - deselect if something was selected
            if (currentSelectedWaypoint != null) {
                mapStore?.dispatch(com.madanala.tern.redux.MapAction.DeselectWaypoint)
                Log.d(TAG, "Deselected waypoint (tap elsewhere)")
                return true
            }

            return false // Let other overlays handle the tap
        }

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
                val radius = if (route.waypoints.size == 1) SINGLE_WAYPOINT_RADIUS else MULTI_WAYPOINT_RADIUS

                // Draw waypoint circle with white fill
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, waypointPaint)

                // Draw colored border based on waypoint type
                when (waypoint.type) {
                    com.madanala.tern.model.Waypoint.Type.LAUNCH -> {
                        waypointBorderPaint.color = Color.GREEN
                        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, waypointBorderPaint)

                        // Draw triangle for launch
                        val trianglePath = Path()
                        val triangleSize = radius * LAUNCH_TRIANGLE_SIZE_RATIO
                        trianglePath.moveTo(screenPoint.x.toFloat(), screenPoint.y - triangleSize)
                        trianglePath.lineTo(screenPoint.x - triangleSize, screenPoint.y + triangleSize)
                        trianglePath.lineTo(screenPoint.x + triangleSize, screenPoint.y + triangleSize)
                        trianglePath.close()
                        canvas.drawPath(trianglePath, waypointBorderPaint)
                    }
                    com.madanala.tern.model.Waypoint.Type.LANDING -> {
                        waypointBorderPaint.color = Color.RED
                        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, waypointBorderPaint)

                        // Draw square for landing
                        val halfSize = radius * LANDING_SQUARE_SIZE_RATIO
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

                // Draw selection highlight if this waypoint is selected
                currentSelectedWaypoint?.let { selection ->
                    if (selection.routeId == route.id && selection.waypointId == waypoint.id) {
                        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius + SELECTION_HIGHLIGHT_PADDING, selectionPaint)
                    }
                }

                // Draw label if present
                waypoint.label?.let { label ->
                    canvas.drawText(label, screenPoint.x.toFloat(), screenPoint.y - radius - LABEL_OFFSET_Y, labelPaint)
                }
            }
        }
    }
}
