package com.madanala.tern.model

import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Tactical decision support for real-time flight recommendations
 * Integrates sensor fusion, weather routing, and risk assessment for optimal decision making
 */
class TacticalDecisionSupport {

    companion object {
        private const val DECISION_UPDATE_INTERVAL = 5000L // milliseconds
        private const val THERMAL_DEVIATION_THRESHOLD = 200.0 // meters
        private const val WIND_OPTIMIZATION_THRESHOLD = 5.0 // knots
        private const val SAFETY_BUFFER_DISTANCE = 1000.0 // meters
    }

    /**
     * Tactical decision recommendation
     */
    data class TacticalDecision(
        val type: DecisionType,
        val priority: DecisionPriority,
        val title: String,
        val description: String,
        val recommendedAction: String,
        val targetLocation: GeoPoint?,
        val confidence: Double, // 0.0-1.0
        val reasoning: List<String>,
        val timestamp: Long = System.currentTimeMillis(),
        val expiresAt: Long = timestamp + 60000 // Valid for 1 minute
    )

    /**
     * Types of tactical decisions
     */
    enum class DecisionType {
        THERMAL_OPPORTUNITY,    // Thermal soaring opportunity
        WIND_OPTIMIZATION,      // Better wind conditions nearby
        SAFETY_MANEUVER,       // Safety-related course correction
        LANDING_RECOMMENDATION, // Recommended landing site
        ALTITUDE_ADJUSTMENT,    // Altitude optimization
        SPEED_OPTIMIZATION,     // Speed-to-fly optimization
        ROUTE_CORRECTION       // Route deviation for better conditions
    }

    /**
     * Decision priority levels
     */
    enum class DecisionPriority {
        LOW,        // Nice to know, no immediate action needed
        MEDIUM,     // Worth considering for optimization
        HIGH,       // Important for safety or performance
        CRITICAL    // Immediate action required for safety
    }

    /**
     * Flight context for decision making
     */
    data class FlightContext(
        val currentPosition: GeoPoint,
        val currentAltitude: Double, // meters MSL
        val groundSpeed: Double, // knots
        val track: Double, // degrees
        val verticalSpeed: Double, // m/s
        val flightMode: FlightMode,
        val sensorAccuracy: SensorAccuracy,
        val recentThermalStrength: Double, // m/s
        val windData: FlightComputer.WindCalculation?,
        val nearbyAirspaces: List<RiskAssessmentEngine.AirspaceData> = emptyList(),
        val terrainProfile: List<Pair<GeoPoint, Double>> = emptyList(), // elevation data
        val weatherForecast: List<WeatherRouter.WeatherDataPoint> = emptyList()
    )

    /**
     * Generate tactical decisions based on current flight context
     */
    fun generateTacticalDecisions(
        flightContext: FlightContext,
        riskAssessment: RiskAssessmentEngine.RiskAssessment? = null,
        weatherRouter: WeatherRouter? = null
    ): List<TacticalDecision> {
        val decisions = mutableListOf<TacticalDecision>()

        // Safety decisions (highest priority)
        decisions.addAll(generateSafetyDecisions(flightContext, riskAssessment))

        // Thermal opportunities
        decisions.addAll(generateThermalDecisions(flightContext))

        // Wind optimization
        decisions.addAll(generateWindOptimizationDecisions(flightContext))

        // Altitude optimization
        decisions.addAll(generateAltitudeDecisions(flightContext))

        // Landing recommendations
        decisions.addAll(generateLandingDecisions(flightContext, riskAssessment))

        // Route corrections
        decisions.addAll(generateRouteCorrectionDecisions(flightContext, weatherRouter))

        // Sort by priority and confidence
        return decisions
            .filter { it.confidence > 0.3 } // Filter out low-confidence decisions
            .sortedWith(compareByDescending<TacticalDecision> { it.priority }
                       .thenByDescending { it.confidence })
            .take(5) // Return top 5 decisions
    }

    /**
     * Generate safety-related tactical decisions
     */
    private fun generateSafetyDecisions(
        context: FlightContext,
        riskAssessment: RiskAssessmentEngine.RiskAssessment?
    ): List<TacticalDecision> {
        val decisions = mutableListOf<TacticalDecision>()

        riskAssessment?.let { assessment ->
            when (assessment.overallRisk) {
                RiskAssessmentEngine.RiskLevel.CRITICAL -> {
                    // Critical safety decisions
                    val nearestSafeArea = findNearestSafeArea(context.currentPosition, assessment)

                    decisions.add(TacticalDecision(
                        type = DecisionType.SAFETY_MANEUVER,
                        priority = DecisionPriority.CRITICAL,
                        title = "⚠️ Critical Safety Risk",
                        description = "Immediate safety maneuver required",
                        recommendedAction = "Head to safe area immediately",
                        targetLocation = nearestSafeArea,
                        confidence = 0.9,
                        reasoning = assessment.recommendedActions
                    ))
                }

                RiskAssessmentEngine.RiskLevel.HIGH -> {
                    // High risk decisions
                    decisions.add(TacticalDecision(
                        type = DecisionType.SAFETY_MANEUVER,
                        priority = DecisionPriority.HIGH,
                        title = "⚠️ High Risk Area",
                        description = "Exercise extreme caution",
                        recommendedAction = "Monitor conditions and be prepared to divert",
                        targetLocation = null,
                        confidence = 0.8,
                        reasoning = assessment.recommendedActions
                    ))
                }

                RiskAssessmentEngine.RiskLevel.MODERATE -> {
                    // Moderate risk awareness
                    decisions.add(TacticalDecision(
                        type = DecisionType.SAFETY_MANEUVER,
                        priority = DecisionPriority.MEDIUM,
                        title = "⚠️ Moderate Risk",
                        description = "Be vigilant and prepared",
                        recommendedAction = "Monitor conditions closely",
                        targetLocation = null,
                        confidence = 0.6,
                        reasoning = assessment.recommendedActions
                    ))
                }

                else -> {
                    // Low risk - no safety decisions needed
                }
            }
        }

        return decisions
    }

    /**
     * Generate thermal opportunity decisions
     */
    private fun generateThermalDecisions(context: FlightContext): List<TacticalDecision> {
        val decisions = mutableListOf<TacticalDecision>()

        // Check if we're missing thermal opportunities
        if (context.recentThermalStrength < 1.0 && context.verticalSpeed < 0.5) {
            // Look for better thermal conditions nearby
            val thermalTarget = findNearbyThermalOpportunity(context)

            thermalTarget?.let { target ->
                val distance = calculateDistance(
                    context.currentPosition.latitude, context.currentPosition.longitude,
                    target.latitude, target.longitude
                )

                if (distance < THERMAL_DEVIATION_THRESHOLD) {
                    decisions.add(TacticalDecision(
                        type = DecisionType.THERMAL_OPPORTUNITY,
                        priority = DecisionPriority.HIGH,
                        title = "🔥 Thermal Opportunity",
                        description = "Strong thermal detected nearby",
                        recommendedAction = "Head toward thermal for better lift",
                        targetLocation = target,
                        confidence = 0.7,
                        reasoning = listOf(
                            "Thermal strength: ${context.recentThermalStrength}m/s",
                            "Distance: ${distance.toInt()}m",
                            "Current sink rate: ${context.verticalSpeed}m/s"
                        )
                    ))
                }
            }
        }

        return decisions
    }

    /**
     * Generate wind optimization decisions
     */
    private fun generateWindOptimizationDecisions(context: FlightContext): List<TacticalDecision> {
        val decisions = mutableListOf<TacticalDecision>()

        context.windData?.let { wind ->
            // Check if we can optimize for better wind conditions
            if (abs(wind.headwindComponent) > WIND_OPTIMIZATION_THRESHOLD) {
                val windOptimizationTarget = findWindOptimizationTarget(context, wind)

                windOptimizationTarget?.let { target ->
                    decisions.add(TacticalDecision(
                        type = DecisionType.WIND_OPTIMIZATION,
                        priority = DecisionPriority.MEDIUM,
                        title = "💨 Wind Optimization",
                        description = "Better wind conditions available",
                        recommendedAction = "Adjust course for better wind",
                        targetLocation = target,
                        confidence = 0.6,
                        reasoning = listOf(
                            "Current headwind: ${wind.headwindComponent.toInt()}kt",
                            "Potential improvement: ${WIND_OPTIMIZATION_THRESHOLD}kt better"
                        )
                    ))
                }
            }
        }

        return decisions
    }

    /**
     * Generate altitude optimization decisions
     */
    private fun generateAltitudeDecisions(context: FlightContext): List<TacticalDecision> {
        val decisions = mutableListOf<TacticalDecision>()

        // Altitude optimization based on flight mode and conditions
        when (context.flightMode) {
            FlightMode.THERMAL -> {
                if (context.verticalSpeed > 2.0 && context.currentAltitude < 2500) {
                    // In strong lift, suggest gaining altitude
                    decisions.add(TacticalDecision(
                        type = DecisionType.ALTITUDE_ADJUSTMENT,
                        priority = DecisionPriority.MEDIUM,
                        title = "⬆️ Gain Altitude",
                        description = "Strong lift - optimal for climbing",
                        recommendedAction = "Continue circling to gain altitude",
                        targetLocation = null,
                        confidence = 0.8,
                        reasoning = listOf(
                            "Strong thermal: ${context.verticalSpeed}m/s",
                            "Recommended: Circle to maximize altitude gain"
                        )
                    ))
                }
            }

            FlightMode.FLIGHT -> {
                // Speed-to-fly optimization
                val optimalSpeed = calculateOptimalSpeed(context)
                val speedDifference = abs(context.groundSpeed - optimalSpeed)

                if (speedDifference > 5.0) {
                    decisions.add(TacticalDecision(
                        type = DecisionType.SPEED_OPTIMIZATION,
                        priority = DecisionPriority.LOW,
                        title = "⚡ Speed Optimization",
                        description = "Adjust speed for better glide",
                        recommendedAction = "Adjust airspeed to ${optimalSpeed.toInt()}kt",
                        targetLocation = null,
                        confidence = 0.5,
                        reasoning = listOf(
                            "Current speed: ${context.groundSpeed.toInt()}kt",
                            "Optimal speed: ${optimalSpeed.toInt()}kt"
                        )
                    ))
                }
            }

            else -> {
                // Other modes - no altitude decisions
            }
        }

        return decisions
    }

    /**
     * Generate landing recommendations
     */
    private fun generateLandingDecisions(
        context: FlightContext,
        riskAssessment: RiskAssessmentEngine.RiskAssessment?
    ): List<TacticalDecision> {
        val decisions = mutableListOf<TacticalDecision>()

        // Check if conditions warrant landing consideration
        val shouldConsiderLanding = shouldConsiderLanding(context, riskAssessment)

        if (shouldConsiderLanding) {
            val bestLandingSite = findBestLandingSite(context)

            bestLandingSite?.let { landingSite ->
                decisions.add(TacticalDecision(
                    type = DecisionType.LANDING_RECOMMENDATION,
                    priority = if (riskAssessment?.overallRisk == RiskAssessmentEngine.RiskLevel.HIGH)
                        DecisionPriority.HIGH else DecisionPriority.MEDIUM,
                    title = "🛬 Landing Recommendation",
                    description = "Conditions suggest considering landing",
                    recommendedAction = "Head to recommended landing site",
                    targetLocation = landingSite,
                    confidence = 0.7,
                    reasoning = listOf(
                        "Safe landing site identified",
                        "Distance: ${calculateDistanceToLandingSite(context.currentPosition, landingSite)}m"
                    )
                ))
            }
        }

        return decisions
    }

    /**
     * Generate route correction decisions
     */
    private fun generateRouteCorrectionDecisions(
        context: FlightContext,
        weatherRouter: WeatherRouter?
    ): List<TacticalDecision> {
        val decisions = mutableListOf<TacticalDecision>()

        weatherRouter?.let { router ->
            // Calculate better route if significant improvement possible
            val currentRouteScore = calculateCurrentRouteScore(context)
            val optimizedRoute = router.calculateOptimalRoute(
                startPoint = context.currentPosition,
                endPoint = getDestinationPoint(context), // Would need actual destination
                weatherData = context.weatherForecast.associateBy { it.location }
            )

            if (optimizedRoute.routeQuality >= WeatherRouter.RouteQuality.GOOD &&
                optimizedRoute.totalDistance < getCurrentRouteDistance(context) * 1.3) {

                decisions.add(TacticalDecision(
                    type = DecisionType.ROUTE_CORRECTION,
                    priority = DecisionPriority.MEDIUM,
                    title = "🛣️ Route Optimization",
                    description = "Better route conditions available",
                    recommendedAction = "Consider route correction for better conditions",
                    targetLocation = optimizedRoute.waypoints.firstOrNull(),
                    confidence = 0.6,
                    reasoning = listOf(
                        "Route quality: ${optimizedRoute.routeQuality}",
                        "Wind assistance: ${optimizedRoute.windAssistance}kt",
                        "Distance: ${optimizedRoute.totalDistance}m"
                    )
                ))
            }
        }

        return decisions
    }

    /**
     * Find nearest safe area when in critical risk situation
     */
    private fun findNearestSafeArea(
        currentPosition: GeoPoint,
        riskAssessment: RiskAssessmentEngine.RiskAssessment
    ): GeoPoint? {
        // Simplified - in practice would use terrain and airspace analysis
        // For now, suggest moving perpendicular to current track
        val moveDistance = SAFETY_BUFFER_DISTANCE
        val bearing = 90.0 // Move east (perpendicular to typical north-south flight)

        return calculateOffsetPosition(currentPosition, bearing, moveDistance)
    }

    /**
     * Find nearby thermal opportunities
     */
    private fun findNearbyThermalOpportunity(context: FlightContext): GeoPoint? {
        // Simplified thermal detection
        // In practice, would use thermal prediction models and weather data

        if (context.recentThermalStrength < 0.5) {
            // Suggest moving to nearby area that might have better thermals
            val searchBearing = (context.track + 45) % 360 // Search 45 degrees off track
            return calculateOffsetPosition(context.currentPosition, searchBearing, 500.0)
        }

        return null
    }

    /**
     * Find wind optimization target
     */
    private fun findWindOptimizationTarget(
        context: FlightContext,
        wind: FlightComputer.WindCalculation
    ): GeoPoint? {
        // Suggest moving to area with better wind conditions
        val windOptimizationBearing = if (wind.headwindComponent > 0) {
            // Headwind - suggest moving perpendicular to reduce headwind component
            context.track + 90
        } else {
            // Tailwind - suggest continuing on current track
            context.track
        }

        return calculateOffsetPosition(context.currentPosition, windOptimizationBearing, 1000.0)
    }

    /**
     * Calculate optimal speed for current conditions
     */
    private fun calculateOptimalSpeed(context: FlightContext): Double {
        // Simplified speed-to-fly calculation
        // In practice, would use aircraft polar and wind data

        val baseOptimalSpeed = 25.0 // knots

        context.windData?.let { wind ->
            // Adjust for wind - fly faster in headwind, slower in tailwind
            return baseOptimalSpeed + wind.headwindComponent * 0.5
        }

        return baseOptimalSpeed
    }

    /**
     * Determine if landing should be considered
     */
    private fun shouldConsiderLanding(
        context: FlightContext,
        riskAssessment: RiskAssessmentEngine.RiskAssessment?
    ): Boolean {
        // Consider landing if:
        // 1. High risk conditions
        // 2. Poor thermal conditions and low altitude
        // 3. Deteriorating weather

        val highRisk = riskAssessment?.overallRisk == RiskAssessmentEngine.RiskLevel.HIGH ||
                      riskAssessment?.overallRisk == RiskAssessmentEngine.RiskLevel.CRITICAL

        val poorConditions = context.recentThermalStrength < 0.5 &&
                           context.currentAltitude < 800 &&
                           context.verticalSpeed < -1.0

        return highRisk || poorConditions
    }

    /**
     * Find best landing site based on current conditions
     */
    private fun findBestLandingSite(context: FlightContext): GeoPoint? {
        // Simplified landing site selection
        // In practice, would use terrain analysis and landing site database

        val landingBearing = context.track + 180 // Opposite to current direction
        val landingDistance = min(2000.0, context.currentAltitude * 10) // Based on altitude

        return calculateOffsetPosition(context.currentPosition, landingBearing, landingDistance)
    }

    /**
     * Calculate current route score for comparison
     */
    private fun calculateCurrentRouteScore(context: FlightContext): Double {
        // Simplified route scoring
        var score = 50.0 // Base score

        // Add points for good conditions
        if (context.recentThermalStrength > 1.0) score += 20
        if (context.verticalSpeed > 0) score += 15

        // Deduct points for poor conditions
        if (context.recentThermalStrength < 0) score -= 20
        if (context.verticalSpeed < -2.0) score -= 25

        return score.coerceIn(0.0, 100.0)
    }

    /**
     * Get destination point (would need actual destination from flight plan)
     */
    private fun getDestinationPoint(context: FlightContext): GeoPoint {
        // For now, assume a destination 20km ahead
        // In practice, would get from flight plan or competition task
        return calculateOffsetPosition(context.currentPosition, context.track, 20000.0)
    }

    /**
     * Get current route distance (would need actual route data)
     */
    private fun getCurrentRouteDistance(context: FlightContext): Double {
        // For now, return distance to assumed destination
        // In practice, would calculate actual route distance
        return 20000.0
    }

    /**
     * Calculate offset position given bearing and distance
     */
    private fun calculateOffsetPosition(
        startPoint: GeoPoint,
        bearing: Double,
        distanceMeters: Double
    ): GeoPoint {
        val earthRadius = 6371000.0 // meters
        val angularDistance = distanceMeters / earthRadius

        val lat1 = Math.toRadians(startPoint.latitude)
        val lon1 = Math.toRadians(startPoint.longitude)
        val bearingRad = Math.toRadians(bearing)

        val lat2 = asin(sin(lat1) * cos(angularDistance) +
                       cos(lat1) * sin(angularDistance) * cos(bearingRad))

        val lon2 = lon1 + atan2(sin(bearingRad) * sin(angularDistance) * cos(lat1),
                               cos(angularDistance) - sin(lat1) * sin(lat2))

        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /**
     * Calculate distance between two points
     */
    private fun calculateDistanceToLandingSite(
        currentPosition: GeoPoint,
        landingSite: GeoPoint
    ): Double {
        return calculateDistance(
            currentPosition.latitude, currentPosition.longitude,
            landingSite.latitude, landingSite.longitude
        )
    }

    /**
     * Calculate distance between two GPS coordinates
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
     * Check if a tactical decision is still valid
     */
    fun isDecisionValid(decision: TacticalDecision): Boolean {
        return System.currentTimeMillis() < decision.expiresAt &&
               decision.confidence > 0.3
    }

    /**
     * Update decision confidence based on current conditions
     */
    fun updateDecisionConfidence(
        decision: TacticalDecision,
        currentContext: FlightContext
    ): TacticalDecision {
        var updatedConfidence = decision.confidence

        // Reduce confidence if conditions have changed significantly
        when (decision.type) {
            DecisionType.THERMAL_OPPORTUNITY -> {
                if (currentContext.recentThermalStrength < 0.5) {
                    updatedConfidence -= 0.2
                }
            }

            DecisionType.WIND_OPTIMIZATION -> {
                currentContext.windData?.let { wind ->
                    if (abs(wind.headwindComponent) < WIND_OPTIMIZATION_THRESHOLD) {
                        updatedConfidence -= 0.2
                    }
                }
            }

            DecisionType.SAFETY_MANEUVER -> {
                // Safety decisions maintain high confidence
            }

            else -> {
                // Other decisions lose confidence over time
                val ageMinutes = (System.currentTimeMillis() - decision.timestamp) / 60000.0
                updatedConfidence -= ageMinutes * 0.1
            }
        }

        return decision.copy(confidence = updatedConfidence.coerceIn(0.0, 1.0))
    }
}