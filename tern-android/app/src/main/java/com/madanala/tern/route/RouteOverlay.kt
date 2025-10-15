package com.madanala.tern.route

import android.graphics.Color
import com.madanala.tern.model.Waypoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

object RouteOverlay {
    private var routeLines = mutableMapOf<String, Polyline>()

    fun redraw(mapView: MapView, waypoints: List<Waypoint>) {
        redraw(mapView, waypoints, RouteColor.DEFAULT)
    }

    /**
     * Redraw route with specified color styling for a specific route
     */
    fun redraw(mapView: MapView, waypoints: List<Waypoint>, routeColor: RouteColor = RouteColor.DEFAULT) {
        if (waypoints.size < 2) {
            return
        }

        // Create a unique key for this route based on waypoints
        val routeKey = waypoints.joinToString { "${it.lat},${it.lon}" }

        // Remove existing polyline for this route
        routeLines[routeKey]?.let { mapView.overlays.remove(it) }

        val line = Polyline(mapView).apply {
            setPoints(waypoints.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = routeColor.polylineColor
            outlinePaint.strokeWidth = routeColor.polylineWidth
            isGeodesic = true
        }

        mapView.overlays.add(line)
        routeLines[routeKey] = line
        mapView.invalidate()
    }

    /**
     * Redraw route with route ID for better route management
     */
    fun redraw(mapView: MapView, routeId: String, waypoints: List<Waypoint>, routeColor: RouteColor = RouteColor.DEFAULT) {
        if (waypoints.size < 2) {
            return
        }

        // Remove existing polyline for this route ID
        routeLines[routeId]?.let { mapView.overlays.remove(it) }

        val line = Polyline(mapView).apply {
            setPoints(waypoints.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = routeColor.polylineColor
            outlinePaint.strokeWidth = routeColor.polylineWidth
            isGeodesic = true
        }

        mapView.overlays.add(line)
        routeLines[routeId] = line
        mapView.invalidate()
    }

    /**
     * Clear all route polylines from the map
     */
    fun clearAllRoutes(mapView: MapView) {
        routeLines.values.forEach { mapView.overlays.remove(it) }
        routeLines.clear()
        mapView.invalidate()
    }

    /**
     * Clear a specific route polyline
     */
    fun clearRoute(mapView: MapView, waypoints: List<Waypoint>) {
        if (waypoints.isEmpty()) return

        val routeKey = waypoints.joinToString { "${it.lat},${it.lon}" }
        routeLines[routeKey]?.let { mapView.overlays.remove(it) }
        routeLines.remove(routeKey)
        mapView.invalidate()
    }

    /**
     * Clear a specific route polyline by route ID
     */
    fun clearRoute(mapView: MapView, routeId: String) {
        routeLines[routeId]?.let { mapView.overlays.remove(it) }
        routeLines.remove(routeId)
        mapView.invalidate()
    }
}
