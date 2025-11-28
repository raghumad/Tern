package com.madanala.tern.model

object TrajectoryAnalyzer {

    /**
     * Format wind direction in degrees to Cardinal (N, NE, E, etc.)
     */
    fun formatWindDirection(degrees: Double): String {
        val normalized = (degrees % 360 + 360) % 360
        val directions = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val index = ((normalized + 11.25) / 22.5).toInt() % 16
        return directions[index]
    }

    /**
     * Calculate arrival times for waypoints based on avg speed
     * @param startTimeMillis Start time in millis
     * @param avgSpeedKmh Average speed in km/h
     * @param distancesKm List of cumulative distances to each waypoint
     * @return List of arrival timestamps
     */
    fun calculateArrivalTimes(startTimeMillis: Long, avgSpeedKmh: Double, distancesKm: List<Double>): List<Long> {
        return distancesKm.map { distance ->
            val hours = distance / avgSpeedKmh
            startTimeMillis + (hours * 3600 * 1000).toLong()
        }
    }
    
    /**
     * Analyze trajectory to find max risk and average wind
     */
    fun analyzeTrajectory(waypoints: List<WaypointWeather>): TrajectoryForecast {
        if (waypoints.isEmpty()) {
            return TrajectoryForecast(emptyList(), OverdevelopmentRisk("", 0.0, RiskLevel.LOW), 0.0)
        }
        
        // Find max risk
        val maxRisk = waypoints.map { 
            WeatherAnalyzer.analyzeOverdevelopmentRisk(it.forecast.cape) 
        }.maxByOrNull { it.maxCape } ?: OverdevelopmentRisk("", 0.0, RiskLevel.LOW)
        
        // Calculate average wind speed (simplified, ideally vector average)
        val avgWind = waypoints.map { wp ->
            wp.forecast.wind.map { it.speed }.average()
        }.average()
        
        return TrajectoryForecast(waypoints, maxRisk, avgWind)
    }
}
