package com.ternparagliding.model

import com.ternparagliding.utils.diagnostics.trackAllocation
import kotlin.math.*

/**
 * TrajectoryAnalyzer: Core paragliding logic for 4D flight path analysis.
 * Calculates Estimated Time of Arrival (ETA) for each waypoint in a route.
 */
object TrajectoryAnalyzer {

    private const val KNOTS_TO_KMH = 1.852
    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Calculate ETAs for all waypoints in a route.
     * @param route The planned route
     * @param averageSpeedKnots Pilot's estimated ground speed in knots
     * @param startTime Start time timestamp (ms). Defaults to current time.
     * @return Map of Waypoint ID to ETA timestamp (ms)
     */
    fun calculateETAs(
        route: Route,
        averageSpeedKnots: Double,
        startTime: Long = System.currentTimeMillis()
    ): Map<String, Long> {
        trackAllocation("TrajectoryAnalyzer.calculateETAs", 256L)
        
        if (route.waypoints.isEmpty()) return emptyMap()
        
        val etas = mutableMapOf<String, Long>()
        val speedKmh = averageSpeedKnots * KNOTS_TO_KMH
        
        // Start waypoint is at startTime
        var currentTime = startTime
        etas[route.waypoints[0].id] = currentTime
        
        if (speedKmh <= 0.1) {
            // If speed is negligible, all ETAs are effectively "unknown" or far future
            // but for UI continuity, we'll mark them as startTime
            route.waypoints.drop(1).forEach { etas[it.id] = startTime }
            return etas
        }

        for (i in 0 until route.waypoints.size - 1) {
            val start = route.waypoints[i]
            val end = route.waypoints[i + 1]
            
            val distanceKm = calculateDistance(start.lat, start.lon, end.lat, end.lon)
            val timeToNextHours = distanceKm / speedKmh
            val timeToNextMs = (timeToNextHours * 3600 * 1000).toLong()
            
            currentTime += timeToNextMs
            etas[end.id] = currentTime
        }
        
        return etas
    }

    /**
     * Helper to calculate distance between two GPS coordinates using Haversine formula.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }
}
