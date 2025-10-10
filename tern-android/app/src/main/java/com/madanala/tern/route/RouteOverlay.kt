package com.madanala.tern.route

import android.graphics.Color
import com.madanala.tern.model.Waypoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

object RouteOverlay {
    private var currentLine: Polyline? = null

    fun redraw(mapView: MapView, waypoints: List<Waypoint>) {
        // Remove existing polyline
        currentLine?.let { mapView.overlays.remove(it) }

        if (waypoints.size < 2) {
            mapView.invalidate()
            return
        }

        val line = Polyline(mapView).apply {
            setPoints(waypoints.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = Color.rgb(0, 120, 255)
            outlinePaint.strokeWidth = 6f
            isGeodesic = true
        }

        mapView.overlays.add(line)
        currentLine = line
        mapView.invalidate()
    }
}
