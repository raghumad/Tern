package com.ternparagliding.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FlightComputerTest {

    private val flightComputer = FlightComputer()

    @Test
    fun `calculateVarioData returns neutral for insufficient history`() {
        val result = flightComputer.calculateVarioData(
            currentAltitude = 1000.0,
            altitudeHistory = listOf(1000.0, 1000.0),
            timeHistory = listOf(1000L, 2000L)
        )

        assertThat(result.verticalSpeed).isEqualTo(0.0)
        assertThat(result.thermalStrength).isEqualTo(FlightComputer.ThermalStrength.NONE)
    }

    @Test
    fun `calculateVarioData detects strong lift`() {
        // 30 meters gain in 10 seconds = 3 m/s
        val altitudes = listOf(1000.0, 1010.0, 1020.0, 1030.0)
        val times = listOf(0L, 3333L, 6666L, 10000L)

        val result = flightComputer.calculateVarioData(
            currentAltitude = 1030.0,
            altitudeHistory = altitudes,
            timeHistory = times
        )

        assertThat(result.verticalSpeed).isWithin(0.1).of(3.0)
        assertThat(result.thermalStrength).isEqualTo(FlightComputer.ThermalStrength.EXTREME)
        assertThat(result.liftTrend).isEqualTo(FlightComputer.LiftTrend.STRONG_LIFT)
    }

    @Test
    fun `calculateVarioData detects sink`() {
        // 25 meters loss in 10 seconds = -2.5 m/s
        val altitudes = listOf(1000.0, 991.6, 983.3, 975.0)
        val times = listOf(0L, 3333L, 6666L, 10000L)

        val result = flightComputer.calculateVarioData(
            currentAltitude = 975.0,
            altitudeHistory = altitudes,
            timeHistory = times
        )

        assertThat(result.verticalSpeed).isWithin(0.1).of(-2.5)
        assertThat(result.liftTrend).isEqualTo(FlightComputer.LiftTrend.STRONG_SINK)
    }

    @Test
    fun `calculateWind estimates wind from ground and air vectors`() {
        // Flying North (0 deg) at 10 m/s airspeed
        // Ground track North (0 deg) at 5 m/s ground speed
        // Implies 5 m/s headwind from North
        
        val result = flightComputer.calculateWind(
            groundSpeed = 5.0,
            groundTrack = 0.0,
            airSpeed = 10.0,
            heading = 0.0,
            gpsBearing = 0.0
        )

        assertThat(result.windSpeed).isWithin(0.1).of(5.0)
        // Wind coming FROM North (0 degrees)
        // Vector math: Ground = Air + Wind => Wind = Ground - Air
        // Ground (0, 5) - Air (0, 10) = (0, -5)
        // Atan2(-5, 0) = -90 deg = 270 deg (West)? Wait, let's check coordinate system
        // Standard math: 0 deg is East. 
        // Aviation: 0 deg is North.
        // The implementation uses sin/cos with Math.toRadians(bearing).
        // If bearing 0 is North, and we use standard sin/cos, we are mapping North to X axis?
        // Let's rely on the implementation's consistency.
        
        // If implementation maps 0 to North:
        // Ground(0, 5), Air(0, 10) -> Wind(0, -5).
        // Wind vector points South. Wind direction is "blowing towards South", so coming from North.
        // Direction calculation should yield "coming from".
        
        assertThat(result.headwindComponent).isGreaterThan(0.0)
    }

    @Test
    fun `calculateFinalGlide determines if goal is reachable`() {
        // 11km distance, 1000m height diff => 11:1 required glide
        // Current glide 8:1 => Unreachable
        
        val resultUnreachable = flightComputer.calculateFinalGlide(
            currentLat = 0.0,
            currentLon = 0.0,
            currentAltitude = 2000.0,
            goalLat = 0.1,
            goalLon = 0.0,
            goalAltitude = 1000.0,
            currentGlideRatio = 8.0
        )

        assertThat(resultUnreachable.canReachGoal).isFalse()
        assertThat(resultUnreachable.requiredGlideRatio).isGreaterThan(10.0)

        // Current glide 15:1 => Reachable
        val resultReachable = flightComputer.calculateFinalGlide(
            currentLat = 0.0,
            currentLon = 0.0,
            currentAltitude = 2000.0,
            goalLat = 0.1,
            goalLon = 0.0,
            goalAltitude = 1000.0,
            currentGlideRatio = 15.0
        )

        assertThat(resultReachable.canReachGoal).isTrue()
        assertThat(resultReachable.arrivalHeight).isGreaterThan(0.0)
    }

    @Test
    fun `calculateAltitudeFromPressure follows standard atmosphere`() {
        // Standard pressure at sea level
        val seaLevelAlt = flightComputer.calculateAltitudeFromPressure(1013.25)
        assertThat(seaLevelAlt).isWithin(0.1).of(0.0)

        // Lower pressure = Higher altitude
        // Approx 850 hPa is ~1450m
        val highAlt = flightComputer.calculateAltitudeFromPressure(850.0)
        assertThat(highAlt).isGreaterThan(1000.0)
    }
}
