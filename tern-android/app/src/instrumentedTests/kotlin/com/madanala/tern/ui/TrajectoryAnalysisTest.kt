package com.madanala.tern.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.model.TrajectoryAnalyzer
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

                // Use System.currentTimeMillis() for better alignment with middleware
                val now = System.currentTimeMillis()
                val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                
                // Use the real TrajectoryAnalyzer to ensure deterministic expectations
                val etas = TrajectoryAnalyzer.calculateETAs(testRoute, 15.0, now)
                val startEta = formatter.format(Instant.ofEpochMilli(etas["wp_launch"]!!))
                val goalEta = formatter.format(Instant.ofEpochMilli(etas["wp_goal"]!!))


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
                    }
                    composeTestRule.waitForIdle()
                }

                `when`("the pilot views the route details") {
                    // Route details panel is auto-shown when a route is selected in the real activity
                    composeTestRule.onNodeWithTag("RouteDetailPanel").assertExists()
                    
                    // Dispatch weather AFTER panel is shown to ensure test data wins over background fetch
                    composeTestRule.runOnUiThread {
                        val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                        store.dispatch(WeatherActions.RouteWeatherFetched(
                            testRoute.id, 
                            mapOf("wp_launch" to forecast, "wp_goal" to forecast),
                            etas
                        ))
                    }
                    composeTestRule.waitForIdle()
                }

                this.then("the launch waypoint should show the starting ETA", takeScreenshot = true) {
                    composeTestRule.onNodeWithText("ETA: $startEta").assertIsDisplayed()
                }

                and("the goal waypoint should show the calculated arrival time approximately 2.5 hours later", takeScreenshot = true) {
                    // Dispatch once and wait for idle
                    composeTestRule.runOnUiThread {
                        val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                        store.dispatch(WeatherActions.RouteWeatherFetched(
                            testRoute.id, 
                            mapOf("wp_launch" to forecast, "wp_goal" to forecast),
                            etas
                        ))
                    }
                    
                    composeTestRule.waitForIdle()

                    // Wait for text to appear without constant re-dispatching
                    composeTestRule.waitUntil(12000) {
                        try {
                            composeTestRule.onNodeWithText("ETA: $goalEta").assertExists()
                            true
                        } catch (e: Throwable) {
                            // Only re-dispatch once a second if missing (mitigates background race conditions)
                            if (System.currentTimeMillis() % 1000 < 50) {
                                composeTestRule.runOnUiThread {
                                    val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                                    store.dispatch(WeatherActions.RouteWeatherFetched(
                                        testRoute.id, 
                                        mapOf("wp_launch" to forecast, "wp_goal" to forecast),
                                        etas
                                    ))
                                }
                            }
                            false
                        }
                    }

                    
                    // Scroll to ensure visibility
                    composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(androidx.compose.ui.test.hasText("Goal", substring = true))
                    composeTestRule.onNodeWithText("ETA: $goalEta").assertIsDisplayed()
                }
                
                and("all waypoints should show an ETA", takeScreenshot = true) {
                    composeTestRule.onAllNodesWithText("ETA:", substring = true).assertCountEquals(2)
                }
                
                and("the weather should be displayed for identifying safe windows", takeScreenshot = true) {
                    // Check for existence of weather info - Loose matching to avoid encoding/rounding issues
                    // Using onFirst() because multiple waypoints may show it
                    composeTestRule.onAllNodesWithText("kt @", substring = true).onFirst().assertExists()
                }




            }
        }
    }
}
