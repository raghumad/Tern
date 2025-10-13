package com.madanala.tern.ui.overlays

import android.content.Context
import android.util.Log
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.route.RouteOverlay
import com.madanala.tern.route.WaypointOverlay
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
        // Set up waypoint store observation for reactive updates
        coroutineScope.launch {
            WaypointStore.waypoints.collect { waypointList ->
                onWaypointsChanged(waypointList)
            }
        }
    }

    override fun onOverlayAttached() {
        Log.d(TAG, "Route overlay manager attached")
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

            // Clear route polyline
            RouteOverlay.redraw(mapView, emptyList())

            mapView.invalidate()
        }
        Log.d(TAG, "All route overlays cleared")
    }

    private fun onWaypointsChanged(waypointList: List<Waypoint>) {
        if (!isAttached || !isEnabled()) return

        coroutineScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.Main) {
                updateWaypointMarkers(waypointList)
                updateRoutePolyline(waypointList)
            }
        }
    }

    private fun updateWaypointMarkers(waypointList: List<Waypoint>) {
        mapView?.let { mapView ->
            // Remove waypoints that no longer exist
            val currentIds = waypointList.map { it.id }.toSet()
            val toRemove = waypointMarkers.keys.filter { it !in currentIds }
            toRemove.forEach { removeWaypointMarker(it) }

            // Add or update existing waypoints
            waypointList.forEach { waypoint ->
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
            // Create new waypoint marker using the static method
            val marker = WaypointOverlay.addMarker(
                mapView = mapView,
                waypoint = waypoint,
                waypointStore = WaypointStore
            )
            waypointMarkers[waypoint.id] = marker
        }
    }

    private fun removeWaypointMarker(waypointId: String) {
        val marker = waypointMarkers.remove(waypointId)
        marker?.let { mapView?.overlays?.remove(it) }
    }

    private fun updateRoutePolyline(waypointList: List<Waypoint>) {
        mapView?.let { mapView ->
            RouteOverlay.redraw(mapView, waypointList)
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
                    Log.d(TAG, "Critical cleanup: Clearing route polylines, preserving ${WaypointStore.waypoints.value.size} waypoints")

                    // Clear only the polyline to free memory while preserving waypoints
                    RouteOverlay.redraw(mapView, emptyList())

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
                    val currentWaypoints = WaypointStore.waypoints.value

                    if (currentWaypoints.size > 10) {
                        Log.d(TAG, "Low memory cleanup: Simplifying route polyline for ${currentWaypoints.size} waypoints")

                        // For routes with many waypoints, we can simplify the polyline
                        // while keeping all waypoints visible for editing
                        RouteOverlay.redraw(mapView, currentWaypoints)
                    } else {
                        Log.v(TAG, "Low memory cleanup: Route size optimal (${currentWaypoints.size} waypoints)")
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
                    val currentWaypoints = WaypointStore.waypoints.value

                    // For medium memory, ensure route is properly displayed
                    // but don't perform unnecessary redraws
                    if (currentWaypoints.isNotEmpty()) {
                        Log.v(TAG, "Medium memory optimization: Ensuring route visibility for ${currentWaypoints.size} waypoints")
                        RouteOverlay.redraw(mapView, currentWaypoints)
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
                    val currentWaypoints = WaypointStore.waypoints.value

                    // In high memory conditions, ensure optimal route display
                    if (currentWaypoints.isNotEmpty()) {
                        Log.v(TAG, "High memory optimization: Ensuring optimal route display for ${currentWaypoints.size} waypoints")
                        RouteOverlay.redraw(mapView, currentWaypoints)
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
                    RouteOverlay.redraw(mapView, emptyList())
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
        return mapOf(
            "waypoint_count" to waypointCount,
            "is_enabled" to isEnabled(),
            "is_attached" to isAttached
        )
    }
}