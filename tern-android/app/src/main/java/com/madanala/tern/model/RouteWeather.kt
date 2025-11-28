package com.madanala.tern.model

data class RouteWeather(
    val routeId: String,
    val forecast: WeatherForecast,
    val trajectoryForecast: TrajectoryForecast? = null
)

data class TrajectoryForecast(
    val waypoints: List<WaypointWeather>,
    val maxRisk: OverdevelopmentRisk,
    val avgHeadwind: Double
)

data class WaypointWeather(
    val waypointId: String,
    val estimatedArrival: Long, // Timestamp
    val forecast: WeatherForecast // Forecast at that specific time
)

data class WeatherForecast(
    val skewT: SkewTForecast,
    val cape: List<CapePoint>,
    val wind: List<WindPoint>
)

data class WindPoint(
    val altitude: Double,
    val speed: Double, // km/h
    val direction: Double // degrees
)
