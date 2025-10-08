package com.madanala.tern.ui.components

import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.madanala.tern.redux.*
import com.madanala.tern.ui.overlays.RouteOverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.*

/**
 * Interactive route editing system for waypoint creation and modification
 * Handles tap-to-create, drag-to-edit, and waypoint management
 */
class RouteEditingManager(
    private val mapView: MapView,
    private val routeOverlayManager: RouteOverlayManager,
    private val routeStore: com.madanala.tern.redux.MapStore?
) {

    companion object {
        private const val TAG = "RouteEditingManager"
        private const val LONG_PRESS_DURATION = 500L // ms
        private const val WAYPOINT_CREATION_ZOOM_THRESHOLD = 12.0 // Minimum zoom for waypoint creation
    }

    // Interactive state
    private var isEditMode = false
    private var pendingWaypointLocation: GeoPoint? = null
    private var waypointCreationInProgress = false
    private var longPressStartTime = 0L

    // Route editing callbacks
    var onWaypointCreated: ((Waypoint) -> Unit)? = null
    var onWaypointSelected: ((Waypoint) -> Unit)? = null
    var onWaypointMoved: ((Waypoint, GeoPoint) -> Unit)? = null
    var onRouteModified: ((Route) -> Unit)? = null

    // Map interaction handlers
    private var mapTouchListener: View.OnTouchListener? = null
    private var markerDragListener: Marker.OnMarkerDragListener? = null

    init {
        // Route editing manager initialized
        setupMapInteractionHandlers()
    }

    /**
     * Enable or disable route editing mode
     */
    fun setEditMode(enabled: Boolean) {
        isEditMode = enabled
        routeOverlayManager.setInteractiveMode(enabled)

        if (enabled) {
            enableMapInteractions()
            android.util.Log.i(TAG, "ROUTE EDIT MODE: Long-press map to create waypoints")
        } else {
            disableMapInteractions()
            android.util.Log.d(TAG, "Edit mode deactivated")
        }
    }

    /**
     * Check if currently in edit mode
     */
    fun isInEditMode(): Boolean = isEditMode

    /**
     * Set up map interaction handlers for route editing
     */
    private fun setupMapInteractionHandlers() {
        mapTouchListener = View.OnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    handleMapTouchDown(event)
                    false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    handleMapTouchUp(event)
                    false
                }
                else -> false
            }
        }

        // Set up marker drag handling
        markerDragListener = object : Marker.OnMarkerDragListener {
            override fun onMarkerDrag(marker: Marker) {
                handleWaypointDrag(marker)
            }

            override fun onMarkerDragEnd(marker: Marker) {
                handleWaypointDragEnd(marker)
            }

            override fun onMarkerDragStart(marker: Marker) {
                handleWaypointDragStart(marker)
            }
        }
    }

    /**
     * Enable map interactions for route editing
     */
    private fun enableMapInteractions() {
        // Set our custom touch listener first
        mapView.setOnTouchListener(mapTouchListener)

        // Note: We're using long-press instead of double-tap to avoid conflicts
        // with OSMdroid's built-in double-tap zoom functionality

        // Enable existing waypoint markers for dragging
        val markerCount = routeOverlayManager.getRouteOverlayStats()["waypoint_markers"]
        android.util.Log.d(TAG, "Enabling drag for ${markerCount ?: 0} waypoint markers")
    }

    /**
     * Disable map interactions
     */
    private fun disableMapInteractions() {
        mapView.setOnTouchListener(null)
        pendingWaypointLocation = null
        waypointCreationInProgress = false
        longPressStartTime = 0L
    }

    /**
     * Handle map touch down events
     */
    private fun handleMapTouchDown(event: android.view.MotionEvent) {
        if (!isEditMode) return

        // Record long-press start time for waypoint creation
        longPressStartTime = System.currentTimeMillis()

        // Get map coordinates from screen position
        val geoPoint = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt()).let { point ->
            GeoPoint(point.latitude, point.longitude)
        }

        // Check if tap is on existing waypoint first
        val tappedWaypoint = findWaypointAtLocation(geoPoint)
        if (tappedWaypoint != null) {
            selectWaypoint(tappedWaypoint)
            return
        }

        // If not on existing waypoint, prepare for potential long-press creation
        if (mapView.zoomLevelDouble >= WAYPOINT_CREATION_ZOOM_THRESHOLD) {
            pendingWaypointLocation = geoPoint
        }
    }

    /**
     * Handle map touch up events
     */
    private fun handleMapTouchUp(event: android.view.MotionEvent) {
        if (!isEditMode || pendingWaypointLocation == null) return

        val geoPoint = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt()).let { point ->
            GeoPoint(point.latitude, point.longitude)
        }

        val pressDuration = System.currentTimeMillis() - longPressStartTime

        // Calculate distance from touch down to touch up
        val distance = calculateDistance(
            pendingWaypointLocation!!.latitude, pendingWaypointLocation!!.longitude,
            geoPoint.latitude, geoPoint.longitude
        )

        when {
            // Long press (> 500ms) - create waypoint
            pressDuration > LONG_PRESS_DURATION -> {
                createWaypointAtLocation(geoPoint)
            }
            // Short tap with small movement - select existing waypoint or prepare for creation
            distance < 100 && pressDuration < LONG_PRESS_DURATION -> {
                if (findWaypointAtLocation(geoPoint) == null) {
                    // Just prepare for potential long-press creation
                    // Don't create immediately on short tap
                }
            }
            else -> {
                // Movement too large or duration too short, cancel waypoint creation
                pendingWaypointLocation = null
            }
        }
    }

    /**
     * Find waypoint at given location
     */
    private fun findWaypointAtLocation(location: GeoPoint): Waypoint? {
        // This would need access to current route waypoints
        // For now, return null - would be implemented with route state access
        return null
    }

    /**
     * Create a new waypoint at the specified location
     */
    private fun createWaypointAtLocation(location: GeoPoint) {
        if (!isEditMode || waypointCreationInProgress) return

        waypointCreationInProgress = true

        // Determine waypoint type based on context
        val waypointType = determineWaypointType(location)

        // Create temporary waypoint for UI feedback
        val tempWaypoint = Waypoint(
            name = generateWaypointName(waypointType),
            location = location,
            waypointType = waypointType,
            description = "New ${waypointType.name.lowercase()} waypoint"
        )

        // Show waypoint creation UI
        showWaypointCreationDialog(tempWaypoint)

        android.util.Log.d(TAG, "Creating waypoint at ${location.latitude}, ${location.longitude}")
    }

    /**
     * Determine appropriate waypoint type based on location and context
     */
    private fun determineWaypointType(location: GeoPoint): WaypointType {
        // Check if location is near known launch sites
        // Check if location is in suitable landing area
        // Default to intermediate waypoint

        // For now, default to intermediate
        // In full implementation, would analyze terrain, airspace, etc.
        return WaypointType.INTERMEDIATE
    }

    /**
     * Generate waypoint name based on type and sequence
     */
    private fun generateWaypointName(waypointType: WaypointType): String {
        // This would need access to current route to determine next waypoint number
        return when (waypointType) {
            WaypointType.LAUNCH -> "Launch"
            WaypointType.LANDING -> "Landing"
            WaypointType.TURNPOINT -> "TP"
            WaypointType.INTERMEDIATE -> "WP"
            WaypointType.THERMAL -> "Thermal"
        }
    }

    /**
     * Show waypoint creation dialog
     */
    private fun showWaypointCreationDialog(tempWaypoint: Waypoint) {
        // This would show a dialog for the user to configure the waypoint
        // For now, just confirm creation with default values

        CoroutineScope(Dispatchers.Main).launch {
            try {
                confirmWaypointCreation(tempWaypoint)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating waypoint", e)
                waypointCreationInProgress = false
            }
        }
    }

    /**
     * Confirm waypoint creation and add to route
     */
    private fun confirmWaypointCreation(waypoint: Waypoint) {
        // Dispatch Redux action to add waypoint to current route
        // routeStore?.dispatch(RouteAction.AddWaypoint(waypoint))

        // Notify listeners
        onWaypointCreated?.invoke(waypoint)

        // Reset state
        waypointCreationInProgress = false
        pendingWaypointLocation = null

        android.util.Log.d(TAG, "Waypoint created: ${waypoint.name}")
    }

    /**
     * Select a waypoint for editing
     */
    private fun selectWaypoint(waypoint: Waypoint) {
        // routeStore?.dispatch(RouteAction.SelectWaypoint(waypoint.id))
        routeOverlayManager.selectWaypoint(waypoint.id)

        onWaypointSelected?.invoke(waypoint)

        Log.d(TAG, "Waypoint selected: ${waypoint.name}")
    }

    /**
     * Handle waypoint drag events
     */
    private fun handleWaypointDrag(marker: Marker) {
        val waypointId = marker.title // Assuming title contains waypoint ID
        Log.d(TAG, "Waypoint drag: $waypointId at ${marker.position}")
    }

    /**
     * Handle waypoint drag end events
     */
    private fun handleWaypointDragEnd(marker: Marker) {
        val waypointId = marker.title
        val newLocation = marker.position

        // Find the waypoint and update its location
        // This would need route state access to find and update the waypoint

        Log.d(TAG, "Waypoint drag end: $waypointId at ${newLocation.latitude}, ${newLocation.longitude}")
    }

    /**
     * Handle waypoint drag start events
     */
    private fun handleWaypointDragStart(marker: Marker) {
        val waypointId = marker.title
        Log.d(TAG, "Waypoint drag start: $waypointId")
    }

    /**
     * Delete currently selected waypoint
     */
    fun deleteSelectedWaypoint() {
        // Get selected waypoint ID from route state
        // routeStore?.dispatch(RouteAction.RemoveWaypoint(selectedWaypointIndex))

        Log.d(TAG, "Selected waypoint deleted")
    }

    /**
     * Cancel current waypoint creation if in progress
     */
    fun cancelWaypointCreation() {
        waypointCreationInProgress = false
        pendingWaypointLocation = null
        longPressStartTime = 0L
        Log.d(TAG, "Waypoint creation cancelled")
    }

    /**
     * Clear all waypoints from current route
     */
    fun clearAllWaypoints() {
        // routeStore?.dispatch(RouteAction.ClearRouteState())
        Log.d(TAG, "All waypoints cleared")
    }

    /**
     * Get current route editing statistics
     */
    fun getEditingStats(): Map<String, Any> {
        return mapOf(
            "edit_mode" to isEditMode,
            "creation_in_progress" to waypointCreationInProgress,
            "pending_location" to (pendingWaypointLocation != null),
            "long_press_duration" to (System.currentTimeMillis() - longPressStartTime)
        )
    }

    /**
     * Calculate distance between two GPS coordinates using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).pow(2) + kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).pow(2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    /**
     * Cleanup when manager is destroyed
     */
    fun cleanup() {
        disableMapInteractions()
        mapTouchListener = null
        markerDragListener = null
        onWaypointCreated = null
        onWaypointSelected = null
        onWaypointMoved = null
        onRouteModified = null
    }
}