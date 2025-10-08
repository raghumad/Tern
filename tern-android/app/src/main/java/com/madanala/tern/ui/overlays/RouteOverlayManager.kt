package com.madanala.tern.ui.overlays

import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.madanala.tern.redux.*
import com.madanala.tern.utils.DistanceZone
import com.madanala.tern.utils.FlightPhase
import com.madanala.tern.utils.MemoryPressureLevel
import kotlinx.coroutines.*
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * Overlay manager for route visualization and interaction
 * Handles waypoints, route lines, and FAI cylinders on the map
 */
class RouteOverlayManager(
    private val routeStore: com.madanala.tern.redux.MapStore? = null,
    mapStore: MapStore? = null
) : BaseOverlayManager(OverlayType.ROUTES, mapStore) {

    companion object {
        private const val TAG = "RouteOverlayManager"

        // Visual styling constants
        private const val ROUTE_LINE_WIDTH = 4.0f
        private const val WAYPOINT_MARKER_SIZE = 32
        private const val CYLINDER_LINE_WIDTH = 2.0f
        private val SELECTED_WAYPOINT_COLOR = Color.YELLOW
        private val UNSELECTED_WAYPOINT_COLOR = Color.BLUE
        private val ROUTE_LINE_COLOR = Color.rgb(0, 100, 255)
        private val CYLINDER_FILL_COLOR = Color.argb(50, 0, 100, 255)
        private val CYLINDER_STROKE_COLOR = Color.rgb(0, 100, 255)

        // Performance constants
        private const val MAX_VISIBLE_WAYPOINTS = 50
        private const val MAX_CYLINDER_POINTS = 64
        private const val ROUTE_SIMPLIFICATION_TOLERANCE = 0.001 // degrees
    }

    // Route visualization overlays
    private val waypointMarkers = mutableMapOf<String, Marker>()
    private val routeLines = mutableMapOf<String, Polyline>()
    private val cylinderOverlays = mutableMapOf<String, Polygon>()

    // Interactive state
    private var interactiveMode = false
    private var selectedWaypointId: String? = null
    private var isDraggingWaypoint = false

    // Performance tracking
    private var lastViewportUpdate = 0L
    private var lastRouteUpdate = 0L
    private val performanceStats = mutableMapOf<String, Any>()

    override fun onOverlayAttached() {
        Log.d(TAG, "Route overlay manager attached")
        setupRouteSubscription()
        initializeRouteOverlays()
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "Route overlay manager detached")
        cleanupRouteOverlays()
        routeSubscriptionJob?.cancel()
    }

    private fun cleanupRouteOverlays() {
        waypointMarkers.clear()
        routeLines.clear()
        cylinderOverlays.clear()
        Log.d(TAG, "Route overlays cleaned up")
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        if (!isAttached || !isEnabled()) return

        val currentTime = System.currentTimeMillis()
        // Debounce rapid map movements
        if (currentTime - lastViewportUpdate < 300) return
        lastViewportUpdate = currentTime

        CoroutineScope(Dispatchers.Main).launch {
            updateVisibleRouteElements()
        }
    }

    override fun onViewportChangedInternal(viewport: BoundingBox) {
        if (!isAttached || !isEnabled()) return

        CoroutineScope(Dispatchers.IO).launch {
            updateViewportVisibility(viewport)
        }
    }

    override fun onReduxStateChanged(state: MapState) {
        if (!isAttached) return

        // Update route visibility based on overlay config
        val routeConfig = state.overlayState.routes
        if (routeConfig.enabled != isEnabled()) {
            setEnabled(routeConfig.enabled)
        }

        // TODO: Integrate with RouteState for route data
        // For now, route data would come from a separate RouteStore
        Log.d(TAG, "Redux state changed - route overlay config: ${routeConfig.enabled}")
    }

    override fun clearOverlays() {
        mapView?.let { map ->
            CoroutineScope(Dispatchers.Main).launch {
                withContext(Dispatchers.Main) {
                    // Remove all route-related overlays
                    waypointMarkers.values.forEach { map.overlays.remove(it) }
                    routeLines.values.forEach { map.overlays.remove(it) }
                    cylinderOverlays.values.forEach { map.overlays.remove(it) }

                    waypointMarkers.clear()
                    routeLines.clear()
                    cylinderOverlays.clear()

                    map.invalidate()
                    Log.d(TAG, "All route overlays cleared")
                }
            }
        }
    }

    override fun removeInvisibleOverlays(): Int {
        if (mapView?.boundingBox == null) return 0

        val visibleViewport = mapView!!.boundingBox
        var removedCount = 0

        // Remove waypoints outside visible area
        val waypointsToRemove = waypointMarkers.entries.filter { (_, marker) ->
            !visibleViewport.contains(marker.position)
        }

        waypointsToRemove.forEach { (id, marker) ->
            mapView?.overlays?.remove(marker)
            waypointMarkers.remove(id)
            removedCount++
        }

        // Remove cylinders outside visible area (with buffer)
        val bufferedViewport = BoundingBox(
            visibleViewport.latNorth + 0.1,
            visibleViewport.lonEast + 0.1,
            visibleViewport.latSouth - 0.1,
            visibleViewport.lonWest - 0.1
        )

        val cylindersToRemove = cylinderOverlays.entries.filter { (_, polygon) ->
            polygon.bounds?.let { bounds ->
                !boundingBoxesOverlap(bufferedViewport, bounds)
            } ?: true
        }

        cylindersToRemove.forEach { (id, polygon) ->
            mapView?.overlays?.remove(polygon)
            cylinderOverlays.remove(id)
            removedCount++
        }

        if (removedCount > 0) {
            mapView?.invalidate()
            Log.d(TAG, "Removed $removedCount invisible route overlays")
        }

        return removedCount
    }

    override fun clearOverlaysInZone(zone: DistanceZone): Int {
        var removedCount = 0

        when (zone) {
            DistanceZone.CORE -> {
                // Remove distant waypoints and cylinders
                removedCount += removeDistantWaypoints(10000.0) // 10km
                removedCount += removeDistantCylinders(15000.0) // 15km
            }
            DistanceZone.NEAR -> {
                // Remove medium distance elements
                removedCount += removeDistantWaypoints(25000.0) // 25km
                removedCount += removeDistantCylinders(30000.0) // 30km
            }
            DistanceZone.MID -> {
                // Remove far elements
                removedCount += removeDistantWaypoints(50000.0) // 50km
                removedCount += removeDistantCylinders(75000.0) // 75km
            }
            DistanceZone.FAR -> {
                // Keep only very close elements
                removedCount += removeDistantWaypoints(1000.0) // 1km
                removedCount += removeDistantCylinders(2000.0) // 2km
            }
            DistanceZone.EXTREME -> {
                // Remove almost everything
                removedCount += removeDistantWaypoints(500.0) // 500m
                removedCount += removeDistantCylinders(1000.0) // 1km
            }
        }

        if (removedCount > 0) {
            mapView?.invalidate()
            Log.d(TAG, "Removed $removedCount route overlays in ${zone.name} zone")
        }

        return removedCount
    }

    override fun preserveSafetyCriticalOverlays(): Int {
        // For routes, we preserve the current route waypoints and essential cylinders
        val preservedCount = waypointMarkers.size + cylinderOverlays.size

        // Don't remove current route elements as they are safety-critical for navigation
        Log.d(TAG, "Preserved $preservedCount safety-critical route overlays")
        return preservedCount
    }

    override fun onMemoryStateChanged(memoryState: com.madanala.tern.utils.ApplicationMemoryState) {
        when (memoryState.calculatedPressure) {
            MemoryPressureLevel.CRITICAL_MEMORY -> {
                // Emergency cleanup - remove all but current route
                clearNonCurrentRouteOverlays()
            }
            MemoryPressureLevel.HIGH_MEMORY -> {
                // Aggressive cleanup - reduce overlay detail
                simplifyRouteVisualization()
            }
            MemoryPressureLevel.MEDIUM_MEMORY -> {
                // Moderate cleanup - remove distant overlays
                removeDistantOverlays()
            }
            MemoryPressureLevel.LOW_MEMORY -> {
                // Normal operation - full visualization
                restoreFullVisualization()
            }
        }
    }

    override fun onOverlayBudgetChanged(budget: com.madanala.tern.utils.OverlayBudget) {
        // Adjust route overlay complexity based on budget
        val maxOverlays = budget.totalOverlays / 4 // Routes get 1/4 of total budget

        if (waypointMarkers.size + cylinderOverlays.size > maxOverlays) {
            reduceOverlayComplexity(maxOverlays)
        }
    }

    // Route-specific functionality

    private fun setupRouteSubscription() {
        // Subscribe to route state changes if route store is available
        // This would typically be handled through the Redux pattern
    }

    private var routeSubscriptionJob: kotlinx.coroutines.Job? = null

    private fun initializeRouteOverlays() {
        if (mapView == null) return

        // Initialize with empty state
        updatePerformanceStats()
        Log.d(TAG, "Route overlays initialized")
    }

    private fun updateRouteVisualization(route: Route) {
        if (mapView == null || !isEnabled()) return

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Clear existing overlays for this route
                clearRouteOverlays(route.id)

                if (route.waypoints.isEmpty()) return@launch

                // Create route line
                createRouteLine(route)

                // Create waypoint markers
                createWaypointMarkers(route)

                // Create FAI cylinders for turnpoints
                createCylinders(route)

                // Update performance stats
                updatePerformanceStats()

                mapView?.invalidate()
                Log.d(TAG, "Updated visualization for route: ${route.name} (${route.waypoints.size} waypoints)")

            } catch (e: Exception) {
                Log.e(TAG, "Error updating route visualization", e)
            }
        }
    }

    private fun createRouteLine(route: Route) {
        if (route.waypoints.size < 2) return

        val polyline = Polyline().apply {
            setPoints(route.waypoints.map { it.location })
            color = ROUTE_LINE_COLOR
            width = ROUTE_LINE_WIDTH.toFloat()
            isGeodesic = true
        }

        routeLines[route.id] = polyline
        mapView?.overlays?.add(polyline)
    }

    private fun createWaypointMarkers(route: Route) {
        route.waypoints.forEach { waypoint ->
            val marker = createWaypointMarker(waypoint, route.id)
            waypointMarkers[waypoint.id] = marker
            mapView?.overlays?.add(marker)
        }
    }

    private fun createWaypointMarker(waypoint: Waypoint, routeId: String): Marker {
        return Marker(mapView).apply {
            position = waypoint.location
            title = waypoint.name
            snippet = waypoint.description

            // Set icon based on waypoint type
            icon = createWaypointIcon(waypoint.waypointType)

            // Set color based on selection state
            val isSelected = waypoint.id == selectedWaypointId
            setTextIcon(createWaypointTextIcon(waypoint, isSelected))

            // Set up interaction listeners
            setOnMarkerClickListener { _, _ ->
                onWaypointClicked(waypoint)
                true
            }

            // Enable dragging if in edit mode
            if (interactiveMode) {
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker) {
                        // Handle drag with current marker position
                        onWaypointDragged(waypoint, marker.position)
                    }

                    override fun onMarkerDragEnd(marker: Marker) {
                        onWaypointDragEnd(waypoint, marker.position)
                    }

                    override fun onMarkerDragStart(marker: Marker) {
                        onWaypointDragStart(waypoint)
                    }
                })
            }
        }
    }

    private fun createCylinders(route: Route) {
        route.waypoints.forEach { waypoint ->
            if (waypoint.waypointType == WaypointType.TURNPOINT) {
                createCylinderOverlay(waypoint, route.id)
            }
        }
    }

    private fun createCylinderOverlay(waypoint: Waypoint, routeId: String) {
        val cylinder = Polygon().apply {
            points = createCylinderPoints(waypoint.location, waypoint.cylinderRadius)
            fillPaint.color = CYLINDER_FILL_COLOR
            fillPaint.style = Paint.Style.FILL
            outlinePaint.color = CYLINDER_STROKE_COLOR
            outlinePaint.strokeWidth = CYLINDER_LINE_WIDTH.toFloat()
            outlinePaint.style = Paint.Style.STROKE
        }

        cylinderOverlays[waypoint.id] = cylinder
        mapView?.overlays?.add(cylinder)
    }

    private fun createCylinderPoints(center: GeoPoint, radiusMeters: Double): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        val numPoints = minOf(MAX_CYLINDER_POINTS, (radiusMeters / 1000).toInt() * 8 + 16)

        // Convert radius from meters to approximate degrees
        val radiusDegrees = (radiusMeters / 1000) / 111.0 // Rough conversion: 1 degree ≈ 111km

        for (i in 0 until numPoints) {
            val angle = (2 * PI * i) / numPoints
            val latOffset = radiusDegrees * cos(angle)
            val lonOffset = radiusDegrees * sin(angle) / cos(Math.toRadians(center.latitude))

            points.add(GeoPoint(
                center.latitude + latOffset,
                center.longitude + lonOffset
            ))
        }

        return points
    }

    /**
     * Check if two bounding boxes overlap (intersection detection)
     */
    private fun boundingBoxesOverlap(box1: BoundingBox, box2: BoundingBox): Boolean {
        return box1.latNorth > box2.latSouth &&
               box1.latSouth < box2.latNorth &&
               box1.lonEast > box2.lonWest &&
               box1.lonWest < box2.lonEast
    }

    /**
     * Calculate distance between two GPS coordinates using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun createWaypointIcon(waypointType: WaypointType): android.graphics.drawable.Drawable? {
        // Create simple colored circle based on waypoint type
        val paint = Paint().apply {
            color = when (waypointType) {
                WaypointType.LAUNCH -> Color.GREEN
                WaypointType.TURNPOINT -> Color.RED
                WaypointType.LANDING -> Color.rgb(255, 165, 0) // Orange
                WaypointType.INTERMEDIATE -> Color.BLUE
                WaypointType.THERMAL -> Color.CYAN
            }
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Create a simple bitmap drawable (simplified implementation)
        return null // Would create actual drawable in full implementation
    }

    private fun createWaypointTextIcon(waypoint: Waypoint, isSelected: Boolean): String {
        val number = waypoint.name.filter { it.isDigit() }.take(2)
        return if (number.isNotEmpty()) "WP$number" else waypoint.name.take(3)
    }

    // Interactive functionality

    private fun onWaypointClicked(waypoint: Waypoint) {
        selectedWaypointId = waypoint.id
        updateWaypointVisualState()

        // TODO: Notify Redux store of selection when RouteStore is available
        // routeStore?.dispatch(RouteAction.SelectWaypoint(waypoint.id))

        Log.d(TAG, "Waypoint clicked: ${waypoint.name}")
    }

    private fun onWaypointDragged(waypoint: Waypoint, newLocation: GeoPoint) {
        isDraggingWaypoint = true
        // Update waypoint location in real-time during drag
        Log.d(TAG, "Waypoint dragged: ${waypoint.name} -> (${newLocation.latitude}, ${newLocation.longitude})")
    }

    private fun onWaypointDragStart(waypoint: Waypoint) {
        isDraggingWaypoint = true
        Log.d(TAG, "Waypoint drag started: ${waypoint.name}")
    }

    private fun onWaypointDragEnd(waypoint: Waypoint, finalLocation: GeoPoint) {
        isDraggingWaypoint = false

        // TODO: Update waypoint location in Redux store when RouteStore is available
        val updatedWaypoint = waypoint.copy(
            location = finalLocation,
            updatedAt = System.currentTimeMillis()
        )

        // routeStore?.dispatch(RouteAction.UpdateWaypoint(0, updatedWaypoint)) // Index would need to be calculated

        Log.d(TAG, "Waypoint drag ended: ${waypoint.name} -> (${finalLocation.latitude}, ${finalLocation.longitude})")
    }

    private fun updateWaypointVisualState() {
        waypointMarkers.forEach { (id, marker) ->
            val isSelected = id == selectedWaypointId
            marker.setTextIcon(createWaypointTextIcon(
                waypointMarkers.entries.find { it.key == id }?.let { entry ->
                    // Would need to get waypoint from ID mapping
                    null
                } ?: return@forEach,
                isSelected
            ))
        }
        mapView?.invalidate()
    }

    // Performance optimization methods

    private fun updateVisibleRouteElements() {
        if (mapView?.boundingBox == null) return

        val viewport = mapView!!.boundingBox
        var visibleCount = 0

        // Show/hide waypoint markers based on viewport
        waypointMarkers.forEach { (id, marker) ->
            val shouldBeVisible = viewport.contains(marker.position)
            if (shouldBeVisible && marker !in mapView!!.overlays) {
                mapView?.overlays?.add(marker)
                visibleCount++
            } else if (!shouldBeVisible && marker in mapView!!.overlays) {
                mapView?.overlays?.remove(marker)
            }
        }

        // Show/hide cylinders based on viewport (with buffer)
        val bufferedViewport = BoundingBox(
            viewport.latNorth + 0.05,
            viewport.lonEast + 0.05,
            viewport.latSouth - 0.05,
            viewport.lonWest - 0.05
        )

        cylinderOverlays.forEach { (id, polygon) ->
            val bounds = polygon.bounds
            val shouldBeVisible = if (bounds != null) {
                boundingBoxesOverlap(bufferedViewport, bounds)
            } else {
                false
            }

            if (shouldBeVisible && polygon !in mapView!!.overlays) {
                mapView?.overlays?.add(polygon)
                visibleCount++
            } else if (!shouldBeVisible && polygon in mapView!!.overlays) {
                mapView?.overlays?.remove(polygon)
            }
        }

        if (visibleCount > 0) {
            mapView?.invalidate()
        }
    }

    private fun updateViewportVisibility(viewport: BoundingBox) {
        // More detailed viewport culling logic
        CoroutineScope(Dispatchers.Main).launch {
            updateVisibleRouteElements()
        }
    }

    private fun clearRouteOverlays(routeId: String) {
        // Remove overlays for specific route
        routeLines.remove(routeId)?.let { polyline ->
            mapView?.overlays?.remove(polyline)
        }

        // Remove waypoints for this route
        val waypointIds = waypointMarkers.keys.filter { it.startsWith(routeId) }
        waypointIds.forEach { id ->
            waypointMarkers.remove(id)?.let { marker ->
                mapView?.overlays?.remove(marker)
            }
        }
    }

    private fun removeDistantWaypoints(maxDistanceKm: Double): Int {
        if (mapView?.mapCenter == null) return 0

        val center = mapView!!.mapCenter
        var removedCount = 0

        val markersToRemove = waypointMarkers.entries.filter { (_, marker) ->
            val distance = calculateDistance(
                center.latitude, center.longitude,
                marker.position.latitude, marker.position.longitude
            )
            distance > maxDistanceKm * 1000
        }

        markersToRemove.forEach { (id, marker) ->
            mapView?.overlays?.remove(marker)
            waypointMarkers.remove(id)
            removedCount++
        }

        return removedCount
    }

    private fun removeDistantCylinders(maxDistanceKm: Double): Int {
        if (mapView?.mapCenter == null) return 0

        val center = mapView!!.mapCenter
        var removedCount = 0

        val cylindersToRemove = cylinderOverlays.entries.filter { (_, polygon) ->
            val bounds = polygon.bounds
            if (bounds != null) {
                val distance = calculateDistance(
                    center.latitude, center.longitude,
                    bounds.centerWithDateLine.latitude, bounds.centerWithDateLine.longitude
                )
                distance > maxDistanceKm * 1000
            } else {
                true // Remove if no bounds available
            }
        }

        cylindersToRemove.forEach { (id, polygon) ->
            mapView?.overlays?.remove(polygon)
            cylinderOverlays.remove(id)
            removedCount++
        }

        return removedCount
    }

    private fun clearNonCurrentRouteOverlays() {
        // Keep only current route overlays, remove others
        Log.w(TAG, "Emergency cleanup: Removing non-current route overlays")
        // Implementation would identify and remove non-current route overlays
    }

    private fun simplifyRouteVisualization() {
        // Reduce cylinder detail and remove distant waypoints
        Log.d(TAG, "Simplifying route visualization for performance")
        // Implementation would reduce polygon points and remove distant elements
    }

    private fun removeDistantOverlays() {
        // Remove overlays far from current view
        removeDistantWaypoints(30000.0) // 30km
        removeDistantCylinders(50000.0) // 50km
    }

    private fun restoreFullVisualization() {
        // Restore all route overlays
        if (mapView != null) {
            waypointMarkers.values.forEach { mapView?.overlays?.add(it) }
            cylinderOverlays.values.forEach { mapView?.overlays?.add(it) }
            mapView?.invalidate()
        }
    }

    private fun reduceOverlayComplexity(maxOverlays: Int) {
        val totalOverlays = waypointMarkers.size + cylinderOverlays.size

        if (totalOverlays > maxOverlays) {
            val toRemove = totalOverlays - maxOverlays

            // Remove distant overlays first
            val removed = removeDistantWaypoints(10000.0) + removeDistantCylinders(15000.0)

            if (removed < toRemove) {
                // If still need to remove more, remove oldest overlays
                removeOldestOverlays(toRemove - removed)
            }
        }
    }

    private fun removeOldestOverlays(count: Int) {
        // Remove oldest overlays based on creation time
        // Implementation would track and remove oldest overlays
    }

    private fun updatePerformanceStats() {
        performanceStats.clear()
        performanceStats["waypoint_markers"] = waypointMarkers.size
        performanceStats["route_lines"] = routeLines.size
        performanceStats["cylinder_overlays"] = cylinderOverlays.size
        performanceStats["last_update"] = System.currentTimeMillis()
        performanceStats["interactive_mode"] = interactiveMode
        performanceStats["selected_waypoint"] = selectedWaypointId ?: "none"
    }

    // Public API methods

    fun setInteractiveMode(enabled: Boolean) {
        interactiveMode = enabled
        // Update all waypoint markers with new interaction state
        waypointMarkers.values.forEach { marker ->
            marker.isDraggable = enabled
        }
        Log.d(TAG, "Interactive mode ${if (enabled) "enabled" else "disabled"}")
    }

    fun selectWaypoint(waypointId: String?) {
        selectedWaypointId = waypointId
        updateWaypointVisualState()
    }

    fun refreshRouteVisualization() {
        // Force refresh of all route overlays
        mapView?.let { map ->
            val routeIds = routeLines.keys.toList()
            routeIds.forEach { routeId ->
                // Would reload and recreate overlays for each route
                Log.d(TAG, "Refreshing visualization for route: $routeId")
            }
            map.invalidate()
        }
    }

    fun getRouteOverlayStats(): Map<String, Any> {
        return mapOf(
            "waypoint_markers" to waypointMarkers.size,
            "route_lines" to routeLines.size,
            "cylinder_overlays" to cylinderOverlays.size,
            "interactive_mode" to interactiveMode,
            "selected_waypoint" to (selectedWaypointId ?: "none"),
            "performance_stats" to performanceStats
        )
    }
}