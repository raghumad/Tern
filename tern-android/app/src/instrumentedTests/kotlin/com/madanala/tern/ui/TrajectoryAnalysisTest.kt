package com.madanala.tern.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.*
import com.madanala.tern.utils.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class TrajectoryAnalysisTest : com.madanala.tern.utils.MapVisualTest() {

    @Test
    fun test4DWeatherAndETA() {
        scenario("4D Trajectory Analysis and ETA display") {
            story("As a long-distance cross-country pilot, I want to see my estimated arrival time at each waypoint and the forecast for that specific time, so I can plan my flight path around moving weather patterns and ensure I stay within safe wind limits.") {
                
                // Create a route with two waypoints far apart to see a clear ETA difference
                val testRoute = Route(
                    id = "route_4d",
                    name = "XC Quest",
                    waypoints = listOf(
                        Waypoint(id = "wp_launch", lat = 45.0, lon = 6.0, label = "Launch"),
                        Waypoint(id = "wp_goal", lat = 45.5, lon = 6.5, label = "Goal") // ~68km away
                    )
                )

                // Current time for reference
                val now = System.currentTimeMillis()
                val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                val startEta = formatter.format(Instant.ofEpochMilli(now))
                
                // At 15 knots (~27km/h), 68km takes ~2.5 hours
                val goalEtaTime = now + (2.5 * 3600 * 1000).toLong()
                val goalEta = formatter.format(Instant.ofEpochMilli(goalEtaTime))

                val etas = mapOf(
                    "wp_launch" to now,
                    "wp_goal" to goalEtaTime
                )

                val forecast = WeatherForecast(
                    current = WeatherData(
                        wind = WindData(speed = 8.0, direction = 270.0, gust = 10.0),
                        temperature = 15.0,
                        humidity = 40.0,
                        visibility = 10.0,
                        pressure = 1013.25,
                        cloudCover = 10.0,
                        timestamp = now / 1000
                    ),
                    daily = emptyList(),
                    hourly = emptyList()
                )

                given("a 4D trajectory with calculated arrival times for a 68km flight") {
                    composeTestRule.runOnUiThread {
                        val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                        store.dispatch(MapAction.AddRoute(testRoute))
                        store.dispatch(MapAction.SelectRoute(testRoute.id))
                        store.dispatch(WeatherActions.RouteWeatherFetched(
                            testRoute.id, 
                            mapOf("wp_launch" to forecast, "wp_goal" to forecast),
                            etas
                        ))
                    }
                    composeTestRule.waitForIdle()
                }

                `when`("the pilot views the route details") {
                    // Route details panel is auto-shown when a route is selected in the real activity
                    composeTestRule.onNodeWithTag("RouteDetailPanel").assertExists()
                }

                this.then("the launch waypoint should show the starting ETA", takeScreenshot = true) {
                    composeTestRule.onNodeWithText("ETA: $startEta").assertIsDisplayed()
                }

                and("the goal waypoint should show the calculated arrival time approximately 2.5 hours later", takeScreenshot = true) {
                    composeTestRule.onNodeWithText("ETA: $goalEta").assertIsDisplayed()
                }
                
                and("the weather should be displayed for identifying safe windows", takeScreenshot = true) {
                    composeTestRule.onAllNodesWithText(
                        text = "🌬️ 8 kt @ 270°",
                        substring = true
                    ).assertCountEquals(2)
                }
            }
        }
    }
}
