package com.madanala.tern.route

import com.madanala.tern.model.Waypoint
import java.time.Instant
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Route calculation constants
private const val EARTH_RADIUS_KM = 6371.0
private const val AVERAGE_FLIGHT_SPEED_KMH = 30.0
private const val MINUTES_PER_HOUR = 60

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
    val estimatedFlightTimeMinutes: Int = 0,
    val isVisible: Boolean = true,
    val legDistances: List<Double> = emptyList(),
    val routeType: RouteType = RouteType.OPEN_DISTANCE,
    val faiPoints: Double = 0.0
) {
    enum class RouteType { OPEN_DISTANCE, FLAT_TRIANGLE, FAI_TRIANGLE }

    /**
     * Add a waypoint to this route
     */
    fun addWaypoint(lat: Double, lon: Double, type: Waypoint.Type = Waypoint.Type.TURNPOINT, label: String? = null): Route {
        val newWaypoint = Waypoint(
            lat = lat,
            lon = lon,
            type = type,
            label = label,
            routeId = this.id
        )
        return copy(
            waypoints = waypoints + newWaypoint,
            updatedAt = Instant.now()
        ).calculateRouteMetrics()
    }

    /**
     * Remove a waypoint from this route
     */
    fun removeWaypoint(waypointId: String): Route {
        return copy(
            waypoints = waypoints.filter { it.id != waypointId },
            updatedAt = Instant.now()
        ).calculateRouteMetrics()
    }

    /**
     * Update a waypoint in this route
     */
    fun updateWaypoint(waypointId: String, lat: Double? = null, lon: Double? = null, type: Waypoint.Type? = null): Route {
        return copy(
            waypoints = waypoints.map {
                if (it.id == waypointId) {
                    it.copy(
                        lat = lat ?: it.lat,
                        lon = lon ?: it.lon,
                        type = type ?: it.type
                    )
                } else it
            },
            updatedAt = Instant.now()
        ).calculateRouteMetrics()
    }

    /**
     * Reorder a waypoint in this route
     */
    fun reorderWaypoint(fromIndex: Int, toIndex: Int): Route {
        if (fromIndex < 0 || fromIndex >= waypoints.size || toIndex < 0 || toIndex >= waypoints.size || fromIndex == toIndex) {
            return this
        }

        val mutableWaypoints = waypoints.toMutableList()
        val waypoint = mutableWaypoints.removeAt(fromIndex)
        mutableWaypoints.add(toIndex, waypoint)

        return copy(
            waypoints = mutableWaypoints,
            updatedAt = Instant.now()
        ).calculateRouteMetrics()
    }

    /**
     * Calculate route metrics (distance, flight time, scoring)
     */
    private fun calculateRouteMetrics(): Route {
        if (waypoints.size < 2) {
            return copy(
                totalDistanceKm = 0.0,
                estimatedFlightTimeMinutes = 0,
                legDistances = emptyList(),
                routeType = RouteType.OPEN_DISTANCE,
                faiPoints = 0.0
            )
        }

        var distance = 0.0
        val legs = mutableListOf<Double>()

        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            val legDist = calculateDistance(p1.lat, p1.lon, p2.lat, p2.lon)
            distance += legDist
            legs.add(legDist)
        }

        // Check for closing the loop (Triangle)
        val isClosedLoop = if (waypoints.size >= 3) {
            val start = waypoints.first()
            val end = waypoints.last()
            val gap = calculateDistance(start.lat, start.lon, end.lat, end.lon)
            // Rule: Gap must be less than 20% of total distance to be considered a closed triangle attempt
            // For simplicity in this MVP, let's say if start and end are very close (< 400m) it's a closed loop
            gap < 0.4
        } else false

        var type = RouteType.OPEN_DISTANCE
        var points = distance // 1 point per km for open distance

        if (isClosedLoop && waypoints.size == 4) { // Start -> TP1 -> TP2 -> Start (3 legs)
             // Triangle logic
             val leg1 = legs[0]
             val leg2 = legs[1]
             val leg3 = legs[2]
             val totalTriDist = leg1 + leg2 + leg3

             // FAI Triangle rule: Shortest leg must be at least 28% of total distance
             val shortest = minOf(leg1, minOf(leg2, leg3))
             if (shortest >= 0.28 * totalTriDist) {
                 type = RouteType.FAI_TRIANGLE
                 points = totalTriDist * 2.0 // FAI triangles often worth more, e.g. 2.0 multiplier (simplified)
             } else {
                 type = RouteType.FLAT_TRIANGLE
                 points = totalTriDist * 1.5 // Flat triangles worth 1.5 (simplified)
             }
        }

        // Estimate flight time (assuming avg speed of 30 km/h for paraglider)
        val timeMinutes = (distance / 30.0 * 60).toInt()

        return copy(
            totalDistanceKm = distance,
            estimatedFlightTimeMinutes = timeMinutes,
            legDistances = legs,
            routeType = type,
            faiPoints = points
        )
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
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
