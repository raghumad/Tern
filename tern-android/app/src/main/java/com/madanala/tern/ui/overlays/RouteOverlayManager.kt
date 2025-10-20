package com.madanala.tern.ui.overlays

import android.content.Context
import android.util.Log
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.route.Route
import com.madanala.tern.route.RouteColor
import com.madanala.tern.route.RouteOverlay
import com.madanala.tern.route.WaypointOverlay
import com.madanala.tern.route.RouteStore
import com.madanala.tern.route.WaypointStore
import com.madanala.tern.utils.MemoryPressureLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Route overlay manager for waypoint and route visualization
 * Extends BaseOverlayManager following Redux patterns
 */
class RouteOverlayManager(
    context: Context,
    store: MapStore?
) : BaseOverlayManager(OverlayType.ROUTES, store) {

    private val waypointMarkers = mutableMapOf<String, Marker>()

    init {
        // Set up route store observation for reactive updates to multiple routes
        coroutineScope.launch {
            RouteStore.routes.collect { routes ->
                onRoutesChanged(routes)
            }
        }
    }

    override fun onOverlayAttached() {
        Log.d(TAG, "Route overlay manager attached")
        // Call parent initialization first
        // Initial waypoint rendering will be handled by WaypointStore observation
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "Route overlay manager detached")
        clearOverlays()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        // Route overlays don't need distance-based filtering like other overlays
        // They show all waypoints regardless of map position for route editing
    }

    override fun onViewportChangedInternal(viewport: BoundingBox) {
        // Update waypoint visibility based on viewport for performance
        updateWaypointVisibility(viewport)
    }

    override fun onReduxStateChanged(state: MapState) {
        // Handle route overlay configuration changes
        val routesConfig = state.overlayState.routes
        if (!routesConfig.enabled) {
            clearOverlays()
        }
        // Update visibility based on config
        updateAllWaypointVisibility()
    }

    override fun clearOverlays() {
        mapView?.let { mapView ->
            // Remove waypoint markers
            waypointMarkers.values.forEach { marker ->
                mapView.overlays.remove(marker)
            }
            waypointMarkers.clear()

            // Clear all route polylines
            RouteOverlay.clearAllRoutes(mapView)

            mapView.invalidate()
        }
        Log.d(TAG, "All route overlays cleared")
    }

        private fun onRoutesChanged(routes: List<Route>) {
        if (!isAttached || !isEnabled()) return

        coroutineScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.Main) {
                updateWaypointMarkers(routes)
                updateRoutePolylines(routes)
            }
        }
    }

    private fun updateWaypointMarkers(routes: List<Route>) {
        mapView?.let { mapView ->
            // Get all waypoint IDs from all visible routes
            val visibleRoutes = routes.filter { route -> route.isVisible }
            val allWaypoints = visibleRoutes.flatMap { route -> route.waypoints }
            val currentIds = allWaypoints.map { it.id }.toSet()

            // Remove waypoints that no longer exist in any visible route
            val toRemove = waypointMarkers.keys.filter { id -> id !in currentIds }
            toRemove.forEach { removeWaypointMarker(it) }

            // Add or update existing waypoints from all visible routes
            allWaypoints.forEach { waypoint ->
                updateWaypointMarker(mapView, waypoint)
            }

            mapView.invalidate()
        }
    }

    private fun updateWaypointMarker(mapView: MapView, waypoint: Waypoint) {
        val existingMarker = waypointMarkers[waypoint.id]
        if (existingMarker != null) {
            // Update existing waypoint position
            existingMarker.position = GeoPoint(waypoint.lat, waypoint.lon)
        } else {
            // Find the route that contains this waypoint to get its color
            val routeWithWaypoint = RouteStore.routes.value.find { route ->
                route.waypoints.any { wp -> wp.id == waypoint.id }
            }
            val routeColor = routeWithWaypoint?.color ?: RouteColor.DEFAULT

            // Create new waypoint marker using the static method
            val marker = WaypointOverlay.addMarker(
                mapView = mapView,
                waypoint = waypoint,
                waypointStore = WaypointStore,
                routeColor = routeColor
            )
            waypointMarkers[waypoint.id] = marker
        }
    }

    private fun removeWaypointMarker(waypointId: String) {
        val marker = waypointMarkers.remove(waypointId)
        marker?.let { mapView?.overlays?.remove(it) }
    }

        private fun updateRoutePolylines(routes: List<Route>) {
        mapView?.let { mapView ->
            // Draw polylines for each visible route separately with proper styling
            val visibleRoutes = routes.filter { route -> route.isVisible }

            // Clear existing route polylines first
            RouteOverlay.clearAllRoutes(mapView)

            // Draw each visible route as a separate polyline with its specific color
            visibleRoutes.forEach { route ->
                if (route.waypoints.size >= 2) {
                        RouteOverlay.redraw(mapView, route.id, route.waypoints, route.color)
                }
            }
        }
    }

    private fun updateWaypointVisibility(viewport: BoundingBox) {
        // For route editing, we typically want to show all waypoints
        // But we can optimize by hiding waypoints far outside the visible area
        val margin = 0.1 // 10% margin around viewport
        val extendedViewport = BoundingBox(
            viewport.latNorth + margin,
            viewport.lonEast + margin,
            viewport.latSouth - margin,
            viewport.lonWest - margin
        )

        waypointMarkers.values.forEach { marker ->
            val markerPosition = marker.position
            val isVisible = markerPosition.latitude in extendedViewport.latSouth..extendedViewport.latNorth &&
                           markerPosition.longitude in extendedViewport.lonWest..extendedViewport.lonEast
            marker.isEnabled = isVisible
        }
    }

    private fun updateAllWaypointVisibility() {
        mapView?.let { mapView ->
            val viewport = mapView.boundingBox ?: return
            updateWaypointVisibility(viewport)
        }
    }

    override fun removeInvisibleOverlays(): Int {
        var removed = 0
        mapView?.let { mapView ->
            val viewport = mapView.boundingBox ?: return 0
            val iterator = waypointMarkers.entries.iterator()

            while (iterator.hasNext()) {
                val (id, marker) = iterator.next()
                val markerPosition = marker.position
                val isVisible = markerPosition.latitude in viewport.latSouth..viewport.latNorth &&
                               markerPosition.longitude in viewport.lonWest..viewport.lonEast

                if (!isVisible) {
                    mapView.overlays.remove(marker)
                    iterator.remove()
                    removed++
                }
            }

            if (removed > 0) {
                mapView.invalidate()
            }
        }
        return removed
    }

    override fun clearOverlaysInZone(zone: com.madanala.tern.utils.DistanceZone): Int {
        // For routes, we don't use distance zones like other overlays
        // Route waypoints should be preserved for route editing context
        return 0
    }

    override fun preserveSafetyCriticalOverlays(): Int {
        // Route overlays are not safety-critical like airspace
        return 0
    }

    override fun onMemoryStateChanged(memoryState: com.madanala.tern.utils.ApplicationMemoryState) {
        // Route overlays require aviation-safety-aware memory management
        // Routes may be critical for active flight planning, so we balance cleanup with safety

        try {
            when (memoryState.calculatedPressure) {
                MemoryPressureLevel.CRITICAL_MEMORY -> {
                    // Critical memory: Aggressive cleanup but preserve essential route data
                    Log.w(TAG, "Critical memory pressure detected - performing emergency route cleanup")
                    handleCriticalMemoryCleanup(memoryState)
                }

                MemoryPressureLevel.LOW_MEMORY -> {
                    // Low memory: Moderate cleanup with route preservation
                    Log.i(TAG, "Low memory pressure detected - performing moderate route cleanup")
                    handleLowMemoryCleanup(memoryState)
                }

                MemoryPressureLevel.MEDIUM_MEMORY -> {
                    // Medium memory: Light cleanup, maintain route visibility
                    Log.d(TAG, "Medium memory pressure detected - performing light route optimization")
                    handleMediumMemoryCleanup(memoryState)
                }

                MemoryPressureLevel.HIGH_MEMORY -> {
                    // High memory: No cleanup needed, ensure optimal route display
                    Log.v(TAG, "High memory availability - ensuring optimal route display")
                    handleHighMemoryOptimization(memoryState)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling memory state change: ${memoryState.calculatedPressure}", e)
            // On error, perform minimal safe cleanup to prevent memory issues
            performEmergencyRouteCleanup()
        }
    }

    /**
     * Handle critical memory pressure with aviation safety preservation
     * Clears polylines but preserves waypoints for emergency route recreation
     */
    private fun handleCriticalMemoryCleanup(memoryState: com.madanala.tern.utils.ApplicationMemoryState) {
        coroutineScope.launch(Dispatchers.Main) {
            try {
                mapView?.let { mapView ->
                    val visibleRoutes = RouteStore.getVisibleRoutes()
                    val totalWaypoints = RouteStore.getTotalWaypointCount()
                    Log.d(TAG, "Critical cleanup: Clearing route polylines, preserving ${totalWaypoints} waypoints across ${visibleRoutes.size} routes")

                    // Clear only the polylines to free memory while preserving waypoints
                    RouteOverlay.clearAllRoutes(mapView)

                    // Force garbage collection hint for immediate memory relief
                    System.gc()

                    Log.i(TAG, "Critical memory cleanup completed - freed polyline memory")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during critical memory cleanup", e)
            }
        }
    }

    /**
     * Handle low memory pressure with balanced cleanup strategy
     * Reduces polyline complexity while maintaining route visibility
     */
    private fun handleLowMemoryCleanup(memoryState: com.madanala.tern.utils.ApplicationMemoryState) {
        coroutineScope.launch(Dispatchers.Main) {
            try {
                mapView?.let { mapView ->
                    val allRoutes = RouteStore.routes.value
                    val visibleRoutes = RouteStore.getVisibleRoutes()
                    val totalWaypoints = RouteStore.getTotalWaypointCount()

                    if (totalWaypoints > 10) {
                        Log.d(TAG, "Low memory cleanup: Simplifying route polylines for ${totalWaypoints} waypoints across ${visibleRoutes.size} routes")

                        // For routes with many waypoints, we can simplify the polylines
                        // while keeping all waypoints visible for editing
                        val allWaypoints = visibleRoutes.flatMap { route -> route.waypoints }
                        RouteOverlay.redraw(mapView, allWaypoints)
                    } else {
                        Log.v(TAG, "Low memory cleanup: Route size optimal (${totalWaypoints} waypoints across ${allRoutes.size} routes)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during low memory cleanup", e)
            }
        }
    }

    /**
     * Handle medium memory pressure with performance optimization
     * Ensures smooth route display without unnecessary complexity
     */
    private fun handleMediumMemoryCleanup(memoryState: com.madanala.tern.utils.ApplicationMemoryState) {
        coroutineScope.launch(Dispatchers.Main) {
            try {
                mapView?.let { mapView ->
                    val allRoutes = RouteStore.routes.value
                    val visibleRoutes = RouteStore.getVisibleRoutes()
                    val totalWaypoints = RouteStore.getTotalWaypointCount()

                    // For medium memory, ensure routes are properly displayed
                    // but don't perform unnecessary redraws
                    if (totalWaypoints > 0) {
                        Log.v(TAG, "Medium memory optimization: Ensuring route visibility for ${totalWaypoints} waypoints across ${visibleRoutes.size} routes")
                        val allWaypoints = visibleRoutes.flatMap { route -> route.waypoints }
                        RouteOverlay.redraw(mapView, allWaypoints)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during medium memory cleanup", e)
            }
        }
    }

    /**
     * Handle high memory availability with optimal route display
     * Ensures best possible route visualization
     */
    private fun handleHighMemoryOptimization(memoryState: com.madanala.tern.utils.ApplicationMemoryState) {
        coroutineScope.launch(Dispatchers.Main) {
            try {
                mapView?.let { mapView ->
                    val allRoutes = RouteStore.routes.value
                    val visibleRoutes = RouteStore.getVisibleRoutes()
                    val totalWaypoints = RouteStore.getTotalWaypointCount()

                    // In high memory conditions, ensure optimal route display
                    if (totalWaypoints > 0) {
                        Log.v(TAG, "High memory optimization: Ensuring optimal route display for ${totalWaypoints} waypoints across ${visibleRoutes.size} routes")
                        val allWaypoints = visibleRoutes.flatMap { route -> route.waypoints }
                        RouteOverlay.redraw(mapView, allWaypoints)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during high memory optimization", e)
            }
        }
    }

    /**
     * Emergency cleanup for error conditions
     * Minimal safe cleanup to prevent memory issues
     */
    private fun performEmergencyRouteCleanup() {
        coroutineScope.launch(Dispatchers.Main) {
            try {
                mapView?.let { mapView ->
                    Log.w(TAG, "Emergency cleanup: Clearing route overlays due to error condition")
                    RouteOverlay.clearAllRoutes(mapView)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during emergency cleanup", e)
            }
        }
    }

    /**
     * Get current route statistics for debugging
     */
    fun getRouteStats(): Map<String, Any> {
        val waypointCount = waypointMarkers.size
        val routeStats = RouteStore.getRouteStats()

        return mapOf(
            "waypoint_markers" to waypointCount,
            "total_routes" to (routeStats["total_routes"] ?: 0),
            "current_route_id" to (routeStats["current_route_id"] ?: "none"),
            "total_waypoints" to (routeStats["total_waypoints"] ?: 0),
            "visible_routes" to (routeStats["visible_routes"] ?: 0),
            "is_enabled" to isEnabled(),
            "is_attached" to isAttached
        )
    }
}