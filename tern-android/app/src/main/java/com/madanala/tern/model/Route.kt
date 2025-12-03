package com.madanala.tern.model

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
    val isVisible: Boolean = true
) {
    init {
        com.madanala.tern.utils.trackAllocation("Route", 128L + waypoints.size * 64L)
    }

    // Computed properties derived from waypoints
    val totalDistanceKm: Double
        get() = calculateTotalDistance()

    val estimatedFlightTimeMinutes: Int
        get() = (totalDistanceKm / AVERAGE_FLIGHT_SPEED_KMH * MINUTES_PER_HOUR).toInt()

    val legDistances: List<Double>
        get() = calculateLegDistances()

    val routeType: RouteType
        get() = calculateRouteType()

    val faiPoints: Double
        get() = calculateFaiPoints()

    enum class RouteType { OPEN_DISTANCE, FLAT_TRIANGLE, FAI_TRIANGLE }

    /**
     * Add a waypoint to this route
     */
    fun addWaypoint(
        lat: Double, 
        lon: Double, 
        type: Waypoint.Type = Waypoint.Type.TURNPOINT, 
        label: String? = null, 
        id: String? = null, 
        radius: Double? = 400.0,
        alt: Double? = null,
        openTime: String? = null,
        closeTime: String? = null
    ): Route {
        val newWaypoint = Waypoint(
            lat = lat,
            lon = lon,
            type = type,
            label = label,
            routeId = this.id,
            id = id ?: UUID.randomUUID().toString(),
            radius = radius,
            alt = alt,
            openTime = openTime,
            closeTime = closeTime
        )
        return copy(
            waypoints = waypoints + newWaypoint,
            updatedAt = Instant.now()
        )
    }

    /**
     * Remove a waypoint from this route
     */
    fun removeWaypoint(waypointId: String): Route {
        return copy(
            waypoints = waypoints.filter { it.id != waypointId },
            updatedAt = Instant.now()
        )
    }

    /**
     * Update a waypoint in this route
     */
    fun updateWaypoint(
        waypointId: String, 
        lat: Double? = null, 
        lon: Double? = null, 
        type: Waypoint.Type? = null, 
        radius: Double? = null,
        alt: Double? = null,
        openTime: String? = null,
        closeTime: String? = null
    ): Route {
        return copy(
            waypoints = waypoints.map {
                if (it.id == waypointId) {
                    it.copy(
                        lat = lat ?: it.lat,
                        lon = lon ?: it.lon,
                        type = type ?: it.type,
                        radius = radius ?: it.radius,
                        alt = alt ?: it.alt,
                        openTime = openTime ?: it.openTime,
                        closeTime = closeTime ?: it.closeTime
                    )
                } else it
            },
            updatedAt = Instant.now()
        )
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
        )
    }

    private fun calculateTotalDistance(): Double {
        if (waypoints.size < 2) return 0.0
        var distance = 0.0
        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            distance += calculateDistance(p1.lat, p1.lon, p2.lat, p2.lon)
        }
        return distance
    }

    private fun calculateLegDistances(): List<Double> {
        if (waypoints.size < 2) return emptyList()
        val legs = mutableListOf<Double>()
        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            legs.add(calculateDistance(p1.lat, p1.lon, p2.lat, p2.lon))
        }
        return legs
    }

    private fun calculateRouteType(): RouteType {
        if (waypoints.size < 3) return RouteType.OPEN_DISTANCE
        
        // Check for closing the loop (Triangle)
        val start = waypoints.first()
        val end = waypoints.last()
        val gap = calculateDistance(start.lat, start.lon, end.lat, end.lon)
        // Rule: Gap must be less than 20% of total distance to be considered a closed triangle attempt
        // For simplicity in this MVP, let's say if start and end are very close (< 400m) it's a closed loop
        val isClosedLoop = gap < 0.4

        if (isClosedLoop && waypoints.size == 4) { // Start -> TP1 -> TP2 -> Start (3 legs)
             val legs = legDistances
             if (legs.size < 3) return RouteType.OPEN_DISTANCE // Should not happen if size is 4
             
             val leg1 = legs[0]
             val leg2 = legs[1]
             val leg3 = legs[2]
             val totalTriDist = leg1 + leg2 + leg3

             // FAI Triangle rule: Shortest leg must be at least 28% of total distance
             val shortest = minOf(leg1, minOf(leg2, leg3))
             if (shortest >= 0.28 * totalTriDist) {
                 return RouteType.FAI_TRIANGLE
             } else {
                 return RouteType.FLAT_TRIANGLE
             }
        }
        return RouteType.OPEN_DISTANCE
    }

    private fun calculateFaiPoints(): Double {
        val dist = totalDistanceKm
        return when (routeType) {
            RouteType.FAI_TRIANGLE -> dist * 2.0
            RouteType.FLAT_TRIANGLE -> dist * 1.5
            RouteType.OPEN_DISTANCE -> dist
        }
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
            )
        }
    }
}
