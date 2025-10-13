package com.madanala.tern.route

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import com.madanala.tern.model.Waypoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.content.Context

object WaypointOverlay {
    fun addMarker(mapView: MapView, waypoint: Waypoint, waypointStore: WaypointStore, onDragStateChanged: (Boolean) -> Unit = {}): Marker {
        val marker = Marker(mapView).apply {
            position = GeoPoint(waypoint.lat, waypoint.lon)
            title = waypoint.label ?: when (waypoint.type) {
                Waypoint.Type.LAUNCH -> "Launch"
                Waypoint.Type.LANDING -> "Landing"
                Waypoint.Type.TURNPOINT -> "Waypoint"
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            // Enable dragging for waypoint editing
            isDraggable = true

            // Set custom waypoint icon
            icon = BitmapDrawable(mapView.resources, createWaypointIcon(waypoint, waypointStore, mapView.context))

            // Add drag listener to update position in store
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker?) {
                    // Optional: Add visual feedback for drag start
                    onDragStateChanged(true)
                }

                override fun onMarkerDrag(marker: Marker?) {
                    // Update waypoint position during drag
                    marker?.position?.let { newPosition ->
                        waypointStore.updateWaypointPosition(waypoint.id, newPosition.latitude, newPosition.longitude)
                    }
                }

                override fun onMarkerDragEnd(marker: Marker?) {
                    // Final position update when drag ends
                    marker?.position?.let { newPosition ->
                        waypointStore.updateWaypointPosition(waypoint.id, newPosition.latitude, newPosition.longitude)
                    }
                    onDragStateChanged(false)
                }
            })
        }
        mapView.overlays.add(marker)
        mapView.invalidate()
        return marker
    }

    private fun createWaypointIcon(waypoint: Waypoint, waypointStore: WaypointStore, context: Context): Bitmap {
        return try {
            // Extract number from waypoint label (e.g., "WP 1" -> "1")
            val number = extractWaypointNumber(waypoint, waypointStore)

            // Create a 60x60 bitmap for the waypoint icon
            val bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Draw circle background using theme primary color (cyan)
            val paint = Paint().apply {
                color = 0xFF00BCD4.toInt() // colorPrimary from theme
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            val centerX = 30f
            val centerY = 30f
            val radius = 25f

            canvas.drawCircle(centerX, centerY, radius, paint)

            // Draw number text in center using theme onPrimary color
            paint.apply {
                color = 0xFFFFFFFF.toInt() // colorOnPrimary from theme
                textSize = 20f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }

            canvas.drawText(number, centerX, centerY + 7f, paint) // +7f for vertical centering

            bitmap
        } catch (e: Exception) {
            // Fallback to default marker if bitmap creation fails
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private fun extractWaypointNumber(waypoint: Waypoint, waypointStore: WaypointStore): String {
        // Find the waypoint's position in the route (1-indexed)
        val waypoints = waypointStore.waypoints.value
        val index = waypoints.indexOfFirst { it.id == waypoint.id }
        return if (index != -1) (index + 1).toString() else "?"
    }
}
