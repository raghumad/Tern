package com.madanala.tern.model

import com.madanala.tern.utils.LocalBddTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class RouteWeatherTest : LocalBddTest() {

    @Test
    fun testRouteWeatherAssociation() {
        scenario("Associating Weather Data with a Route") {
            var routeId: String? = null
            var forecast: WeatherForecast? = null
            var routeWeather: RouteWeather? = null

            given("a valid route ID and a weather forecast") {
                routeId = UUID.randomUUID().toString()
                forecast = WeatherForecast(
                    skewT = SkewTForecast(emptyList()),
                    cape = listOf(CapePoint("12:00", 500.0)),
                    wind = emptyList()
                )
            }

            `when`("we create a RouteWeather object") {
                routeWeather = RouteWeather(routeId!!, forecast!!, null)
            }

            then("the weather data should be linked to the route") {
                assertEquals(routeId, routeWeather!!.routeId)
                assertEquals(forecast, routeWeather!!.forecast)
            }
        }
    }

    @Test
    fun testWeatherRiskAggregation() {
        scenario("Aggregating Weather Risk along a Route") {
            var routeWeather: RouteWeather? = null
            var riskAnalysis: OverdevelopmentRisk? = null

            given("weather data for a route with high CAPE") {
                val capeForecast = listOf(
                    CapePoint("12:00", 500.0),
                    CapePoint("14:00", 2000.0) // High risk
                )
                val forecast = WeatherForecast(
                    skewT = SkewTForecast(emptyList()),
                    cape = capeForecast,
                    wind = emptyList()
                )
                routeWeather = RouteWeather("route_1", forecast)
            }

            `when`("we analyze the route risk") {
                riskAnalysis = WeatherAnalyzer.analyzeOverdevelopmentRisk(routeWeather!!.forecast.cape)
            }

            then("the system should report High Risk") {
                assertEquals(RiskLevel.HIGH, riskAnalysis!!.riskLevel)
            }
        }
    }

    @Test
    fun testTrajectoryForecast() {
        scenario("Trajectory-based Weather Forecast") {
            var trajectory: TrajectoryForecast? = null
            
            given("a list of waypoint weather forecasts") {
                val wp1Forecast = WeatherForecast(SkewTForecast(emptyList()), emptyList(), emptyList())
                val wp1 = WaypointWeather("wp1", 1000L, wp1Forecast)
                
                trajectory = TrajectoryForecast(
                    waypoints = listOf(wp1),
                    maxRisk = OverdevelopmentRisk("12:00", 0.0, RiskLevel.LOW),
                    avgHeadwind = 5.0
                )
            }
            
            then("the trajectory should contain the waypoint data") {
                assertEquals(1, trajectory!!.waypoints.size)
                assertEquals("wp1", trajectory!!.waypoints[0].waypointId)
            }
        }
    }
}
