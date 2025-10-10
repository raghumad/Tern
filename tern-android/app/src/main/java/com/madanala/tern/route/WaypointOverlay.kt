package com.madanala.tern.route

import com.madanala.tern.model.Waypoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

object WaypointOverlay {
    fun addMarker(mapView: MapView, waypoint: Waypoint): Marker {
        val marker = Marker(mapView).apply {
            position = GeoPoint(waypoint.lat, waypoint.lon)
            title = waypoint.label ?: "Waypoint"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(marker)
        mapView.invalidate()
        return marker
    }
}
