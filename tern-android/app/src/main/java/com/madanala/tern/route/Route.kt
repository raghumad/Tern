package com.madanala.tern.route

import com.madanala.tern.model.Waypoint
import java.time.Instant
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route model for paragliding route planning.
 * Routes own their waypoints with strong relationships.
 */
data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Route",
    val waypoints: List<Waypoint> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val totalDistanceKm: Double = 0.0,
    val estimatedFlightTimeMinutes: Int = 0
) {

    /**
     * Add a waypoint to this route
     */
    fun addWaypoint(lat: Double, lon: Double, type: Waypoint.Type = Waypoint.Type.TURNPOINT, label: String? = null): Route {
        val waypoint = Waypoint(
            lat = lat,
            lon = lon,
            type = type,
            label = label,
            routeId = id
        )
        val updatedWaypoints = waypoints + waypoint
        return copy(
            waypoints = updatedWaypoints,
            updatedAt = Instant.now()
        ).calculateRouteMetrics()
    }

    /**
     * Remove a waypoint from this route
     */
    fun removeWaypoint(waypointId: String): Route {
        val updatedWaypoints = waypoints.filter { it.id != waypointId }
        return copy(
            waypoints = updatedWaypoints,
            updatedAt = Instant.now()
        ).calculateRouteMetrics()
    }

    /**
     * Update a waypoint in this route
     */
    fun updateWaypoint(waypointId: String, lat: Double? = null, lon: Double? = null, type: Waypoint.Type? = null, label: String? = null): Route {
        val updatedWaypoints = waypoints.map { waypoint ->
            if (waypoint.id == waypointId) {
                waypoint.copy(
                    lat = lat ?: waypoint.lat,
                    lon = lon ?: waypoint.lon,
                    type = type ?: waypoint.type,
                    label = label ?: waypoint.label
                )
            } else {
                waypoint
            }
        }
        return copy(
            waypoints = updatedWaypoints,
            updatedAt = Instant.now()
        ).calculateRouteMetrics()
    }

    /**
     * Calculate route metrics (distance, flight time)
     */
    private fun calculateRouteMetrics(): Route {
        if (waypoints.size < 2) {
            return copy(totalDistanceKm = 0.0, estimatedFlightTimeMinutes = 0)
        }

        var totalDistance = 0.0
        for (i in 0 until waypoints.size - 1) {
            val wp1 = waypoints[i]
            val wp2 = waypoints[i + 1]
            val distance = calculateDistance(wp1.lat, wp1.lon, wp2.lat, wp2.lon)
            totalDistance += distance
        }

        // Estimate flight time at 30 km/h average speed
        val estimatedTimeMinutes = (totalDistance / 30.0 * 60).toInt()

        return copy(
            totalDistanceKm = totalDistance,
            estimatedFlightTimeMinutes = estimatedTimeMinutes
        )
    }

    /**
     * Calculate distance between two points using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371.0 // km
        val dLat = kotlin.math.PI * (lat2 - lat1) / 180.0
        val dLon = kotlin.math.PI * (lon2 - lon1) / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(kotlin.math.PI * lat1 / 180.0) * cos(kotlin.math.PI * lat2 / 180.0) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    companion object {
        /**
         * Create a route from a list of waypoints
         */
        fun fromWaypoints(name: String, waypoints: List<Waypoint>): Route {
            val routeId = UUID.randomUUID().toString()
            val routeWaypoints = waypoints.map { it.copy(routeId = routeId) }
            return Route(
                id = routeId,
                name = name,
                waypoints = routeWaypoints
            ).calculateRouteMetrics()
        }
    }
}
