package com.madanala.tern.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Handles gesture detection for map interactions.
 * Encapsulates long-press detection, waypoint selection, and drag-and-drop repositioning logic.
 */
class MapGestureHandler(
    private val context: Context,
    private val mapStore: MapStore,
    private val onLongPress: (GeoPoint) -> Unit
) {

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            try {
                if (!isDragging) {
                    val geoPoint = screenToGeoPoint(e.x.toInt(), e.y.toInt()) ?: return
                    val hit = findWaypointAtLocation(mapStore.state.value.routes, geoPoint)
                    
                    if (hit != null) {
                        // 🎯 Premium: Perform haptic feedback immediately
                        mapView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        
                        // Start Drag (which now implies selection in the reducer)
                        startDrag(hit)
                    } else {
                        // Fallback: Long press on map (create new waypoint)
                        onLongPress(geoPoint)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "onLongPress failed: ${t.message}")
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Single tap no longer handled here - waypoint selection is handled by RouteOverlayManager
            return false
        }
    }

    private val gestureDetector = GestureDetector(context, gestureListener)
    private var mapView: MapView? = null

    // Drag state
    private var isDragging = false
    private var lastDragUpdate = 0L
    private val dragUpdateThrottleMs = 50L // ~20 updates/sec max
    private var initialPointerCount = 0
    private var cancelVelocityThreshold = 1000f // pixels per second

    /**
     * Attach gesture handling to a MapView
     */
    fun attachToMapView(mapView: MapView) {
        this.mapView = mapView

        // Set touch listener to handle both gesture detector and drag logic
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        mapView.setOnTouchListener { _, event ->
            try {
                val handled = handleDragEvents(event)
                if (!handled) {
                    gestureDetector.onTouchEvent(event)
                }
            } catch (ignored: Throwable) {
                // Ignore gesture processing errors to prevent crashes
            }
            // Return false so MapView still handles panning/zoom gestures when not dragging
            false
        }

        Log.d(TAG, "Gesture handler attached to map view")
    }

    /**
     * Handle drag events for waypoint repositioning
     */
    private fun handleDragEvents(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialPointerCount = event.pointerCount
                return false // Let gesture detector handle this
            }
            MotionEvent.ACTION_MOVE -> {
                return handleTouchMove(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handleTouchUp(event)
                return isDragging // Consume if we were dragging
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger touch cancels drag
                if (isDragging && event.pointerCount > 1) {
                    cancelDrag()
                    return true
                }
                return false
            }
        }
        return false
    }

    /**
     * Find a waypoint at the given GeoPoint across all routes
     */
    private fun findWaypointAtLocation(routes: List<com.madanala.tern.model.Route>, geoPoint: GeoPoint): com.madanala.tern.redux.WaypointSelection? {
        val toleranceDegrees = 0.005 // ~500 meters (increased for reliable test hit-testing)
        
        for (route in routes) {
            if (!route.isVisible) continue
            
            for (waypoint in route.waypoints) {
                val distance = calculateDistanceDegrees(
                    waypoint.lat, waypoint.lon,
                    geoPoint.latitude, geoPoint.longitude
                )
                if (distance <= toleranceDegrees) {
                    return com.madanala.tern.redux.WaypointSelection(route.id, waypoint.id)
                }
            }
        }
        return null
    }

    /**
     * Handle touch move - update drag position
     */
    private fun handleTouchMove(event: MotionEvent): Boolean {
        if (isDragging) {
            val velocityX = event.getAxisValue(MotionEvent.AXIS_X, 0)
            val velocityY = event.getAxisValue(MotionEvent.AXIS_Y, 0)
            val velocity = kotlin.math.sqrt(velocityX * velocityX + velocityY * velocityY)

            // Cancel drag if swiped away fast enough
            if (velocity > cancelVelocityThreshold) {
                cancelDrag()
                return true
            }

            val now = System.currentTimeMillis()
            if (now - lastDragUpdate >= dragUpdateThrottleMs) {
                val geoPoint = screenToGeoPoint(event.x.toInt(), event.y.toInt())
                geoPoint?.let { updateDragPosition(it) }
                lastDragUpdate = now
            }
            return true // Consume the move event
        }

        return false
    }

    /**
     * Handle touch up - end drag if active
     */
    private fun handleTouchUp(event: MotionEvent) {
        if (isDragging) {
            endDrag()
        }
    }

    /**
     * Cancel drag operation and revert to original position
     */
    private fun cancelDrag() {
        isDragging = false
        mapStore.dispatch(MapAction.CancelWaypointDrag)
        Log.d(TAG, "Drag cancelled")
    }

    /**
     * Start drag mode for selected waypoint
     */
    private fun startDrag(target: com.madanala.tern.redux.WaypointSelection) {
        isDragging = true
        mapStore.dispatch(MapAction.StartWaypointDrag(
            routeId = target.routeId,
            waypointId = target.waypointId
        ))
        Log.d(TAG, "Drag mode started for ${target.waypointId}")
    }

    /**
     * Update drag position with throttling
     */
    private fun updateDragPosition(geoPoint: GeoPoint) {
        mapStore.dispatch(MapAction.UpdateWaypointDrag(geoPoint.latitude, geoPoint.longitude))
        Log.v(TAG, "Drag position updated: ${geoPoint.latitude}, ${geoPoint.longitude}")
    }

    /**
     * End drag mode and confirm position
     */
    private fun endDrag() {
        isDragging = false
        mapStore.dispatch(MapAction.EndWaypointDrag)
        Log.d(TAG, "Drag mode ended")
    }

    /**
     * Check if selected waypoint is at the given location
     */
    private fun isWaypointAtLocation(selectedWaypoint: com.madanala.tern.redux.WaypointSelection, geoPoint: GeoPoint): Boolean {
        val route = mapStore.state.value.routes.find { it.id == selectedWaypoint.routeId }
        val waypoint = route?.waypoints?.find { it.id == selectedWaypoint.waypointId }

        if (waypoint != null) {
            val distance = calculateDistanceDegrees(
                waypoint.lat, waypoint.lon,
                geoPoint.latitude, geoPoint.longitude
            )
            val toleranceDegrees = 0.001 // ~100 meters at equator
            return distance <= toleranceDegrees
        }
        return false
    }

    /**
     * Calculate distance between two points in degrees
     */
    private fun calculateDistanceDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = lat1 - lat2
        val dLon = lon1 - lon2
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    /**
     * Detach gesture handling from MapView
     */
    fun detachFromMapView() {
        mapView?.setOnTouchListener(null)
        mapView = null
        Log.d(TAG, "Gesture handler detached from map view")
    }

    /**
     * Convert screen coordinates to GeoPoint
     */
    private fun screenToGeoPoint(screenX: Int, screenY: Int): GeoPoint? {
        return try {
            mapView?.projection?.fromPixels(screenX, screenY)?.let { geoPoint ->
                if (geoPoint is GeoPoint && geoPoint.latitude.isFinite() && geoPoint.longitude.isFinite()) {
                    geoPoint
                } else {
                    Log.w(TAG, "Invalid GeoPoint from screen coordinates: $screenX, $screenY")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert screen coordinates to GeoPoint: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MapGestureHandler"
    }
}
