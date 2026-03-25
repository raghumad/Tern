package com.madanala.tern.ui.overlays

import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.madanala.tern.R
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
import com.madanala.tern.redux.RouteConstants
import com.madanala.tern.model.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.madanala.tern.utils.ZoomCategory
import com.madanala.tern.ui.theme.*

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

    // Waypoint icons
    private val launchIcon = ContextCompat.getDrawable(context, R.drawable.ic_waypoint_launch)
    private val landingIcon = ContextCompat.getDrawable(context, R.drawable.ic_waypoint_landing)
    private val turnpointIcon = ContextCompat.getDrawable(context, R.drawable.ic_waypoint_turnpoint)
    private val sssIcon = ContextCompat.getDrawable(context, R.drawable.ic_waypoint_sss)
    private val essIcon = ContextCompat.getDrawable(context, R.drawable.ic_waypoint_ess)
    private val goalIcon = ContextCompat.getDrawable(context, R.drawable.ic_waypoint_goal)

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
    private var weatherState: com.madanala.tern.redux.WeatherState? = null
    
    // PERFORMANCE: Cache high-fidelity Composable-to-Bitmap overlays
    private val waypointBitmapCache = android.util.LruCache<Int, android.graphics.Bitmap>(100)
    private val legDecorationCache = android.util.LruCache<Int, android.graphics.Bitmap>(50)

    // Changed to Map for better tracking and incremental updates
    private val currentlyRenderedRoutes = mutableMapOf<String, RouteOverlay>()

    // Paint objects for route rendering
    private val routePaint = Paint().apply {
        color = AeroNeonCyanHex.toInt()
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
        color = AeroCharcoalHex.toInt()
        style = Paint.Style.STROKE
        strokeWidth = WAYPOINT_BORDER_WIDTH
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = LABEL_TEXT_SIZE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val selectionPaint = Paint().apply {
        color = AeroOrangeHex.toInt()
        style = Paint.Style.STROKE
        strokeWidth = SELECTION_HIGHLIGHT_WIDTH
        isAntiAlias = true
    }

    private val dragPaint = Paint().apply {
        color = (AeroNeonCyanHex and 0x00FFFFFFL or (180L shl 24)).toInt()
        style = Paint.Style.STROKE
        strokeWidth = SELECTION_HIGHLIGHT_WIDTH + 2f // Thicker for "glow" effect
        isAntiAlias = true
    }

    private val routeSelectionPaint = Paint().apply {
        color = AeroOrangeHex.toInt()
        style = Paint.Style.STROKE
        strokeWidth = ROUTE_SELECTION_HIGHLIGHT_WIDTH
        isAntiAlias = true
    }

    private val hazardHaloPaint = Paint().apply {
        color = (AeroOrangeHex and 0x00FFFFFFL or (180L shl 24)).toInt()
        style = Paint.Style.STROKE
        strokeWidth = 10f
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        isAntiAlias = true
    }

    private val lightningBoltPath = Path().apply {
        moveTo(0f, -22f)
        lineTo(-12f, 0f)
        lineTo(2f, 0f)
        lineTo(-2f, 22f)
        lineTo(12f, 0f)
        lineTo(-2f, 0f)
        close()
    }

    private val lightningPaint = Paint().apply {
        color = AeroOrangeHex.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(8f, 0f, 0f, Color.BLACK) // Contrast against satellite maps
    }

    override fun getRenderedCount(): Int {
        return currentlyRenderedRoutes.size
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

    override fun onOverlayDetached() {
        Log.d(TAG, "[$this] Route overlay manager detached from MapView@${System.identityHashCode(mapView)}")
        
        // [LIFECYCLE FIX] Actually remove route overlays from map on detach to prevent orphans
        mapView?.let { map ->
            val overlays = currentlyRenderedRoutes.values.toList()
            overlays.forEach { map.overlays.remove(it) }
            map.invalidate()
            Log.d(TAG, "[$this] Removed ${overlays.size} orphaned route overlays from MapView on detach")
        }

        clearRouteOverlays()
    }

    override fun onReduxStateChanged(state: MapState) {
        try {
            // Log.d(TAG, "Redux state changed - routes enabled: ${state.overlayState.routes.enabled}")

            val config = state.overlayState.routes
            
            // Sync weather state for waypoint wind gauges
            this.weatherState = state.weatherState

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
                                }
                            } else {
                                // New route will be handled by updateRouteOverlays()
                            }

                            // Always update cache to ensure latest state (visibility, waypoints) is persisted
                            routeCache?.cacheRoute(route)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to cache/update route ${route.id}", e)
                        }
                    }
                }
            } else {
                clearRouteOverlays()
            }
            
            // Trigger pre-rendering of high-fidelity decorations
            preRenderRouteDecorations(state)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Redux state change in RouteOverlayManager", e)
        }
    }

    override fun clearOverlays() {
        clearRouteOverlays()
    }

    override fun reset() {
        super.reset()
        Log.d(TAG, "Resetting RouteOverlayManager state")
        currentRoutes = emptyList()
        currentSelectedWaypoint = null
        currentSelectedRouteId = null
        weatherState = null
        waypointBitmapCache.evictAll()
        clearRouteOverlays()
        currentlyRenderedRoutes.clear()
        legDecorationCache.evictAll()
        Log.d(TAG, "RouteOverlayManager reset complete")
    }

    /**
     * Pre-render high-fidelity bitmaps for waypoints and legs (RFC 005)
     * Offloads complex Compose rendering to background bitmaps for high-performance map overlays.
     */
    private fun preRenderRouteDecorations(state: MapState) {
        val mapView = mapView ?: return
        val weatherState = state.weatherState
        
        // 🎯 Zoom-Aware Context (RFC 005 SSOT)
        val zoom = mapView.zoomLevelDouble
        val category = ZoomCategory.fromZoom(zoom)
        val iconScale = category.iconScale
        val baseShowDetails = category.showHazardIndicators

        state.routes.filter { it.isVisible }.forEach { route ->
            // 🎯 Pre-render Waypoint Markers
            route.waypoints.forEachIndexed { index, waypoint ->
                val forecast = weatherState.waypointWeathers[waypoint.id]
                val isSelected = state.selectedWaypoint?.let { it.routeId == route.id && it.waypointId == waypoint.id } ?: false
                val isDragging = isSelected && state.selectedWaypoint?.isDragging == true
                
                // Priority Check: Always show details for critical waypoints
                val isCritical = waypoint.type == Waypoint.Type.LAUNCH || 
                                waypoint.type == Waypoint.Type.GOAL || 
                                waypoint.type == Waypoint.Type.ESS ||
                                index == 0 || // Start
                                index == route.waypoints.size - 1 // End
                
                val showDetails = baseShowDetails || isCritical || isSelected

                val waypointKey = arrayOf<Any?>(
                    waypoint.id,
                    waypoint.type,
                    waypoint.label,
                    isSelected,
                    isDragging,
                    forecast?.current?.wind?.speed?.roundToInt(),
                    forecast?.current?.wind?.direction?.roundToInt(),
                    forecast?.hasThunderstorm(),
                    forecast?.hasConvectiveDanger(),
                    iconScale,
                    showDetails
                ).contentHashCode()

                if (waypointBitmapCache.get(waypointKey) == null) {
                    coroutineScope.launch(Dispatchers.Main) {
                        try {
                            // Scale dimensions based on iconScale
                            val widthDp = (80 * iconScale).toInt().coerceAtLeast(32)
                            val heightDp = (100 * iconScale).toInt().coerceAtLeast(40)
                            
                            val bitmap = com.madanala.tern.utils.ViewToBitmap.createBitmapFromComposableDP(
                                parentView = mapView,
                                widthDp = widthDp,
                                heightDp = heightDp
                            ) {
                                com.madanala.tern.ui.components.WaypointMarker(
                                    waypoint = waypoint,
                                    forecast = forecast,
                                    isSelected = isSelected,
                                    isDragging = isDragging,
                                    scale = iconScale,
                                    showDetails = showDetails
                                )
                            }
                            waypointBitmapCache.put(waypointKey, bitmap)
                            mapView.invalidate()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to pre-render waypoint marker for ${waypoint.id}", e)
                        }
                    }
                }
            }

            // 🎯 Pre-render Leg Decorations (Distances/ETAs)
            if (route.waypoints.size >= 2) {
                route.waypoints.indices.drop(1).forEach { index ->
                    val distanceKm = route.legDistances.getOrNull(index - 1) ?: 0.0
                    val etaMin = weatherState.waypointEtas[route.waypoints[index].id]?.toInt()
                    
                    val legKey = arrayOf<Any?>(distanceKm, etaMin, iconScale).contentHashCode()
                    
                    if (legDecorationCache.get(legKey) == null) {
                        coroutineScope.launch(Dispatchers.Main) {
                            try {
                                val widthDp = (100 * iconScale).toInt().coerceAtLeast(40)
                                val heightDp = (40 * iconScale).toInt().coerceAtLeast(20)
                                
                                val bitmap = com.madanala.tern.utils.ViewToBitmap.createBitmapFromComposableDP(
                                    parentView = mapView,
                                    widthDp = widthDp,
                                    heightDp = heightDp
                                ) {
                                    com.madanala.tern.ui.components.LegDecoration(
                                        distanceKm = distanceKm,
                                        etaMin = etaMin,
                                        scale = iconScale
                                    )
                                }
                                legDecorationCache.put(legKey, bitmap)
                                mapView.invalidate()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to pre-render leg decoration for route ${route.id}", e)
                            }
                        }
                    }
                }
            }
        }
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
        // REMOVED: This causes infinite recursion as OverlayCoordinator calls this method!
        // mOverlayCoordinator?.onMapMoved(center.latitude, center.longitude, zoom)
        
        // Trigger update to respect new center for prioritization
        updateRouteOverlays(center)
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
        mOverlayCoordinator?.onViewportChanged(viewport)
    }

    /**
     * Handle overlay budget changes with route-specific logging
     */
    override fun onOverlayBudgetChanged(budget: com.madanala.tern.utils.OverlayBudget) {
        super.onOverlayBudgetChanged(budget)

        // Get current map center for geographic context
        val center = mapView?.mapCenter
        val centerStr = if (center != null) {
            String.format("@ %.4f,%.4f", center.latitude, center.longitude)
        } else {
            "@ unknown location"
        }

        // Get actually visible routes (added to map vs just created)
        val actuallyVisibleRoutes = currentlyRenderedRoutes.count { (_, overlay) ->
            mapView?.overlays?.contains(overlay) == true
        }

        Log.d(TAG, String.format(
            "Route Budget: %d total (Created: %d, Visible: %d %s)",
            budget.totalOverlays,
            currentlyRenderedRoutes.size,
            actuallyVisibleRoutes,
            centerStr
        ))
    }

    /**
     * Update route overlays on map with incremental updates and prioritization
     */
    private fun updateRouteOverlays(center: GeoPoint = mapView?.mapCenter as? GeoPoint ?: GeoPoint(0.0, 0.0)) {
        val mapView = mapView ?: return
        


        // 🎯 STEP 0: Prioritize routes (Zone-based budgeting)
        val visibleRoutes = currentRoutes.filter { it.isVisible }
        
        val prioritizedRoutes = prioritizeFeaturesByZone(
            visibleRoutes,
            center
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
        addRoutes(sortedAdditions, mapView)

        // Force map redraw
        mapView.invalidate()
    }

    private fun removeRoutes(ids: List<String>) {
        if (ids.isEmpty()) return

        val coordinator = mOverlayCoordinator
        
        if (coordinator != null) {
            ids.forEach { id ->
                currentlyRenderedRoutes[id]?.let { overlay ->
                    // Use first waypoint as centroid approximation
                    val centroid = if (overlay.route.waypoints.isNotEmpty()) {
                        GeoPoint(overlay.route.waypoints[0].lat, overlay.route.waypoints[0].lon)
                    } else {
                        GeoPoint(0.0, 0.0)
                    }
                    coordinator.removeOverlayFromBatch(overlay, id, centroid, OverlayType.ROUTES)
                }
            }
            coordinator.flushPendingRemovals()
            ids.forEach { currentlyRenderedRoutes.remove(it) }
        } else {
            ids.forEach { id ->
                currentlyRenderedRoutes[id]?.let { overlay ->
                    animationManager?.animateOverlayRemoval(overlay, id, mapView!!, OverlayType.ROUTES) {
                        currentlyRenderedRoutes.remove(id)
                    }
                }
            }
        }
    }

    /**
     * Add routes to the map with staggered animation
     */
    private fun addRoutes(routes: List<Route>, mapView: MapView) {
        val coordinator = mOverlayCoordinator

        if (coordinator != null) {
            // Use Hilbert-ordered batch addition for smooth center-to-outside addition
            routes.forEachIndexed { index, route ->
                // Create overlay WITHOUT adding to map
                val overlay = RouteOverlay(route)

                // Add to our tracking immediately
                currentlyRenderedRoutes[route.id] = overlay

                // Add to Hilbert-ordered batch for addition (center to outside)
                val centroid = getRouteCentroid(route)
                coordinator.addOverlayToBatch(overlay, route.id, centroid, OverlayType.ROUTES)
            }

            // Process the batch for Hilbert-ordered addition
            coordinator.flushPendingAdditions()
        } else {
            // Fallback to direct animation manager if coordinator not available
            routes.forEachIndexed { index, route ->
                // Create overlay WITHOUT adding to map
                val overlay = RouteOverlay(route)

                // Add to our tracking immediately
                currentlyRenderedRoutes[route.id] = overlay

                // Animation manager handles addition with fade-in effect
                animationManager?.animateOverlayAddition(
                    overlay = overlay,
                    overlayId = route.id,
                    mapView = mapView,
                    staggerDelay = index * 100L, // Stagger for visual polish
                    type = OverlayType.ROUTES
                ) {
                } ?: throw IllegalStateException(
                    "Animation manager is required for route overlay addition. " +
                    "Ensure OverlayCoordinator is properly initialized."
                )
            }
        }
    }

    /**
     * Helper to get route centroid
     */
    private fun getRouteCentroid(route: Route): GeoPoint {
        return if (route.waypoints.isNotEmpty()) {
            GeoPoint(route.waypoints[0].lat, route.waypoints[0].lon)
        } else {
            GeoPoint(0.0, 0.0)
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
        baseStats["route_overlays_active"] = currentlyRenderedRoutes.size
        baseStats["current_routes_count"] = currentRoutes.size
        baseStats["has_selected_waypoint"] = currentSelectedWaypoint != null
        baseStats["has_selected_route"] = currentSelectedRouteId != null
        baseStats["overlay_coordinator_connected"] = mOverlayCoordinator != null
        
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

        private val cylinderPaint by lazy {
            Paint().apply {
                color = (AeroNeonCyanHex and 0x00FFFFFFL or (40L shl 24)).toInt()
                style = Paint.Style.FILL
                isAntiAlias = true
            }
        }

        private val cylinderStrokePaint by lazy {
            Paint().apply {
                color = (AeroNeonCyanHex and 0x00FFFFFFL or (150L shl 24)).toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }
        }

        private val arrowPaint by lazy {
            Paint().apply {
                color = AeroNeonCyanHex.toInt()
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }
        }

        private val mPath = Path()
        private val mArrowPath = Path()

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            try {
                if (shadow) return

                val projection = mapView.projection
                val zoom = mapView.zoomLevelDouble
                val category = ZoomCategory.fromZoom(zoom)
                val iconScale = category.iconScale
                val baseShowDetails = category.showHazardIndicators

                // Draw cylinders first
                route.waypoints.forEach { waypoint ->
                    drawCylinder(canvas, projection, waypoint)
                }

                // Draw route line and leg labels
                if (route.waypoints.size >= 2) {
                    mPath.reset()

                    route.waypoints.forEachIndexed { index, waypoint ->
                        val point = GeoPoint(waypoint.lat, waypoint.lon)
                        val screenPoint = projection.toPixels(point, null)

                        if (index == 0) {
                            mPath.moveTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
                        } else {
                            val prevWaypoint = route.waypoints[index - 1]
                            val prevScreenPoint = projection.toPixels(GeoPoint(prevWaypoint.lat, prevWaypoint.lon), null)
                            
                            mPath.lineTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())

                            // Draw directional arrow
                            drawDirectionalArrow(canvas, prevScreenPoint.x.toFloat(), prevScreenPoint.y.toFloat(), screenPoint.x.toFloat(), screenPoint.y.toFloat())

                            // 🎯 RFC 005: Leg Decoration (Distance/ETA)
                            val distanceKm = route.legDistances.getOrNull(index - 1) ?: 0.0
                            val etaMin = weatherState?.waypointEtas?.get(waypoint.id)?.toInt()
                            val legKey = arrayOf<Any?>(distanceKm, etaMin, iconScale).contentHashCode()
                            
                            legDecorationCache.get(legKey)?.let { bitmap ->
                                val midX = (prevScreenPoint.x + screenPoint.x) / 2f
                                val midY = (prevScreenPoint.y + screenPoint.y) / 2f
                                canvas.drawBitmap(bitmap, midX - bitmap.width / 2f, midY - bitmap.height / 2f, null)
                            }
                        }
                    }

                    // Check if this route is selected and draw highlight first
                    if (currentSelectedRouteId == route.id) {
                        canvas.drawPath(mPath, routeSelectionPaint)
                    }

                    canvas.drawPath(mPath, routePaint)
                }

                // 🎯 RFC 005: Waypoint Rendering (Unified Composable)
                route.waypoints.forEachIndexed { index, waypoint ->
                    try {
                        val point = GeoPoint(waypoint.lat, waypoint.lon)
                        val screenPoint = projection.toPixels(point, null)

                        val isSelected = currentSelectedWaypoint?.let { it.routeId == route.id && it.waypointId == waypoint.id } ?: false
                        val isDragging = isSelected && currentSelectedWaypoint?.isDragging == true
                        val forecast = weatherState?.waypointWeathers?.get(waypoint.id)
                        
                        // Local priority check (matches pre-render)
                        val isCritical = waypoint.type == Waypoint.Type.LAUNCH || 
                                        waypoint.type == Waypoint.Type.GOAL || 
                                        waypoint.type == Waypoint.Type.ESS ||
                                        index == 0 || index == route.waypoints.size - 1

                        val showDetails = baseShowDetails || isCritical || isSelected

                        val waypointKey = arrayOf<Any?>(
                            waypoint.id,
                            waypoint.type,
                            waypoint.label,
                            isSelected,
                            isDragging,
                            forecast?.current?.wind?.speed?.roundToInt(),
                            forecast?.current?.wind?.direction?.roundToInt(),
                            forecast?.hasThunderstorm(),
                            forecast?.hasConvectiveDanger(),
                            iconScale,
                            showDetails
                        ).contentHashCode()

                        waypointBitmapCache.get(waypointKey)?.let { bitmap ->
                            // Center bitmap on screen point
                            canvas.drawBitmap(bitmap, screenPoint.x - bitmap.width / 2f, screenPoint.y - bitmap.height / 2f, null)
                        } ?: run {
                            // Fallback to basic circle if bitmap not yet rendered
                            canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), (MULTI_WAYPOINT_RADIUS * iconScale), waypointPaint)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error drawing waypoint ${waypoint.id} in route ${route.id}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing route overlay for route ${route.id}", e)
            }
        }
        /**
         * Draw FAI cylinder around waypoint
         */
        private fun drawCylinder(canvas: Canvas, projection: org.osmdroid.views.Projection, waypoint: com.madanala.tern.model.Waypoint) {
            val radiusMeters = waypoint.radius ?: RouteConstants.FAI_DEFAULT_RADIUS_METERS
            val point = GeoPoint(waypoint.lat, waypoint.lon)
            val screenPoint = projection.toPixels(point, null)
            
            // Calculate radius in pixels at current zoom level
            val radiusPixels = projection.metersToEquatorPixels(radiusMeters.toFloat())
            
            if (radiusPixels > 2f) { // Only draw if visible
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radiusPixels, cylinderPaint)
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radiusPixels, cylinderStrokePaint)
            }
        }

        /**
         * Draw directional arrow on route segment
         */
        private fun drawDirectionalArrow(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float) {
            val dx = endX - startX
            val dy = endY - startY
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            
            if (distance < 50) return // Don't draw arrow on very short segments
            
            // Calculate midpoint
            val midX = (startX + endX) / 2
            val midY = (startY + endY) / 2
            
            // Calculate angle
            val angle = kotlin.math.atan2(dy, dx)
            
            // Arrow dimensions
            val arrowLength = 30f
            val arrowWidth = 15f
            
            mArrowPath.reset()
            mArrowPath.moveTo(midX, midY)
            mArrowPath.lineTo(
                midX - arrowLength * kotlin.math.cos(angle - kotlin.math.PI / 6).toLong().toFloat(),
                midY - arrowLength * kotlin.math.sin(angle - kotlin.math.PI / 6).toLong().toFloat()
            )
            mArrowPath.moveTo(midX, midY)
            mArrowPath.lineTo(
                midX - arrowLength * kotlin.math.cos(angle + kotlin.math.PI / 6).toLong().toFloat(),
                midY - arrowLength * kotlin.math.sin(angle + kotlin.math.PI / 6).toLong().toFloat()
            )
            
            canvas.drawPath(mArrowPath, arrowPaint)
        }
    }
}
