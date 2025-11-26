package com.madanala.tern.ui.overlays

import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.redux.WaypointSelection
import com.madanala.tern.model.Route
import com.madanala.tern.utils.RouteCache
import com.madanala.tern.utils.CacheManager
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Overlay manager for route visualization
 * Extends BaseOverlayManager for Redux integration and memory management
 */
class RouteOverlayManager(
    context: android.content.Context,
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

    init {
        try {
            // Ensure CacheManager is initialized
            if (!isCacheManagerInitialized()) {
                CacheManager.initialize(context.applicationContext)
            }
            routeCache = CacheManager.routeCache
            Log.d(TAG, "RouteCache initialized successfully from CacheManager in constructor")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RouteCache in constructor", e)
        }
    }
    
    // Helper to check if CacheManager is initialized (reflection-based check or try-catch)
    private fun isCacheManagerInitialized(): Boolean {
        return try {
            CacheManager.airspaceCache // Accessing any lazy property triggers check
            true
        } catch (e: IllegalStateException) {
            false
        }
    }
    private var currentRoutes: List<Route> = emptyList()
    private var currentSelectedWaypoint: WaypointSelection? = null
    private var currentSelectedRouteId: String? = null
    // Changed to Map for better tracking and incremental updates
    private val currentlyRenderedRoutes = mutableMapOf<String, RouteOverlay>()

    // Overlay coordinator for Hilbert ordering and lifecycle management
    private var overlayCoordinator: OverlayCoordinator? = null

    // Paint objects for route rendering - lazy initialization to avoid Android mocking issues in tests
    private val routePaint by lazy {
        Paint().apply {
            color = Color.BLUE
            strokeWidth = ROUTE_LINE_WIDTH
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
    }

    private val waypointPaint by lazy {
        Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    private val waypointBorderPaint by lazy {
        Paint().apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = WAYPOINT_BORDER_WIDTH
            isAntiAlias = true
        }
    }

    private val labelPaint by lazy {
        Paint().apply {
            color = Color.BLACK
            textSize = LABEL_TEXT_SIZE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    private val selectionPaint by lazy {
        Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = SELECTION_HIGHLIGHT_WIDTH
            isAntiAlias = true
        }
    }

    private val dragPaint by lazy {
        Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = SELECTION_HIGHLIGHT_WIDTH
            isAntiAlias = true
        }
    }

    private val routeSelectionPaint by lazy {
        Paint().apply {
            color = Color.MAGENTA
            style = Paint.Style.STROKE
            strokeWidth = ROUTE_SELECTION_HIGHLIGHT_WIDTH
            isAntiAlias = true
        }
    }

    override fun onOverlayAttached() {
        Log.d(TAG, "Route overlay manager attached")
        
        // Force initial render of existing routes from Redux state
        mapStore?.state?.value?.let { currentState ->
            Log.d(TAG, "Forcing initial route render with ${currentState.routes.size} routes")
            onReduxStateChanged(currentState)
        }

        // Load persisted routes if Redux state is empty (initial launch)
        if (mapStore?.state?.value?.routes?.isEmpty() == true) {
            loadPersistedRoutes()
        }
    }

    /**
     * Load persisted routes from cache and populate Redux state
     */
    private fun loadPersistedRoutes() {
        try {
            val persistedRoutes = routeCache?.getAllCachedRoutes()

            if (!persistedRoutes.isNullOrEmpty()) {
                // Dispatch Redux actions to add each persisted route
                persistedRoutes.forEach { route ->
                    mapStore?.dispatch(com.madanala.tern.redux.MapAction.AddRoute(route))
                }
                Log.d(TAG, "Loaded ${persistedRoutes.size} persisted routes into Redux state")
            } else {
                Log.d(TAG, "No persisted routes found in cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading persisted routes", e)
        }
    }

    override fun setOverlayCoordinator(coordinator: OverlayCoordinator) {
        println("SYSTEM_OUT: RouteOverlayManager.setOverlayCoordinator called with $coordinator")
        this.overlayCoordinator = coordinator
        Log.d(TAG, "DEBUG: setOverlayCoordinator called. Coordinator: $coordinator")
        Log.d(TAG, "RouteOverlayManager connected to OverlayCoordinator for lifecycle management")
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "Route overlay manager detached")
        clearRouteOverlays()
    }

    override fun onReduxStateChanged(state: MapState) {
        try {
            // Log.d(TAG, "Redux state changed - routes enabled: ${state.overlayState.routes.enabled}")

            val config = state.overlayState.routes

            if (config.enabled) {
                // Single source of truth: Only display routes from Redux state
                if (state.routes != currentRoutes || state.selectedWaypoint != currentSelectedWaypoint || state.selectedRouteId != currentSelectedRouteId) {
                    
                    // Calculate differences for persistence
                    val newRouteIds = state.routes.map { it.id }.toSet()
                    val oldRouteIds = currentRoutes.map { it.id }.toSet()
                    val removedRouteIds = oldRouteIds - newRouteIds
                    
                    // Update local state
                    currentRoutes = state.routes
                    currentSelectedWaypoint = state.selectedWaypoint
                    currentSelectedRouteId = state.selectedRouteId
                    
                    updateRouteOverlays()
                    
                    // Handle removals from cache
                    removedRouteIds.forEach { routeId ->
                        try {
                            routeCache?.clearCacheForRoute(routeId)
                            Log.d(TAG, "Removed route $routeId from cache")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to remove route $routeId from cache", e)
                        }
                    }
                    
                    // Handle additions/updates to cache and overlays
                    currentRoutes.forEach { route ->
                        try {
                            // Update existing overlay if present
                            val existingOverlay = currentlyRenderedRoutes[route.id]
                            if (existingOverlay != null) {
                                if (existingOverlay.route != route) {
                                    existingOverlay.updateRoute(route)
                                    // Log.d(TAG, "Updated existing overlay for route ${route.id}")
                                }
                            } else {
                                // New route will be handled by updateRouteOverlays()
                            }

                            // Always update cache to ensure latest state (visibility, waypoints) is persisted
                            routeCache?.cacheRoute(route)
                            // Log.d(TAG, "Cached route ${route.id}: visible=${route.isVisible}, waypoints=${route.waypoints.size}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to cache/update route ${route.id}", e)
                        }
                    }
                }
            } else {
                clearRouteOverlays()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Redux state change in RouteOverlayManager", e)
        }
    }

    override fun clearOverlays() {
        clearRouteOverlays()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        // LOD Check
        if (!isZoomLevelSufficient(zoom)) {
            if (currentlyRenderedRoutes.isNotEmpty()) {
                clearRouteOverlays()
            }
            return
        }

        // Notify overlay coordinator of map movement for distance-based zoning
        overlayCoordinator?.onMapMoved(center.latitude, center.longitude, zoom)
        
        // Trigger update to respect new center for prioritization
        updateRouteOverlays()
    }

    override fun onViewportChangedInternal(viewport: org.osmdroid.util.BoundingBox) {
        // LOD Check
        if (mapView != null && !isZoomLevelSufficient(mapView!!.zoomLevelDouble)) {
            if (currentlyRenderedRoutes.isNotEmpty()) {
                clearRouteOverlays()
            }
            return
        }

        // Notify overlay coordinator of viewport changes for memory management
        overlayCoordinator?.onViewportChanged(viewport)
    }

    /**
     * Update route overlays on map with incremental updates and prioritization
     */
    private fun updateRouteOverlays() {
        val mapView = mapView ?: return
        val center = mapView.mapCenter as GeoPoint
        
        // LOD Check
        if (!isZoomLevelSufficient(mapView.zoomLevelDouble)) {
            clearRouteOverlays()
            return
        }

        // 🎯 STEP 0: Prioritize routes (Distance-based sorting + Limit)
        val visibleRoutes = currentRoutes.filter { it.isVisible }
        
        val prioritizedRoutes = prioritizeFeatures(
            visibleRoutes,
            center,
            10 // Limit to 10 routes to prevent clutter
        ) { route ->
            if (route.waypoints.isNotEmpty()) {
                GeoPoint(route.waypoints[0].lat, route.waypoints[0].lon)
            } else {
                center
            }
        }

        // 🎯 STEP 1: Determine desired state
        val desiredIds = prioritizedRoutes.map { it.id }.toSet()
        val currentIds = currentlyRenderedRoutes.keys

        val toRemoveIds = currentIds - desiredIds
        val toAddRoutes = prioritizedRoutes.filter { !currentIds.contains(it.id) }

        // 🎯 STEP 2: Remove old routes (Outside -> Center)
        val sortedRemovals = sortForRemoval(toRemoveIds.toList(), center) { id ->
            val overlay = currentlyRenderedRoutes[id]
            if (overlay != null && overlay.route.waypoints.isNotEmpty()) {
                GeoPoint(overlay.route.waypoints[0].lat, overlay.route.waypoints[0].lon)
            } else {
                center
            }
        }
        removeRoutes(sortedRemovals)

        // 🎯 STEP 3: Add new routes (Center -> Outside)
        val sortedAdditions = sortForAddition(toAddRoutes, center) { route ->
            if (route.waypoints.isNotEmpty()) {
                GeoPoint(route.waypoints[0].lat, route.waypoints[0].lon)
            } else {
                center
            }
        }
        addRoutes(sortedAdditions)

        // Force map redraw
        mapView.invalidate()
    }

    private fun removeRoutes(ids: List<String>) {
        if (ids.isEmpty()) return

        val coordinator = overlayCoordinator
        
        if (coordinator != null) {
            ids.forEach { id ->
                currentlyRenderedRoutes[id]?.let { overlay ->
                    // Use first waypoint as centroid approximation
                    val centroid = if (overlay.route.waypoints.isNotEmpty()) {
                        GeoPoint(overlay.route.waypoints[0].lat, overlay.route.waypoints[0].lon)
                    } else {
                        GeoPoint(0.0, 0.0)
                    }
                    coordinator.removeOverlayFromBatch(overlay, id, centroid)
                }
            }
            coordinator.removeOverlayFromBatch()
            ids.forEach { currentlyRenderedRoutes.remove(it) }
        } else {
            ids.forEach { id ->
                currentlyRenderedRoutes[id]?.let { overlay ->
                    animationManager?.animateOverlayRemoval(overlay, id, mapView!!) {
                        currentlyRenderedRoutes.remove(id)
                    }
                }
            }
        }
    }

    private fun addRoutes(routes: List<Route>) {
        val coordinator = overlayCoordinator
        if (routes.isNotEmpty()) {
             // throw RuntimeException("DEBUG_EXCEPTION: addRoutes called with ${routes.size} routes. Map size: ${currentlyRenderedRoutes.size}")
        }

        if (coordinator != null) {
            routes.forEach { route ->
                val overlay = RouteOverlay(route)
                currentlyRenderedRoutes[route.id] = overlay
                
                coordinator.getAnimationManager().animateOverlayAddition(
                    overlay = overlay,
                    overlayId = route.id,
                    mapView = mapView!!
                ) {}
            }
        } else {
            routes.forEachIndexed { index, route ->
                val overlay = RouteOverlay(route)
                currentlyRenderedRoutes[route.id] = overlay
                
                animationManager?.animateOverlayAddition(
                    overlay = overlay,
                    overlayId = route.id,
                    mapView = mapView!!,
                    staggerDelay = index * 100L
                ) {}
            }
        }
    }

    /**
     * Clear all route overlays
     */
    private fun clearRouteOverlays() {
        val mapView = mapView ?: return
        
        // Use animation for clearing if possible
        val ids = currentlyRenderedRoutes.keys.toList()
        removeRoutes(ids)
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
     * Get the route cache instance safely
     */
    fun getRouteCache(): RouteCache? = routeCache

    /**
     * Enhanced performance statistics for RouteOverlayManager
     */
    override fun getPerformanceStats(): Map<String, Any> {
        val baseStats = super.getPerformanceStats().toMutableMap()
        val cacheStats = routeCache?.getCacheStats() ?: emptyMap()

        baseStats.putAll(cacheStats)
        baseStats["route_overlays_active"] = currentlyRenderedRoutes.size
        baseStats["current_routes_count"] = currentRoutes.size
        baseStats["has_selected_waypoint"] = currentSelectedWaypoint != null
        baseStats["has_selected_route"] = currentSelectedRouteId != null
        baseStats["overlay_coordinator_connected"] = overlayCoordinator != null
        
        return baseStats
    }

    /**
     * Inner class for individual route overlay rendering and interaction
     */
    private inner class RouteOverlay(var route: Route) : Overlay() {

        fun updateRoute(newRoute: Route) {
            this.route = newRoute
        }

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

                // Draw route line and leg labels
                if (route.waypoints.size >= 2) {
                    val path = Path()

                    route.waypoints.forEachIndexed { index, waypoint ->
                        val point = GeoPoint(waypoint.lat, waypoint.lon)
                        val screenPoint = projection.toPixels(point, null)

                        if (index == 0) {
                            path.moveTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
                        } else {
                            val prevWaypoint = route.waypoints[index - 1]
                            val prevPoint = GeoPoint(prevWaypoint.lat, prevWaypoint.lon)
                            val prevScreenPoint = projection.toPixels(prevPoint, null)
                            
                            path.lineTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())

                            // Draw leg distance label
                            val distanceMeters = point.distanceToAsDouble(prevPoint)
                            if (distanceMeters > 100) { // Only draw if segment is significant
                                val distanceKm = distanceMeters / 1000.0
                                val labelText = String.format("%.1f km", distanceKm)
                                
                                // Calculate midpoint
                                val midX = (prevScreenPoint.x + screenPoint.x) / 2f
                                val midY = (prevScreenPoint.y + screenPoint.y) / 2f
                                
                                // Draw label background (optional, for readability)
                                // canvas.drawCircle(midX, midY, 20f, backgroundPaint) 
                                
                                canvas.drawText(labelText, midX, midY - 10f, labelPaint)
                            }
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
