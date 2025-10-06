package com.madanala.tern.model

import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Weather-based routing for optimal paragliding flight paths
 * Advanced algorithms for thermal, ridge, and weather-optimized routing
 */
class WeatherRouter {

    companion object {
        private const val EARTH_RADIUS = 6371000.0 // meters
        private const val THERMAL_INFLUENCE_RADIUS = 2000.0 // meters
        private const val WIND_OPTIMAL_SPEED = 15.0 // knots for cross-country
        private const val MIN_THERMAL_STRENGTH = 1.0 // m/s minimum for soaring
        private const val ROUTE_CALCULATION_INTERVAL = 100.0 // meters between waypoints
    }

    /**
     * Weather-optimized route calculation result
     */
    data class WeatherRoute(
        val waypoints: List<GeoPoint>,
        val totalDistance: Double, // meters
        val estimatedDuration: Double, // seconds
        val thermalOpportunities: List<ThermalOpportunity>,
        val windAssistance: Double, // average knots of tailwind
        val routeQuality: RouteQuality,
        val riskFactors: List<RiskFactor>
    )

    /**
     * Potential thermal opportunity along route
     */
    data class ThermalOpportunity(
        val location: GeoPoint,
        val strength: Double, // m/s
        val quality: ThermalQuality,
        val altitude: Double, // meters MSL
        val confidence: Double // 0.0-1.0
    )

    /**
     * Route quality assessment
     */
    enum class RouteQuality {
        DANGEROUS,  // High risk, avoid
        POOR,       // Limited soaring potential
        FAIR,       // Some opportunities
        GOOD,       // Good soaring conditions
        EXCELLENT   // Perfect for cross-country
    }

    /**
     * Thermal quality for soaring assessment
     */
    enum class ThermalQuality {
        NONE,       // No thermal activity
        WEAK,       // Light, inconsistent thermals
        MODERATE,   // Good for local soaring
        STRONG,     // Excellent for cross-country
        EXTREME     // Competition-level thermals
    }

    /**
     * Risk factors that could affect flight safety
     */
    data class RiskFactor(
        val location: GeoPoint,
        val riskType: RiskType,
        val severity: RiskSeverity,
        val description: String
    )

    /**
     * Types of aviation risks
     */
    enum class RiskType {
        STRONG_WIND,    // Winds too strong for safe flight
        TURBULENCE,     // Turbulent conditions
        AIRSPACE,       // Restricted airspace
        TERRAIN,        // Dangerous terrain features
        WEATHER,        // Severe weather (thunderstorms, etc.)
        LANDING_ZONE    // Poor landing options
    }

    /**
     * Risk severity levels
     */
    enum class RiskSeverity {
        LOW,        // Minor concern
        MODERATE,   // Worth attention
        HIGH,       // Significant risk
        CRITICAL    // Flight-threatening
    }

    /**
     * Weather data for routing calculations
     */
    data class WeatherDataPoint(
        val location: GeoPoint,
        val windSpeed: Double, // knots
        val windDirection: Double, // degrees
        val thermalStrength: Double, // m/s
        val cloudBase: Double, // meters MSL
        val visibility: Double, // km
        val precipitation: Double, // mm/h
        val temperature: Double, // Celsius
        val timestamp: Long
    )

    /**
     * Calculate optimal route based on weather conditions
     */
    fun calculateOptimalRoute(
        startPoint: GeoPoint,
        endPoint: GeoPoint,
        weatherData: Map<GeoPoint, WeatherDataPoint>,
        maxDetour: Double = 0.3, // Allow 30% longer route for better conditions
        prioritizeSafety: Boolean = true
    ): WeatherRoute {

        // Generate candidate routes
        val candidateRoutes = generateCandidateRoutes(startPoint, endPoint, maxDetour)

        // Score each route based on weather conditions
        val scoredRoutes = candidateRoutes.map { route ->
            Pair(route, scoreRoute(route, weatherData, prioritizeSafety))
        }

        // Select best route
        val bestRoute = scoredRoutes.maxByOrNull { it.second.totalScore }?.first
            ?: generateDirectRoute(startPoint, endPoint)

        // Analyze thermal opportunities along the route
        val thermalOpportunities = identifyThermalOpportunities(bestRoute, weatherData)

        // Assess risk factors
        val riskFactors = identifyRiskFactors(bestRoute, weatherData)

        // Calculate route quality
        val routeQuality = assessRouteQuality(thermalOpportunities, riskFactors, weatherData)

        // Estimate wind assistance
        val windAssistance = calculateWindAssistance(bestRoute, weatherData)

        return WeatherRoute(
            waypoints = bestRoute,
            totalDistance = calculateRouteDistance(bestRoute),
            estimatedDuration = estimateFlightDuration(bestRoute, windAssistance),
            thermalOpportunities = thermalOpportunities,
            windAssistance = windAssistance,
            routeQuality = routeQuality,
            riskFactors = riskFactors
        )
    }

    /**
     * Generate candidate routes with different strategies
     */
    private fun generateCandidateRoutes(
        startPoint: GeoPoint,
        endPoint: GeoPoint,
        maxDetour: Double
    ): List<List<GeoPoint>> {
        val routes = mutableListOf<List<GeoPoint>>()

        // Direct route
        routes.add(generateDirectRoute(startPoint, endPoint))

        // Thermal-seeking route (deviate toward better thermal areas)
        routes.add(generateThermalSeekingRoute(startPoint, endPoint, maxDetour))

        // Wind-optimized route (follow tailwind)
        routes.add(generateWindOptimizedRoute(startPoint, endPoint, maxDetour))

        // Safety-optimized route (avoid high-risk areas)
        routes.add(generateSafetyOptimizedRoute(startPoint, endPoint, maxDetour))

        return routes
    }

    /**
     * Generate direct route between two points
     */
    private fun generateDirectRoute(startPoint: GeoPoint, endPoint: GeoPoint): List<GeoPoint> {
        val route = mutableListOf<GeoPoint>()
        val distance = calculateDistance(startPoint.latitude, startPoint.longitude,
                                       endPoint.latitude, endPoint.longitude)

        val steps = max(10, (distance / ROUTE_CALCULATION_INTERVAL).toInt())
        val latStep = (endPoint.latitude - startPoint.latitude) / steps
        val lonStep = (endPoint.longitude - startPoint.longitude) / steps

        for (i in 0..steps) {
            val lat = startPoint.latitude + (latStep * i)
            val lon = startPoint.longitude + (lonStep * i)
            route.add(GeoPoint(lat, lon))
        }

        return route
    }

    /**
     * Generate route that seeks thermal opportunities
     */
    private fun generateThermalSeekingRoute(
        startPoint: GeoPoint,
        endPoint: GeoPoint,
        maxDetour: Double
    ): List<GeoPoint> {
        // Simplified thermal-seeking algorithm
        // In practice, this would use thermal prediction models

        val directRoute = generateDirectRoute(startPoint, endPoint)
        val modifiedRoute = mutableListOf<GeoPoint>()

        directRoute.forEachIndexed { index, point ->
            var lat = point.latitude
            var lon = point.longitude

            // Simulate thermal influence (simplified)
            if (index % 3 == 0) { // Every 3rd point, consider thermal detour
                val thermalInfluence = sin(index * 0.5) * 0.01 * maxDetour
                lat += thermalInfluence
                lon += cos(index * 0.3) * 0.01 * maxDetour
            }

            modifiedRoute.add(GeoPoint(lat, lon))
        }

        return modifiedRoute
    }

    /**
     * Generate wind-optimized route
     */
    private fun generateWindOptimizedRoute(
        startPoint: GeoPoint,
        endPoint: GeoPoint,
        maxDetour: Double
    ): List<GeoPoint> {
        // Simplified wind optimization
        // In practice, this would use wind field analysis

        val directRoute = generateDirectRoute(startPoint, endPoint)
        val windOptimizedRoute = mutableListOf<GeoPoint>()

        directRoute.forEach { point ->
            var lat = point.latitude
            var lon = point.longitude

            // Simulate wind influence (simplified)
            val windEffect = 0.005 * maxDetour // Simplified wind adjustment
            lon += windEffect // Assume tailwind from west

            windOptimizedRoute.add(GeoPoint(lat, lon))
        }

        return windOptimizedRoute
    }

    /**
     * Generate safety-optimized route
     */
    private fun generateSafetyOptimizedRoute(
        startPoint: GeoPoint,
        endPoint: GeoPoint,
        maxDetour: Double
    ): List<GeoPoint> {
        // Simplified safety optimization
        // In practice, this would avoid terrain, airspace, and weather hazards

        return generateDirectRoute(startPoint, endPoint) // For now, same as direct
    }

    /**
     * Score a route based on weather conditions and safety
     */
    private fun scoreRoute(
        route: List<GeoPoint>,
        weatherData: Map<GeoPoint, WeatherDataPoint>,
        prioritizeSafety: Boolean
    ): RouteScore {
        var thermalScore = 0.0
        var windScore = 0.0
        var safetyScore = 0.0
        var distanceScore = 0.0

        val directDistance = calculateDistance(
            route.first().latitude, route.first().longitude,
            route.last().latitude, route.last().longitude
        )

        // Score each segment of the route
        route.zipWithNext().forEach { (start, end) ->
            val segmentWeather = interpolateWeatherData(start, end, weatherData)
            val segmentDistance = calculateDistance(start.latitude, start.longitude,
                                                  end.latitude, end.longitude)

            // Thermal scoring
            thermalScore += scoreThermalConditions(segmentWeather) * (segmentDistance / directDistance)

            // Wind scoring
            windScore += scoreWindConditions(segmentWeather) * (segmentDistance / directDistance)

            // Safety scoring
            safetyScore += scoreSafetyConditions(segmentWeather) * (segmentDistance / directDistance)
        }

        // Distance penalty (longer routes score lower unless they provide significant benefits)
        val distanceMultiplier = directDistance / max(directDistance, calculateRouteDistance(route))
        distanceScore = distanceMultiplier * 100

        // Weight scores based on priority
        val weights = if (prioritizeSafety) {
            Triple(0.2, 0.2, 0.6) // Thermal, Wind, Safety
        } else {
            Triple(0.4, 0.4, 0.2) // Favor performance over safety
        }

        val totalScore = (thermalScore * weights.first +
                         windScore * weights.second +
                         safetyScore * weights.third +
                         distanceScore)

        return RouteScore(
            totalScore = totalScore,
            thermalScore = thermalScore,
            windScore = windScore,
            safetyScore = safetyScore,
            distanceScore = distanceScore
        )
    }

    /**
     * Score thermal conditions for soaring potential
     */
    private fun scoreThermalConditions(weather: WeatherDataPoint?): Double {
        if (weather == null) return 0.0

        return when {
            weather.thermalStrength < 0.5 -> 0.0
            weather.thermalStrength < 1.0 -> 25.0
            weather.thermalStrength < 2.0 -> 50.0
            weather.thermalStrength < 3.0 -> 75.0
            else -> 100.0
        }
    }

    /**
     * Score wind conditions for flight efficiency
     */
    private fun scoreWindConditions(weather: WeatherDataPoint?): Double {
        if (weather == null) return 50.0 // Neutral score for unknown conditions

        return when {
            weather.windSpeed < 5 -> 60.0  // Too light for efficient soaring
            weather.windSpeed < 15 -> 100.0 // Ideal for cross-country
            weather.windSpeed < 25 -> 70.0  // Strong but manageable
            else -> 20.0 // Too strong for safe flight
        }
    }

    /**
     * Score safety conditions
     */
    private fun scoreSafetyConditions(weather: WeatherDataPoint?): Double {
        if (weather == null) return 50.0

        var safetyScore = 100.0

        // Penalize high winds
        if (weather.windSpeed > 25) safetyScore -= 50
        else if (weather.windSpeed > 20) safetyScore -= 25

        // Penalize poor visibility
        if (weather.visibility < 5) safetyScore -= 30
        else if (weather.visibility < 10) safetyScore -= 15

        // Penalize precipitation
        if (weather.precipitation > 2) safetyScore -= 40
        else if (weather.precipitation > 0.5) safetyScore -= 20

        return max(0.0, safetyScore)
    }

    /**
     * Interpolate weather data between two points
     */
    private fun interpolateWeatherData(
        start: GeoPoint,
        end: GeoPoint,
        weatherData: Map<GeoPoint, WeatherDataPoint>
    ): WeatherDataPoint? {
        // Find nearest weather data points
        val startWeather = weatherData.minByOrNull { (point, _) ->
            calculateDistance(start.latitude, start.longitude, point.latitude, point.longitude)
        }?.value

        val endWeather = weatherData.minByOrNull { (point, _) ->
            calculateDistance(end.latitude, end.longitude, point.latitude, point.longitude)
        }?.value

        // Simple interpolation (in practice, would use more sophisticated methods)
        return if (startWeather != null && endWeather != null) {
            WeatherDataPoint(
                location = GeoPoint((start.latitude + end.latitude) / 2,
                                  (start.longitude + end.longitude) / 2),
                windSpeed = (startWeather.windSpeed + endWeather.windSpeed) / 2,
                windDirection = (startWeather.windDirection + endWeather.windDirection) / 2,
                thermalStrength = (startWeather.thermalStrength + endWeather.thermalStrength) / 2,
                cloudBase = (startWeather.cloudBase + endWeather.cloudBase) / 2,
                visibility = (startWeather.visibility + endWeather.visibility) / 2,
                precipitation = (startWeather.precipitation + endWeather.precipitation) / 2,
                temperature = (startWeather.temperature + endWeather.temperature) / 2,
                timestamp = (startWeather.timestamp + endWeather.timestamp) / 2
            )
        } else null
    }

    /**
     * Identify thermal opportunities along a route
     */
    private fun identifyThermalOpportunities(
        route: List<GeoPoint>,
        weatherData: Map<GeoPoint, WeatherDataPoint>
    ): List<ThermalOpportunity> {
        return route.mapNotNull { point ->
            val weather = weatherData[point] ?: return@mapNotNull null

            if (weather.thermalStrength >= MIN_THERMAL_STRENGTH) {
                ThermalOpportunity(
                    location = point,
                    strength = weather.thermalStrength,
                    quality = classifyThermalQuality(weather.thermalStrength),
                    altitude = weather.cloudBase,
                    confidence = calculateThermalConfidence(weather)
                )
            } else null
        }
    }

    /**
     * Identify risk factors along a route
     */
    private fun identifyRiskFactors(
        route: List<GeoPoint>,
        weatherData: Map<GeoPoint, WeatherDataPoint>
    ): List<RiskFactor> {
        val risks = mutableListOf<RiskFactor>()

        route.forEach { point ->
            val weather = weatherData[point] ?: return@forEach

            // Check for high wind risk
            if (weather.windSpeed > 25) {
                risks.add(RiskFactor(
                    location = point,
                    riskType = RiskType.STRONG_WIND,
                    severity = RiskSeverity.HIGH,
                    description = "Winds exceeding 25 knots"
                ))
            }

            // Check for poor visibility
            if (weather.visibility < 3) {
                risks.add(RiskFactor(
                    location = point,
                    riskType = RiskType.WEATHER,
                    severity = RiskSeverity.MODERATE,
                    description = "Poor visibility conditions"
                ))
            }

            // Check for precipitation
            if (weather.precipitation > 1) {
                risks.add(RiskFactor(
                    location = point,
                    riskType = RiskType.WEATHER,
                    severity = RiskSeverity.HIGH,
                    description = "Active precipitation"
                ))
            }
        }

        return risks
    }

    /**
     * Assess overall route quality
     */
    private fun assessRouteQuality(
        thermalOpportunities: List<ThermalOpportunity>,
        riskFactors: List<RiskFactor>,
        weatherData: Map<GeoPoint, WeatherDataPoint>
    ): RouteQuality {
        val avgThermalStrength = thermalOpportunities.map { it.strength }.average()
        val highRiskCount = riskFactors.count { it.severity == RiskSeverity.HIGH ||
                                              it.severity == RiskSeverity.CRITICAL }

        return when {
            highRiskCount > 0 -> RouteQuality.DANGEROUS
            avgThermalStrength < 0.5 -> RouteQuality.POOR
            avgThermalStrength < 1.0 -> RouteQuality.FAIR
            avgThermalStrength < 2.0 -> RouteQuality.GOOD
            else -> RouteQuality.EXCELLENT
        }
    }

    /**
     * Calculate average wind assistance along route
     */
    private fun calculateWindAssistance(
        route: List<GeoPoint>,
        weatherData: Map<GeoPoint, WeatherDataPoint>
    ): Double {
        val windAssistanceValues = route.mapNotNull { point ->
            weatherData[point]?.let { weather ->
                // Calculate tailwind component (simplified)
                val tailwindComponent = weather.windSpeed * cos(Math.toRadians(weather.windDirection))
                tailwindComponent.coerceAtLeast(0.0) // Only count tailwind
            }
        }

        return if (windAssistanceValues.isNotEmpty()) {
            windAssistanceValues.average()
        } else 0.0
    }

    /**
     * Estimate flight duration based on distance and conditions
     */
    private fun estimateFlightDuration(route: List<GeoPoint>, windAssistance: Double): Double {
        val distance = calculateRouteDistance(route)

        // Base speed estimate (simplified)
        val baseSpeed = 20.0 // knots average cross-country speed

        // Adjust for wind assistance
        val effectiveSpeed = baseSpeed + windAssistance

        return if (effectiveSpeed > 5.0) {
            (distance / 1000.0) / effectiveSpeed * 3600.0 // seconds
        } else Double.MAX_VALUE
    }

    /**
     * Calculate total route distance
     */
    private fun calculateRouteDistance(route: List<GeoPoint>): Double {
        return route.zipWithNext().sumOf { (start, end) ->
            calculateDistance(start.latitude, start.longitude, end.latitude, end.longitude)
        }
    }

    /**
     * Classify thermal quality for soaring assessment
     */
    private fun classifyThermalQuality(strength: Double): ThermalQuality {
        return when {
            strength < 0.5 -> ThermalQuality.NONE
            strength < 1.0 -> ThermalQuality.WEAK
            strength < 2.0 -> ThermalQuality.MODERATE
            strength < 3.0 -> ThermalQuality.STRONG
            else -> ThermalQuality.EXTREME
        }
    }

    /**
     * Calculate confidence in thermal prediction
     */
    private fun calculateThermalConfidence(weather: WeatherDataPoint): Double {
        // Simplified confidence calculation
        // In practice, would use thermal model confidence, data age, etc.

        var confidence = 0.5 // Base confidence

        // Higher confidence for stronger thermals
        confidence += min(0.3, weather.thermalStrength / 10.0)

        // Higher confidence for better visibility (better thermal detection)
        confidence += min(0.2, weather.visibility / 50.0)

        return confidence.coerceIn(0.0, 1.0)
    }

    /**
     * Calculate distance between two GPS coordinates using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS * c
    }
}

/**
 * Route scoring for optimization algorithms
 */
data class RouteScore(
    val totalScore: Double,
    val thermalScore: Double,
    val windScore: Double,
    val safetyScore: Double,
    val distanceScore: Double
)