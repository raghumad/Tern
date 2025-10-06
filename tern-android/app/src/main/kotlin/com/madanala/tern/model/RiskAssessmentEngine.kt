package com.madanala.tern.model

import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Comprehensive risk assessment engine for aviation safety
 * Integrates airspace, terrain, and weather risk analysis for safe flight planning
 */
class RiskAssessmentEngine {

    companion object {
        private const val AIRSPACE_BUFFER_DISTANCE = 500.0 // meters buffer around restricted airspace
        private const val TERRAIN_CLEARANCE_MINIMUM = 100.0 // meters minimum terrain clearance
        private const val WIND_SAFETY_LIMIT = 25.0 // knots maximum safe wind for paragliding
        private const val VISIBILITY_MINIMUM = 3000.0 // meters minimum visibility
        private const val PRECIPITATION_LIMIT = 2.0 // mm/h precipitation limit
    }

    /**
     * Comprehensive risk assessment result
     */
    data class RiskAssessment(
        val location: GeoPoint,
        val overallRisk: RiskLevel,
        val riskFactors: List<RiskFactor>,
        val safetyScore: Double, // 0-100, higher is safer
        val recommendedActions: List<String>,
        val validUntil: Long, // timestamp until assessment is valid
        val assessmentRadius: Double // meters radius this assessment covers
    )

    /**
     * Individual risk factor analysis
     */
    data class RiskFactor(
        val type: RiskType,
        val severity: RiskLevel,
        val description: String,
        val location: GeoPoint?,
        val distance: Double, // meters from assessment point
        val mitigation: String?
    )

    /**
     * Risk severity levels
     */
    enum class RiskLevel {
        NEGLIGIBLE, // Minimal risk, safe to ignore
        LOW,        // Minor risk, worth noting
        MODERATE,   // Significant risk, requires attention
        HIGH,       // Serious risk, careful evaluation needed
        CRITICAL    // Extreme risk, avoid area
    }

    /**
     * Types of aviation risks
     */
    enum class RiskType {
        AIRSPACE_RESTRICTION,   // Prohibited or restricted airspace
        AIRSPACE_PROXIMITY,     // Close to restricted airspace
        TERRAIN_COLLISION,      // Dangerous terrain features
        TERRAIN_CLEARANCE,      // Insufficient terrain clearance
        WEATHER_WIND,          // Dangerous wind conditions
        WEATHER_VISIBILITY,    // Poor visibility conditions
        WEATHER_PRECIPITATION,  // Active precipitation
        WEATHER_TURBULENCE,     // Turbulent conditions
        LANDING_AVAILABILITY,   // Limited safe landing options
        OBSTACLE_PROXIMITY      // Power lines, towers, buildings
    }

    /**
     * Airspace data for risk assessment
     */
    data class AirspaceData(
        val name: String,
        val type: String, // Restricted, Prohibited, Danger, etc.
        val floor: Double, // meters MSL
        val ceiling: Double, // meters MSL
        val boundary: List<GeoPoint>,
        val schedule: String? = null, // Operating hours/restrictions
        val contact: String? = null // Contact frequency/information
    )

    /**
     * Terrain data for risk assessment
     */
    data class TerrainData(
        val elevation: Double, // meters MSL
        val slope: Double, // degrees
        val terrainType: String, // Mountain, Hill, Valley, Water, Urban
        val vegetation: String? = null,
        val obstacles: List<Obstacle> = emptyList()
    )

    /**
     * Obstacle data (power lines, towers, etc.)
     */
    data class Obstacle(
        val type: String, // PowerLine, Tower, Building, Tree
        val height: Double, // meters AGL
        val location: GeoPoint,
        val radius: Double = 50.0 // meters danger radius
    )

    /**
     * Perform comprehensive risk assessment for a location
     */
    fun assessLocationRisk(
        location: GeoPoint,
        currentAltitude: Double, // meters MSL
        airspaces: List<AirspaceData> = emptyList(),
        terrainData: TerrainData? = null,
        weatherData: WeatherRouter.WeatherDataPoint? = null,
        assessmentRadius: Double = 1000.0 // meters
    ): RiskAssessment {

        val riskFactors = mutableListOf<RiskFactor>()
        var totalRiskScore = 100.0 // Start with perfect safety score

        // Assess airspace risks
        val airspaceRisks = assessAirspaceRisks(location, currentAltitude, airspaces)
        riskFactors.addAll(airspaceRisks)
        totalRiskScore -= airspaceRisks.sumOf { calculateRiskPenalty(it.severity) }

        // Assess terrain risks
        val terrainRisks = assessTerrainRisks(location, currentAltitude, terrainData)
        riskFactors.addAll(terrainRisks)
        totalRiskScore -= terrainRisks.sumOf { calculateRiskPenalty(it.severity) }

        // Assess weather risks
        val weatherRisks = assessWeatherRisks(location, weatherData)
        riskFactors.addAll(weatherRisks)
        totalRiskScore -= weatherRisks.sumOf { calculateRiskPenalty(it.severity) }

        // Ensure score doesn't go below 0
        val safetyScore = max(0.0, totalRiskScore)

        // Determine overall risk level
        val overallRisk = determineOverallRisk(riskFactors)

        // Generate recommended actions
        val recommendedActions = generateRecommendedActions(riskFactors, overallRisk)

        // Assessment valid for 30 minutes (weather and airspace can change)
        val validUntil = System.currentTimeMillis() + (30 * 60 * 1000)

        return RiskAssessment(
            location = location,
            overallRisk = overallRisk,
            riskFactors = riskFactors,
            safetyScore = safetyScore,
            recommendedActions = recommendedActions,
            validUntil = validUntil,
            assessmentRadius = assessmentRadius
        )
    }

    /**
     * Assess airspace-related risks
     */
    private fun assessAirspaceRisks(
        location: GeoPoint,
        currentAltitude: Double,
        airspaces: List<AirspaceData>
    ): List<RiskFactor> {
        val risks = mutableListOf<RiskFactor>()

        airspaces.forEach { airspace ->
            val distanceToAirspace = calculateDistanceToPolygon(location, airspace.boundary)
            val altitudeInAirspace = currentAltitude in airspace.floor..airspace.ceiling

            when {
                // Inside restricted airspace
                distanceToAirspace == 0.0 && altitudeInAirspace -> {
                    risks.add(RiskFactor(
                        type = RiskType.AIRSPACE_RESTRICTION,
                        severity = RiskLevel.CRITICAL,
                        description = "Inside restricted airspace: ${airspace.name}",
                        location = location,
                        distance = 0.0,
                        mitigation = "Immediately exit airspace and contact ATC"
                    ))
                }

                // Very close to restricted airspace
                distanceToAirspace < AIRSPACE_BUFFER_DISTANCE && altitudeInAirspace -> {
                    risks.add(RiskFactor(
                        type = RiskType.AIRSPACE_PROXIMITY,
                        severity = RiskLevel.HIGH,
                        description = "Near restricted airspace: ${airspace.name}",
                        location = location,
                        distance = distanceToAirspace,
                        mitigation = "Maintain safe distance and monitor altitude"
                    ))
                }

                // Close to airspace boundary
                distanceToAirspace < AIRSPACE_BUFFER_DISTANCE * 2 -> {
                    risks.add(RiskFactor(
                        type = RiskType.AIRSPACE_PROXIMITY,
                        severity = RiskLevel.MODERATE,
                        description = "Near airspace boundary: ${airspace.name}",
                        location = location,
                        distance = distanceToAirspace,
                        mitigation = "Be aware of airspace restrictions"
                    ))
                }
            }
        }

        return risks
    }

    /**
     * Assess terrain-related risks
     */
    private fun assessTerrainRisks(
        location: GeoPoint,
        currentAltitude: Double,
        terrainData: TerrainData?
    ): List<RiskFactor> {
        val risks = mutableListOf<RiskFactor>()

        if (terrainData == null) return risks

        // Check terrain clearance
        val terrainClearance = currentAltitude - terrainData.elevation
        if (terrainClearance < TERRAIN_CLEARANCE_MINIMUM) {
            risks.add(RiskFactor(
                type = RiskType.TERRAIN_CLEARANCE,
                severity = if (terrainClearance < 50) RiskLevel.CRITICAL else RiskLevel.HIGH,
                description = "Insufficient terrain clearance: ${terrainClearance.toInt()}m",
                location = location,
                distance = 0.0,
                mitigation = "Gain altitude immediately"
            ))
        }

        // Check steep terrain
        if (terrainData.slope > 30) {
            risks.add(RiskFactor(
                type = RiskType.TERRAIN_COLLISION,
                severity = RiskLevel.MODERATE,
                description = "Steep terrain: ${terrainData.slope.toInt()}° slope",
                location = location,
                distance = 0.0,
                mitigation = "Exercise extreme caution, consider alternative route"
            ))
        }

        // Check for obstacles
        terrainData.obstacles.forEach { obstacle ->
            val distanceToObstacle = calculateDistance(
                location.latitude, location.longitude,
                obstacle.location.latitude, obstacle.location.longitude
            )

            if (distanceToObstacle < obstacle.radius) {
                risks.add(RiskFactor(
                    type = RiskType.OBSTACLE_PROXIMITY,
                    severity = RiskLevel.HIGH,
                    description = "Near obstacle: ${obstacle.type}",
                    location = obstacle.location,
                    distance = distanceToObstacle,
                    mitigation = "Avoid area or maintain high altitude"
                ))
            }
        }

        return risks
    }

    /**
     * Assess weather-related risks
     */
    private fun assessWeatherRisks(
        location: GeoPoint,
        weatherData: WeatherRouter.WeatherDataPoint?
    ): List<RiskFactor> {
        val risks = mutableListOf<RiskFactor>()

        if (weatherData == null) return risks

        // Check wind conditions
        if (weatherData.windSpeed > WIND_SAFETY_LIMIT) {
            risks.add(RiskFactor(
                type = RiskType.WEATHER_WIND,
                severity = if (weatherData.windSpeed > 35) RiskLevel.CRITICAL else RiskLevel.HIGH,
                description = "Dangerous wind: ${weatherData.windSpeed.toInt()} knots",
                location = location,
                distance = 0.0,
                mitigation = "Avoid area or wait for calmer conditions"
            ))
        }

        // Check visibility
        if (weatherData.visibility * 1000 < VISIBILITY_MINIMUM) {
            risks.add(RiskFactor(
                type = RiskType.WEATHER_VISIBILITY,
                severity = RiskLevel.HIGH,
                description = "Poor visibility: ${(weatherData.visibility * 1000).toInt()}m",
                location = location,
                distance = 0.0,
                mitigation = "Exercise extreme caution or avoid area"
            ))
        }

        // Check precipitation
        if (weatherData.precipitation > PRECIPITATION_LIMIT) {
            risks.add(RiskFactor(
                type = RiskType.WEATHER_PRECIPITATION,
                severity = RiskLevel.HIGH,
                description = "Active precipitation: ${weatherData.precipitation}mm/h",
                location = location,
                distance = 0.0,
                mitigation = "Avoid area during precipitation"
            ))
        }

        return risks
    }

    /**
     * Calculate distance from point to polygon
     */
    private fun calculateDistanceToPolygon(point: GeoPoint, polygon: List<GeoPoint>): Double {
        if (polygon.isEmpty()) return Double.MAX_VALUE

        // Check if point is inside polygon
        if (isPointInPolygon(point, polygon)) {
            return 0.0
        }

        // Find minimum distance to polygon edges
        var minDistance = Double.MAX_VALUE

        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            val distance = distanceToLineSegment(
                point,
                polygon[i],
                polygon[j]
            )
            minDistance = min(minDistance, distance)
        }

        return minDistance
    }

    /**
     * Check if point is inside polygon using ray casting algorithm
     */
    private fun isPointInPolygon(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        var inside = false

        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            if (((polygon[i].latitude > point.latitude) != (polygon[j].latitude > point.latitude)) &&
                (point.longitude < (polygon[j].longitude - polygon[i].longitude) *
                (point.latitude - polygon[i].latitude) / (polygon[j].latitude - polygon[i].latitude) + polygon[i].longitude)) {
                inside = !inside
            }
        }

        return inside
    }

    /**
     * Calculate distance from point to line segment
     */
    private fun distanceToLineSegment(
        point: GeoPoint,
        lineStart: GeoPoint,
        lineEnd: GeoPoint
    ): Double {
        val A = point.latitude - lineStart.latitude
        val B = point.longitude - lineStart.longitude
        val C = lineEnd.latitude - lineStart.latitude
        val D = lineEnd.longitude - lineStart.longitude

        val dot = A * C + B * D
        val lengthSquared = C * C + D * D

        var param = -1.0
        if (lengthSquared != 0.0) {
            param = dot / lengthSquared
        }

        var xx: Double
        var yy: Double

        if (param < 0) {
            xx = lineStart.latitude
            yy = lineStart.longitude
        } else if (param > 1) {
            xx = lineEnd.latitude
            yy = lineEnd.longitude
        } else {
            xx = lineStart.latitude + param * C
            yy = lineStart.longitude + param * D
        }

        val dx = point.latitude - xx
        val dy = point.longitude - yy

        return sqrt(dx * dx + dy * dy) * 111000 // Convert degrees to meters
    }

    /**
     * Calculate risk penalty for safety score
     */
    private fun calculateRiskPenalty(severity: RiskLevel): Double {
        return when (severity) {
            RiskLevel.NEGLIGIBLE -> 0.0
            RiskLevel.LOW -> 5.0
            RiskLevel.MODERATE -> 15.0
            RiskLevel.HIGH -> 30.0
            RiskLevel.CRITICAL -> 50.0
        }
    }

    /**
     * Determine overall risk level from individual risk factors
     */
    private fun determineOverallRisk(riskFactors: List<RiskFactor>): RiskLevel {
        if (riskFactors.any { it.severity == RiskLevel.CRITICAL }) {
            return RiskLevel.CRITICAL
        }

        if (riskFactors.count { it.severity == RiskLevel.HIGH } >= 2) {
            return RiskLevel.HIGH
        }

        if (riskFactors.any { it.severity == RiskLevel.HIGH } ||
            riskFactors.count { it.severity == RiskLevel.MODERATE } >= 3) {
            return RiskLevel.HIGH
        }

        if (riskFactors.count { it.severity == RiskLevel.MODERATE } >= 2) {
            return RiskLevel.MODERATE
        }

        if (riskFactors.any { it.severity == RiskLevel.MODERATE }) {
            return RiskLevel.MODERATE
        }

        if (riskFactors.any { it.severity == RiskLevel.LOW }) {
            return RiskLevel.LOW
        }

        return RiskLevel.NEGLIGIBLE
    }

    /**
     * Generate recommended actions based on risk factors
     */
    private fun generateRecommendedActions(
        riskFactors: List<RiskFactor>,
        overallRisk: RiskLevel
    ): List<String> {
        val actions = mutableSetOf<String>()

        when (overallRisk) {
            RiskLevel.CRITICAL -> {
                actions.add("⚠️ CRITICAL RISK: Do not fly in this area")
                actions.add("Seek immediate safe landing if already airborne")
            }
            RiskLevel.HIGH -> {
                actions.add("⚠️ HIGH RISK: Exercise extreme caution")
                actions.add("Consider alternative route or landing options")
            }
            RiskLevel.MODERATE -> {
                actions.add("⚠️ MODERATE RISK: Be vigilant and prepared")
                actions.add("Monitor conditions closely")
            }
            else -> {
                actions.add("✅ LOW RISK: Normal operations")
            }
        }

        // Add specific mitigations
        riskFactors.forEach { risk ->
            risk.mitigation?.let { actions.add(it) }
        }

        // Add general safety recommendations
        if (riskFactors.any { it.type == RiskType.AIRSPACE_RESTRICTION }) {
            actions.add("Check NOTAMs and airspace restrictions before flight")
        }

        if (riskFactors.any { it.type == RiskType.WEATHER_WIND }) {
            actions.add("Monitor wind conditions and be prepared for gusts")
        }

        if (riskFactors.any { it.type == RiskType.TERRAIN_CLEARANCE }) {
            actions.add("Maintain adequate terrain clearance at all times")
        }

        return actions.toList()
    }

    /**
     * Assess risk along a flight path
     */
    fun assessRouteRisk(
        route: List<GeoPoint>,
        currentAltitude: Double,
        airspaces: List<AirspaceData> = emptyList(),
        weatherData: Map<GeoPoint, WeatherRouter.WeatherDataPoint> = emptyMap()
    ): List<RiskAssessment> {
        return route.map { point ->
            val terrainData = getTerrainDataForLocation(point) // Would fetch real terrain data
            val weather = weatherData[point]

            assessLocationRisk(
                location = point,
                currentAltitude = currentAltitude,
                airspaces = airspaces,
                terrainData = terrainData,
                weatherData = weather
            )
        }
    }

    /**
     * Get terrain data for a location (placeholder)
     */
    private fun getTerrainDataForLocation(location: GeoPoint): TerrainData? {
        // In production, this would fetch real terrain data
        // For now, return null (no terrain data available)
        return null
    }

    /**
     * Calculate distance between two GPS coordinates using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Find the safest altitude for a location based on risk assessment
     */
    fun findSafestAltitude(
        location: GeoPoint,
        minAltitude: Double = 500.0, // meters MSL
        maxAltitude: Double = 3000.0, // meters MSL
        airspaces: List<AirspaceData> = emptyList()
    ): Double? {
        val altitudeStep = 100.0 // Check every 100 meters

        var bestAltitude: Double? = null
        var bestScore = 0.0

        var altitude = minAltitude
        while (altitude <= maxAltitude) {
            val assessment = assessLocationRisk(
                location = location,
                currentAltitude = altitude,
                airspaces = airspaces
            )

            if (assessment.safetyScore > bestScore) {
                bestScore = assessment.safetyScore
                bestAltitude = altitude
            }

            altitude += altitudeStep
        }

        return bestAltitude
    }

    /**
     * Check if a flight path is safe given current conditions
     */
    fun isFlightPathSafe(
        route: List<GeoPoint>,
        currentAltitude: Double,
        airspaces: List<AirspaceData> = emptyList(),
        weatherData: Map<GeoPoint, WeatherRouter.WeatherDataPoint> = emptyMap()
    ): Boolean {
        val routeAssessments = assessRouteRisk(route, currentAltitude, airspaces, weatherData)

        // Path is unsafe if any segment has critical or high risk
        return !routeAssessments.any { assessment ->
            assessment.overallRisk == RiskLevel.CRITICAL ||
            assessment.overallRisk == RiskLevel.HIGH
        }
    }
}