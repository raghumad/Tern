package com.madanala.tern.model

import com.madanala.tern.utils.LocalBddTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherModelTest : LocalBddTest() {

    @Test
    fun testSkewTAnalysis() {
        scenario("Skew-T Analysis: Identifying Inversion Layers") {
            var forecast: SkewTForecast? = null
            var analysis: SkewTAnalysis? = null

            given("a weather forecast with a temperature inversion") {
                // Mock data: Temp decreases normally until 1000m, then increases
                val points = listOf(
                    SkewTPoint(altitude = 0.0, temperature = 20.0, dewpoint = 10.0),
                    SkewTPoint(altitude = 500.0, temperature = 15.0, dewpoint = 8.0),
                    SkewTPoint(altitude = 1000.0, temperature = 10.0, dewpoint = 5.0), // Inversion base
                    SkewTPoint(altitude = 1500.0, temperature = 12.0, dewpoint = 0.0)  // Inversion peak
                )
                forecast = SkewTForecast(points)
            }

            `when`("the Skew-T analysis is performed") {
                analysis = WeatherAnalyzer.analyzeSkewT(forecast!!)
            }

            then("the system should identify the inversion layer") {
                assertTrue(analysis!!.hasInversion)
                assertEquals(1000.0, analysis!!.inversionBaseAltitude!!, 0.1)
            }
        }
    }

    @Test
    fun testCloudBaseEstimation() {
        scenario("Cloud Base Estimation") {
            var forecast: SkewTForecast? = null
            var cloudBase: Double? = null

            given("a standard lapse rate forecast") {
                // Simple approximation: (Temp - Dewpoint) * 125 + Altitude
                // At 0m: Temp 20, Dew 10 -> Spread 10 -> Cloud Base ~1250m
                val points = listOf(
                    SkewTPoint(altitude = 0.0, temperature = 20.0, dewpoint = 10.0),
                    SkewTPoint(altitude = 1000.0, temperature = 10.0, dewpoint = 5.0)
                )
                forecast = SkewTForecast(points)
            }

            `when`("the cloud base is estimated") {
                cloudBase = WeatherAnalyzer.estimateCloudBase(forecast!!)
            }

            then("the estimated cloud base should be consistent with the spread") {
                // 20 - 10 = 10 spread. 10 * 125 = 1250m.
                assertEquals(1250.0, cloudBase!!, 50.0) // Allow some margin
            }
        }
    }

    @Test
    fun testInversionBreakTime() {
        scenario("Inversion Break Time Estimation") {
            var forecast: SkewTForecast? = null
            var analysis: SkewTAnalysis? = null
            val currentSurfaceTemp = 15.0
            val heatingRate = 2.0 // degrees per hour

            given("an inversion layer at 1000m that requires heating to break") {
                val points = listOf(
                    SkewTPoint(altitude = 0.0, temperature = 20.0, dewpoint = 10.0),
                    SkewTPoint(altitude = 1000.0, temperature = 10.0, dewpoint = 5.0), // Base
                    SkewTPoint(altitude = 1500.0, temperature = 12.0, dewpoint = 0.0)  // Peak
                )
                forecast = SkewTForecast(points)
            }

            `when`("we calculate the break time with a heating rate of 2°C/hr") {
                analysis = WeatherAnalyzer.analyzeSkewT(forecast!!, currentSurfaceTemp)
            }

            then("the system should estimate the time to break") {
                // If required temp is X, and current is 15, time = (X - 15) / 2
                // This asserts that the logic exists and returns a value.
                // Specific value depends on the adiabatic calculation implementation.
                assertTrue(analysis!!.estimatedBreakTimeHours > 0)
            }
        }
    }

    @Test
    fun testCapeTiming() {
        scenario("CAPE Forecast Timing") {
            var capeForecast: List<CapePoint>? = null
            var riskAnalysis: OverdevelopmentRisk? = null

            given("a CAPE forecast peaking in the afternoon") {
                capeForecast = listOf(
                    CapePoint(time = "10:00", cape = 200.0),
                    CapePoint(time = "12:00", cape = 800.0),
                    CapePoint(time = "14:00", cape = 1500.0), // Danger zone
                    CapePoint(time = "16:00", cape = 1200.0)
                )
            }

            `when`("we analyze the overdevelopment risk") {
                riskAnalysis = WeatherAnalyzer.analyzeOverdevelopmentRisk(capeForecast!!)
            }

            then("the system should identify the peak time and risk level") {
                assertEquals("14:00", riskAnalysis!!.peakTime)
                assertEquals(RiskLevel.HIGH, riskAnalysis!!.riskLevel)
            }
        }
    }
}
