package com.madanala.tern.model

import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Aviation flight computer for paragliding calculations
 * Advanced algorithms for variometer, wind drift, and final glide
 */
class FlightComputer {

    companion object {
        private const val EARTH_RADIUS = 6371000.0 // meters
        private const val STANDARD_LAPSE_RATE = 0.0065 // °C per meter
        private const val SEA_LEVEL_PRESSURE = 1013.25 // hPa
        private const val GAS_CONSTANT = 287.05 // J/(kg·K)
        private const val GRAVITY = 9.80665 // m/s²
        private const val AIR_MOLAR_MASS = 0.0289644 // kg/mol
    }

    /**
     * Calculate variometer data for thermal soaring
     */
    data class VarioCalculation(
        val verticalSpeed: Double, // m/s
        val averageVerticalSpeed: Double, // m/s (averaged over time)
        val thermalStrength: ThermalStrength,
        val liftTrend: LiftTrend,
        val netVario: Double, // m/s (vertical speed minus sink rate)
        val thermalCenterOffset: Double? = null // meters from thermal center
    )

    /**
     * Wind calculation results
     */
    data class WindCalculation(
        val windSpeed: Double, // m/s
        val windDirection: Double, // degrees
        val headwindComponent: Double, // m/s (positive = headwind)
        val crosswindComponent: Double, // m/s (positive = right crosswind)
        val windQuality: WindQuality
    )

    /**
     * Final glide calculation for reaching a goal
     */
    data class FinalGlideCalculation(
        val distanceToGoal: Double, // meters
        val heightDifference: Double, // meters (goal elevation - current elevation)
        val requiredGlideRatio: Double, // required L/D to reach goal
        val currentGlideRatio: Double, // current L/D
        val arrivalHeight: Double, // meters above goal
        val canReachGoal: Boolean,
        val safetyMargin: Double, // meters
        val bestGlideSpeed: Double? = null // m/s for optimal glide
    )

    /**
     * Lift trend analysis
     */
    enum class LiftTrend {
        STRONG_SINK,    // > 2 m/s sink
        MODERATE_SINK,  // 1-2 m/s sink
        WEAK_SINK,      // 0.5-1 m/s sink
        NEUTRAL,        // +/- 0.5 m/s
        WEAK_LIFT,      // 0.5-1 m/s lift
        MODERATE_LIFT,  // 1-2 m/s lift
        STRONG_LIFT,    // 2-4 m/s lift
        EXTREME_LIFT    // > 4 m/s lift
    }

    /**
     * Wind data quality assessment
     */
    enum class WindQuality {
        NONE,           // No wind data
        POOR,          // Limited data, low confidence
        FAIR,          // Some data, moderate confidence
        GOOD,          // Good data, high confidence
        EXCELLENT      // Multiple sources, very high confidence
    }

    /**
     * Calculate comprehensive variometer data
     */
    fun calculateVarioData(
        currentAltitude: Double,
        altitudeHistory: List<Double>,
        timeHistory: List<Long>,
        aircraftSinkRate: Double = 1.0 // m/s (aircraft polar sink rate)
    ): VarioCalculation {

        if (altitudeHistory.size < 3) {
            return VarioCalculation(
                verticalSpeed = 0.0,
                averageVerticalSpeed = 0.0,
                thermalStrength = ThermalStrength.NONE,
                liftTrend = LiftTrend.NEUTRAL,
                netVario = 0.0
            )
        }

        // Calculate current vertical speed
        val recentAltitudes = altitudeHistory.takeLast(3)
        val recentTimes = timeHistory.takeLast(3)
        val timeDiff = (recentTimes.last() - recentTimes.first()) / 1000.0 // seconds
        val altitudeDiff = recentAltitudes.last() - recentAltitudes.first()

        val verticalSpeed = if (timeDiff > 0) altitudeDiff / timeDiff else 0.0

        // Calculate averaged vertical speed (last 10 seconds)
        val recentData = altitudeHistory.zip(timeHistory).takeLast(10)
        val averageVerticalSpeed = if (recentData.size >= 2) {
            val totalAltitudeChange = recentData.last().first - recentData.first().first
            val totalTimeChange = (recentData.last().second - recentData.first().second) / 1000.0
            if (totalTimeChange > 0) totalAltitudeChange / totalTimeChange else 0.0
        } else verticalSpeed

        // Determine thermal strength
        val thermalStrength = classifyThermalStrength(averageVerticalSpeed)

        // Determine lift trend
        val liftTrend = classifyLiftTrend(verticalSpeed)

        // Calculate net vario (total energy minus aircraft sink rate)
        val netVario = averageVerticalSpeed + aircraftSinkRate

        // Estimate thermal center offset (simplified)
        val thermalCenterOffset = estimateThermalCenterOffset(altitudeHistory, verticalSpeed)

        return VarioCalculation(
            verticalSpeed = verticalSpeed,
            averageVerticalSpeed = averageVerticalSpeed,
            thermalStrength = thermalStrength,
            liftTrend = liftTrend,
            netVario = netVario,
            thermalCenterOffset = thermalCenterOffset
        )
    }

    /**
     * Calculate wind components and direction
     */
    fun calculateWind(
        groundSpeed: Double, // m/s
        groundTrack: Double, // degrees
        airSpeed: Double, // m/s (from GPS if available)
        heading: Double, // degrees (compass heading)
        gpsBearing: Double, // degrees (GPS course over ground)
        windHistory: List<Pair<Double, Double>> = emptyList() // Previous wind calculations
    ): WindCalculation {

        // Simplified wind calculation using ground speed vector vs airspeed vector
        // In practice, this would use more sophisticated Kalman filtering

        val windSpeed: Double
        val windDirection: Double
        val windQuality: WindQuality

        if (airSpeed > 0 && abs(groundSpeed - airSpeed) > 0.5) {
            // We have airspeed data - can calculate wind directly
            val groundSpeedVector = Pair(
                groundSpeed * cos(Math.toRadians(gpsBearing)),
                groundSpeed * sin(Math.toRadians(gpsBearing))
            )

            val airSpeedVector = Pair(
                airSpeed * cos(Math.toRadians(heading)),
                airSpeed * sin(Math.toRadians(heading))
            )

            val windVector = Pair(
                groundSpeedVector.first - airSpeedVector.first,
                groundSpeedVector.second - airSpeedVector.second
            )

            windSpeed = sqrt(windVector.first.pow(2) + windVector.second.pow(2))
            windDirection = Math.toDegrees(atan2(windVector.second, windVector.first))
                .let { if (it < 0) it + 360 else it }

            windQuality = if (windHistory.size > 5) WindQuality.GOOD else WindQuality.FAIR
        } else {
            // No airspeed data - use simplified estimation
            windSpeed = windHistory.map { it.first }.average().coerceAtLeast(0.0)
            windDirection = windHistory.map { it.second }.average()
            windQuality = WindQuality.POOR
        }

        // Calculate wind components relative to current track
        val headwindComponent = windSpeed * cos(Math.toRadians(windDirection - groundTrack))
        val crosswindComponent = windSpeed * sin(Math.toRadians(windDirection - groundTrack))

        return WindCalculation(
            windSpeed = windSpeed,
            windDirection = windDirection,
            headwindComponent = -headwindComponent, // Negative because headwind slows us down
            crosswindComponent = crosswindComponent,
            windQuality = windQuality
        )
    }

    /**
     * Calculate final glide to reach a goal
     */
    fun calculateFinalGlide(
        currentPosition: GeoPoint,
        currentAltitude: Double, // meters MSL
        goalPosition: GeoPoint,
        goalAltitude: Double, // meters MSL
        currentGlideRatio: Double,
        wind: WindCalculation? = null,
        safetyMargin: Double = 50.0 // meters
    ): FinalGlideCalculation {

        // Calculate horizontal distance to goal
        val distanceToGoal = calculateDistance(
            currentPosition.latitude, currentPosition.longitude,
            goalPosition.latitude, goalPosition.longitude
        )

        // Calculate height difference (positive if goal is higher)
        val heightDifference = goalAltitude - currentAltitude

        // Required glide ratio to reach goal
        val requiredGlideRatio = if (distanceToGoal > 0) {
            abs(heightDifference) / distanceToGoal
        } else 0.0

        // Adjust for wind (simplified)
        val windAdjustedGlideRatio = if (wind != null) {
            val windEffect = wind.headwindComponent * 0.1 // Simplified wind effect
            currentGlideRatio + windEffect
        } else currentGlideRatio

        // Calculate arrival height above goal
        val altitudeAtGoal = currentAltitude + (distanceToGoal * windAdjustedGlideRatio) - heightDifference
        val arrivalHeight = altitudeAtGoal - goalAltitude

        // Determine if goal can be reached
        val canReachGoal = arrivalHeight >= safetyMargin

        // Calculate best glide speed for optimal performance
        val bestGlideSpeed = calculateBestGlideSpeed(currentGlideRatio, wind)

        return FinalGlideCalculation(
            distanceToGoal = distanceToGoal,
            heightDifference = heightDifference,
            requiredGlideRatio = requiredGlideRatio,
            currentGlideRatio = windAdjustedGlideRatio,
            arrivalHeight = arrivalHeight,
            canReachGoal = canReachGoal,
            safetyMargin = safetyMargin,
            bestGlideSpeed = bestGlideSpeed
        )
    }

    /**
     * Calculate thermal strength from vertical speed
     */
    private fun classifyThermalStrength(verticalSpeed: Double): ThermalStrength {
        return when {
            verticalSpeed < -2.0 -> ThermalStrength.NONE
            verticalSpeed < -0.5 -> ThermalStrength.WEAK
            verticalSpeed < 1.0 -> ThermalStrength.MODERATE
            verticalSpeed < 3.0 -> ThermalStrength.STRONG
            else -> ThermalStrength.EXTREME
        }
    }

    /**
     * Classify lift trend for thermal analysis
     */
    private fun classifyLiftTrend(verticalSpeed: Double): LiftTrend {
        return when {
            verticalSpeed < -2.0 -> LiftTrend.STRONG_SINK
            verticalSpeed < -1.0 -> LiftTrend.MODERATE_SINK
            verticalSpeed < -0.5 -> LiftTrend.WEAK_SINK
            verticalSpeed < 0.5 -> LiftTrend.NEUTRAL
            verticalSpeed < 1.0 -> LiftTrend.WEAK_LIFT
            verticalSpeed < 2.0 -> LiftTrend.MODERATE_LIFT
            verticalSpeed < 4.0 -> LiftTrend.STRONG_LIFT
            else -> LiftTrend.EXTREME_LIFT
        }
    }

    /**
     * Estimate thermal center offset (simplified)
     */
    private fun estimateThermalCenterOffset(
        altitudeHistory: List<Double>,
        currentVerticalSpeed: Double
    ): Double? {
        // Simplified thermal centering calculation
        // In practice, this would analyze the flight path pattern

        if (altitudeHistory.size < 5 || currentVerticalSpeed <= 0) return null

        // Estimate based on recent altitude changes
        val recentAltitudes = altitudeHistory.takeLast(5)
        val altitudeTrend = recentAltitudes.last() - recentAltitudes.first()

        return if (altitudeTrend > 10) {
            // We're gaining altitude - estimate offset based on trend
            altitudeTrend * 10 // Simplified: 10m offset per meter of altitude gain
        } else null
    }

    /**
     * Calculate best glide speed for optimal performance
     */
    private fun calculateBestGlideSpeed(glideRatio: Double, wind: WindCalculation?): Double? {
        // Simplified best glide speed calculation
        // In practice, this would use aircraft polar data

        val baseBestGlideSpeed = 12.0 // m/s (simplified)

        wind?.let { windData ->
            // Adjust for headwind (fly faster into headwind)
            val windAdjustment = windData.headwindComponent * 0.3
            return baseBestGlideSpeed + windAdjustment
        }

        return baseBestGlideSpeed
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

    /**
     * Calculate altitude from pressure using hypsometric formula
     */
    fun calculateAltitudeFromPressure(pressure: Double, seaLevelPressure: Double = SEA_LEVEL_PRESSURE): Double {
        return (SEA_LEVEL_PRESSURE - pressure) / (SEA_LEVEL_PRESSURE * STANDARD_LAPSE_RATE / 288.15)
    }

    /**
     * Calculate true altitude considering temperature and pressure
     */
    fun calculateTrueAltitude(
        indicatedAltitude: Double,
        temperature: Double, // Celsius
        pressure: Double, // hPa
        elevation: Double = 0.0 // ground elevation in meters
    ): Double {
        val temperatureK = temperature + 273.15
        val pressureRatio = pressure / SEA_LEVEL_PRESSURE

        // Calculate density altitude
        val densityAltitude = elevation + 118.8 * (temperature - 15) +
                             (pressureRatio - 1) * 30000

        // Calculate pressure altitude
        val pressureAltitude = calculateAltitudeFromPressure(pressure)

        // True altitude is approximately pressure altitude
        return pressureAltitude
    }

    /**
     * Calculate glide ratio from horizontal and vertical speeds
     */
    fun calculateGlideRatio(horizontalSpeed: Double, verticalSpeed: Double): Double {
        return if (abs(verticalSpeed) > 0.1) {
            abs(horizontalSpeed / verticalSpeed)
        } else 0.0
    }

    /**
     * Calculate required glide ratio to reach a goal
     */
    fun calculateRequiredGlideRatio(
        distanceToGoal: Double,
        heightDifference: Double
    ): Double {
        return if (distanceToGoal > 0) {
            abs(heightDifference) / distanceToGoal
        } else 0.0
    }

    /**
     * Calculate estimated time of arrival at goal
     */
    fun calculateETA(
        distanceToGoal: Double,
        groundSpeed: Double,
        wind: WindCalculation? = null
    ): Double {
        val effectiveSpeed = if (wind != null) {
            groundSpeed + wind.headwindComponent // Headwind reduces ground speed
        } else groundSpeed

        return if (effectiveSpeed > 1.0) {
            (distanceToGoal / effectiveSpeed) // seconds
        } else Double.MAX_VALUE // Can't reach if speed too low
    }

    /**
     * Calculate optimal bank angle for coordinated turn
     */
    fun calculateOptimalBankAngle(
        airSpeed: Double, // m/s
        turnRadius: Double, // meters
        wind: WindCalculation? = null
    ): Double {
        val effectiveAirSpeed = if (wind != null) {
            airSpeed + wind.crosswindComponent
        } else airSpeed

        return Math.toDegrees(atan((effectiveAirSpeed.pow(2)) / (GRAVITY * turnRadius)))
    }

    /**
     * Calculate thermal drift compensation
     */
    fun calculateThermalDriftCompensation(
        thermalStrength: ThermalStrength,
        wind: WindCalculation,
        aircraftGlideRatio: Double
    ): Double {
        // Simplified thermal drift calculation
        // In practice, this would use more sophisticated thermal modeling

        val driftFactor = when (thermalStrength) {
            ThermalStrength.NONE -> 0.0
            ThermalStrength.WEAK -> 0.2
            ThermalStrength.MODERATE -> 0.4
            ThermalStrength.STRONG -> 0.6
            ThermalStrength.EXTREME -> 0.8
        }

        return wind.crosswindComponent * driftFactor
    }
}

/**
 * Moving average calculator for sensor smoothing
 */
class MovingAverageCalculator(private val size: Int) {
    private val values = mutableListOf<Double>()
    private var sum = 0.0

    fun add(value: Double): Double {
        values.add(value)
        sum += value

        if (values.size > size) {
            sum -= values.removeAt(0)
        }

        return sum / values.size.coerceAtLeast(1)
    }

    fun getAverage(): Double {
        return if (values.isEmpty()) 0.0 else sum / values.size
    }

    fun reset() {
        values.clear()
        sum = 0.0
    }

    fun getSize(): Int = values.size
}

/**
 * Kalman filter for sensor fusion (simplified implementation)
 */
class KalmanFilter(
    private var estimate: Double = 0.0,
    private var error: Double = 1.0,
    private val processNoise: Double = 0.01,
    private val measurementNoise: Double = 0.1
) {

    fun update(measurement: Double): Double {
        // Prediction step
        val predictedError = error + processNoise

        // Update step
        val kalmanGain = predictedError / (predictedError + measurementNoise)
        estimate = estimate + kalmanGain * (measurement - estimate)
        error = (1 - kalmanGain) * predictedError

        return estimate
    }

    fun getEstimate(): Double = estimate
    fun getError(): Double = error
}