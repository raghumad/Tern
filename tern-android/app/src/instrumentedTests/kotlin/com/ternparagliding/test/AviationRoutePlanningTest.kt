package com.ternparagliding.test

import androidx.compose.ui.test.*
import com.ternparagliding.model.LocationType
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.model.Waypoint
import com.ternparagliding.model.Route
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.WeatherActions
import com.ternparagliding.ui.givenAppIsLaunchedOnMap
import com.ternparagliding.utils.WeatherTestHelper
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.osmdroid.util.GeoPoint

/**
 * BDD Instrumented Tests - Phase 14: Personal XC Planning UX
 *
 * Framed as Pilot Stories to ensure Aviation-Grade UX Fidelity.
 */
class AviationRoutePlanningTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    /**
     * STORY 1: Mountain Record Attempt
     * Narrative: As a pilot planning a record flight in the Rockies, I want to see
     * critical terrain and thermal hotspots automatically while I define my task.
     */
    @Test
    fun pilot_plans_mountain_record_attempt() {
        scenario("Mountain Record Attempt") {
            story("As a pilot planning a record flight, I want to build a route and see HUD metrics update.") {
                given("a pilot starting a new XC task at Lookout Mountain", takeScreenshot = true) {
                    givenAppIsLaunchedOnMap(lat = 39.7429, lon = -105.2393, countryCode = "us")
                }

                `when`("I create a route starting at Lookout Mountain via long-press") {
                    longPressOnMap(39.7429, -105.2393)
                    // Wait for waypoint creation (CheckSmartSuggestion -> LongPressMap fallback)
                    composeTestRule.waitForIdle()
                    Thread.sleep(2000)
                }

                then("a route should be created and the RouteDetailPanel should appear", takeScreenshot = true) {
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isNotEmpty()
                    }
                    composeTestRule.onNodeWithTag("RouteDetailPanel").assertIsDisplayed()
                }

                `when`("I add a turnpoint at Idaho Springs") {
                    longPressOnMap(39.7420, -105.5136)
                    composeTestRule.waitForIdle()
                    Thread.sleep(1000)
                }

                then("the HUD should show distance and FAI points for the route", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("RoutePlanningHUD").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("HUD_Distance").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("HUD_FaiPoints").assertIsDisplayed()
                }

                and("the HUD should show the offline secured shield") {
                    composeTestRule.onNodeWithTag("HUD_FlightReady_Shield").assertIsDisplayed()
                }

                `when`("I move the last waypoint into Denver Class B airspace") {
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]
                        val route = store.state.value.routes.first()
                        store.dispatch(MapAction.UpdateWaypoint(route.id, route.waypoints.last().id, lat = 39.8617, lon = -104.6731))
                        store.dispatch(MapAction.SetAirspaceCollision(true))
                    }
                    composeTestRule.waitForIdle()
                }

                then("the HUD should show a CLASS B COLLISION warning", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("HUD_AirspaceWarning").assertIsDisplayed()
                    composeTestRule.onNodeWithText("CLASS B COLLISION", substring = true).assertIsDisplayed()
                }
            }
        }
    }

    /**
     * STORY 2: Competition Readiness from the Sofa
     * Narrative: As a competition pilot, I want to randomly import a task from a
     * link file, so I can practice my glide plan.
     */
    @Test
    fun pilot_imports_random_competition_task() {
        scenario("Competition Readiness from the Sofa") {
            story("As a competition pilot, I want to import a task and see route metrics.") {
                given("a competition task is loaded onto the map") {
                    val mockRoute = Route(
                        id = "comp_practice",
                        name = "Airtribune Practice Task",
                        waypoints = listOf(
                            Waypoint(lat = 39.7429, lon = -105.2393, type = LocationType.LAUNCH, label = "Lookout"),
                            Waypoint(lat = 39.8, lon = -105.3, type = LocationType.TURNPOINT, label = "TP1"),
                            Waypoint(lat = 39.75, lon = -105.1, type = LocationType.GOAL, label = "Goal")
                        )
                    )
                    showRouteOnMap(mockRoute)
                    assertMapLocation(39.7429, -105.2393)
                }

                then("the RouteDetailPanel should display the task name and distance", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("RouteDetailPanel").assertIsDisplayed()
                    composeTestRule.onNodeWithText("Airtribune Practice Task").assertIsDisplayed()
                    composeTestRule.onNodeWithText("km", substring = true).assertExists()
                }

                and("the HUD should show the offline secured shield") {
                    composeTestRule.onNodeWithTag("RoutePlanningHUD").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("HUD_FlightReady_Shield").assertIsDisplayed()
                    composeTestRule.onNodeWithText("OFFLINE SECURED").assertIsDisplayed()
                }
            }
        }
    }

    /**
     * STORY 3: The Adaptive Weather Pivot
     * Narrative: While planning my route, I notice a storm risk in the HUD.
     * I want to modify my waypoints to avoid the affected area.
     */
    @Test
    fun pilot_adapts_route_to_weather_risk() {
        scenario("The Adaptive Weather Pivot") {
            story("While planning, I notice a storm risk indicator and adapt my route.") {
                lateinit var route: Route

                given("a route through the high mountains with injected storm risk weather") {
                    route = Route(
                        id = "weather_test",
                        name = "High Mountain Line",
                        waypoints = listOf(
                            Waypoint(lat = 39.9, lon = -105.6, label = "Mt Evans Launch"),
                            Waypoint(lat = 40.1, lon = -105.7, label = "Stormy Peak")
                        )
                    )
                    showRouteOnMap(route)
                    // Inject thunderstorm weather for Stormy Peak waypoint
                    val stormForecast = com.ternparagliding.utils.WeatherForecast(
                        current = com.ternparagliding.utils.WeatherData(
                            wind = com.ternparagliding.utils.WindData(20.0, 270.0, 35.0),
                            temperature = 15.0, humidity = 90.0, visibility = 5.0,
                            pressure = 1005.0, cloudCover = 95.0,
                            timestamp = System.currentTimeMillis(),
                            cape = 2000.0, lightningPotential = 80.0
                        ),
                        daily = emptyList(), hourly = emptyList()
                    )
                    val stormyWpId = route.waypoints[1].id
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]
                        store.dispatch(WeatherActions.RouteWeatherFetched(
                            routeId = route.id,
                            waypointForecasts = mapOf(stormyWpId to stormForecast)
                        ))
                    }
                    composeTestRule.waitForIdle()
                }

                then("the route panel header should show STORM RISK badge", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("RouteDetailPanel").assertIsDisplayed()
                    composeTestRule.onNodeWithText("STORM RISK", substring = true).assertExists()
                }

                `when`("I expand the panel and remove the storm-risk waypoint") {
                    // Expand to TEA mode to see Delete buttons
                    composeTestRule.onNodeWithTag("SSA_Header").performClick()
                    composeTestRule.waitForIdle()
                    composeTestRule.onAllNodesWithContentDescription("Delete Waypoint").onFirst().performClick()
                    composeTestRule.waitForIdle()
                }

                then("the HUD should remain visible for the updated route", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("RoutePlanningHUD").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("HUD_Distance").assertIsDisplayed()
                }
            }
        }
    }

    /**
     * STORY 4: The 150km Window Check (4D Trajectory)
     * Narrative: As a pilot planning a long flight, I want to verify that the
     * weather at my goal is still safe when I eventually arrive there.
     */
    @Test
    fun pilot_verifies_4d_trajectory_weather() {
        scenario("The 150km Window Check") {
            story("As a pilot, I want to see forecast weather metrics in the HUD for my route.") {
                given("a long XC route with weather data injected") {
                    val longRoute = Route(
                        id = "long_xc",
                        name = "150km Alpine Tour",
                        waypoints = listOf(
                            Waypoint(lat = 46.5, lon = 8.5, label = "Fiesch Launch", type = LocationType.LAUNCH),
                            Waypoint(lat = 46.8, lon = 9.5, label = "Turnaround", type = LocationType.TURNPOINT),
                            Waypoint(lat = 46.5, lon = 8.5, label = "Fiesch Goal", type = LocationType.GOAL)
                        )
                    )
                    showRouteOnMap(longRoute)
                    // Inject weather and ETA data for the goal waypoint
                    val goalWpId = longRoute.waypoints[2].id
                    val goalForecast = com.ternparagliding.utils.WeatherForecast(
                        current = com.ternparagliding.utils.WeatherData(
                            wind = com.ternparagliding.utils.WindData(12.0, 225.0, 18.0),
                            temperature = 22.0, humidity = 45.0, visibility = 15.0,
                            pressure = 1015.0, cloudCover = 30.0,
                            timestamp = System.currentTimeMillis()
                        ),
                        daily = emptyList(), hourly = emptyList()
                    )
                    val etaAtGoal = System.currentTimeMillis() + 5 * 3600 * 1000 // 5 hours from now
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]
                        store.dispatch(WeatherActions.RouteWeatherFetched(
                            routeId = longRoute.id,
                            waypointForecasts = mapOf(goalWpId to goalForecast),
                            etas = mapOf(goalWpId to etaAtGoal)
                        ))
                    }
                    composeTestRule.waitForIdle()
                }

                then("the HUD should display ETA at Goal", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("RoutePlanningHUD").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("HUD_ETA_Goal").assertIsDisplayed()
                }

                and("the HUD should show the forecast wind") {
                    composeTestRule.onNodeWithTag("HUD_ETA_Wind").assertIsDisplayed()
                }

                and("the HUD should show Cloudbase and Lapse Rate metrics") {
                    composeTestRule.onNodeWithTag("HUD_Weather_Cloudbase").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("HUD_Weather_LapseRate").assertIsDisplayed()
                }
            }
        }
    }

    // --- Helpers ---

    private fun longPressOnMap(lat: Double, lon: Double) {
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val store = androidx.lifecycle.ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]
            val state = store.state.value

            val geoPoint = GeoPoint(lat, lon)
            if (state.selectedRouteId == null) {
                store.dispatch(MapAction.CheckSmartSuggestion(geoPoint))
            } else {
                store.dispatch(MapAction.LongPressMap(geoPoint))
            }
        }
        composeTestRule.waitForIdle()
    }
}
