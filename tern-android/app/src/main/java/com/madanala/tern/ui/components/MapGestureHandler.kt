package com.madanala.tern.ui.components

import android.content.Context
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Handles gesture detection for map interactions.
 * Encapsulates long-press detection and coordinate conversion logic.
 */
class MapGestureHandler(
    private val context: Context,
    private val onLongPress: (GeoPoint) -> Unit,
    private val onSingleTap: ((GeoPoint) -> Unit)? = null
) {

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            try {
                // Convert screen coordinates to GeoPoint
                val geoPoint = screenToGeoPoint(e.x.toInt(), e.y.toInt())
                geoPoint?.let { onLongPress(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "onLongPress failed: ${t.message}")
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            try {
                // Convert screen coordinates to GeoPoint for tap handling
                val geoPoint = screenToGeoPoint(e.x.toInt(), e.y.toInt())
                geoPoint?.let { onSingleTap?.invoke(it) }
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "onSingleTapConfirmed failed: ${t.message}")
                return false
            }
        }
    }

    private val gestureDetector = GestureDetector(context, gestureListener)
    private var mapView: MapView? = null

    /**
     * Attach gesture handling to a MapView
     */
    fun attachToMapView(mapView: MapView) {
        this.mapView = mapView

        // Set touch listener to forward events to gesture detector
        mapView.setOnTouchListener { _, event ->
            try {
                gestureDetector.onTouchEvent(event)
            } catch (ignored: Throwable) {
                // Ignore gesture processing errors to prevent crashes
            }
            // Return false so MapView still handles panning/zoom gestures
            false
        }

        Log.d(TAG, "Gesture handler attached to map view")
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
                    Log.d(TAG, "Long press detected at: ${geoPoint.latitude}, ${geoPoint.longitude}")
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
