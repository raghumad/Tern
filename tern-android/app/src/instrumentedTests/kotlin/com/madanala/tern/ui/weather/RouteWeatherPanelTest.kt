package com.madanala.tern.ui.weather

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule // Kept if needed by other tests, but unused here now
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.madanala.tern.model.OverdevelopmentRisk
import com.madanala.tern.model.RiskLevel
import com.madanala.tern.model.RouteWeather
import com.madanala.tern.model.SkewTForecast
import com.madanala.tern.model.TrajectoryForecast
import com.madanala.tern.model.WaypointWeather
import com.madanala.tern.model.WeatherForecast
import com.madanala.tern.utils.BddTest
import org.junit.Test

import org.osmdroid.util.GeoPoint

class RouteWeatherPanelTest : BddTest() {

    // composeTestRule is inherited from BaseUITest via BddTest

    @Test
    fun testRouteWeatherPanelDisplay() {
        scenario("Displaying Route Weather Insights") {
            var routeWeather: RouteWeather? = null
            
            given("a route with wind and risk data") {
                val windPoints = listOf(com.madanala.tern.model.WindPoint(1000.0, 15.0, 315.0))
                val forecast = WeatherForecast(SkewTForecast(emptyList()), emptyList(), windPoints)
                val wp1 = WaypointWeather("Launch", GeoPoint(0.0, 0.0), System.currentTimeMillis(), forecast)
                val wp2 = WaypointWeather("Goal", GeoPoint(0.0, 0.0), System.currentTimeMillis() + 3600000, forecast)
                
                val trajectory = TrajectoryForecast(
                    waypoints = listOf(wp1, wp2),
                    maxRisk = OverdevelopmentRisk("12:00", 0.0, RiskLevel.LOW),
                    avgHeadwind = 15.0
                )
                
                routeWeather = RouteWeather("route1", forecast, trajectory)
            }
            
            `when`("the weather panel is displayed") {
                composeTestRule.setContent {
                    androidx.compose.material3.MaterialTheme {
                        RouteWeatherPanel(routeWeather = routeWeather!!)
                    }
                }
            }
            
            then("the header should show 'Route Forecast'") {
                composeTestRule.onNodeWithText("Route Forecast").assertIsDisplayed()
            }
            
            and("the average wind should be displayed as 15 km/h") {
                composeTestRule.onNodeWithText("Avg Wind").assertIsDisplayed()
                // Use testTag because text might be merged or hard to find
                composeTestRule.onAllNodesWithTag("WindGaugeValue")[0].assertTextEquals("15")
            }
            
            and("expanding the waypoint list should show the trend chart") {
                composeTestRule.onNodeWithText("Waypoint Breakdown").performClick()
                composeTestRule.onNodeWithText("Wind & Cloud Trend").assertIsDisplayed()
                composeTestRule.onNodeWithText("Launch (", substring = true).assertIsDisplayed()
                composeTestRule.onNodeWithText("Goal (", substring = true).assertIsDisplayed()
            }
        }
    }
}
