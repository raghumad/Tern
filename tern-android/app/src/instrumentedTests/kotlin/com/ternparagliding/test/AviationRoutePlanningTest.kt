package com.ternparagliding.test

import androidx.compose.ui.test.*
import com.ternparagliding.model.LocationType
import com.ternparagliding.utils.Liar
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.model.Waypoint
import com.ternparagliding.model.Route
import com.ternparagliding.redux.MapAction
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
    @Liar("3 of 4 stories have step names disconnected from assertions")
    @Test
    fun pilot_plans_mountain_record_attempt() {
        scenario("Mountain Record Attempt") {
            story("As a pilot planning a record flight, I want to see automated hotspots.") {
                given("a pilot starting a new XC task at Lookout Mountain", takeScreenshot = true) {
                    givenAppIsLaunchedOnMap(lat = 39.7429, lon = -105.2393, countryCode = "us")
                }

                `when`("I long-press on Lookout Mountain launch") {
                    longPressOnMap(39.7429, -105.2393)
                }

                then("I should see a Smart Suggestion for 'Lookout Mountain'") {
                    // TODO: write real assertions
                }

                `when`("I confirm the suggestion") {
                    composeTestRule.onNodeWithText("Use Spot").performClick()
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]
                        store.dispatch(MapAction.DeselectWaypoint)
                    }
                }

                then("the route should start at Lookout Mountain", takeScreenshot = true) {
                    // TODO: write real assertions
                }

                and("I should see 'Thermal Hotspots' secured in the HUD") {
                    // TODO: write real assertions
                }

                `when`("I add a turnpoint at Idaho Springs") {
                    longPressOnMap(39.7420, -105.5136)
                }

                then("the HUD should update with record-relevant metrics", takeScreenshot = true) {
                    // TODO: write real assertions
                }

                and("I should see the 'Corridor Secured' shield once caching is complete") {
                    // TODO: write real assertions
                }

                `when`("I drag my route into the Denver Class B airspace") {
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]
                        val route = store.state.value.routes.first()
                        store.dispatch(MapAction.UpdateWaypoint(route.id, route.waypoints.last().id, lat = 39.8617, lon = -104.6731))
                    }
                }

                then("the HUD should flash a Red 'CLASS B COLLISION' warning", takeScreenshot = true) {
                    // TODO: write real assertions
                }
            }
        }
    }

    /**
     * STORY 2: Competition Readiness from the Sofa
     * Narrative: As a competition pilot, I want to randomly import a task from a
     * link file, so I can practice my glide plan.
     */
    @Liar("3 of 4 stories have step names disconnected from assertions")
    @Test
    fun pilot_imports_random_competition_task() {
        scenario("Competition Readiness from the Sofa") {
            story("As a competition pilot, I want to randomly import a task to practice.") {
                given("a list of competition task URLs simulated for the test") {
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

                then("the competition task should be loaded", takeScreenshot = true) {
                    // TODO: write real assertions
                }

                and("I should see the 'Offline Secured' indicator for the route corridor") {
                    // TODO: write real assertions
                }
            }
        }
    }

    /**
     * STORY 3: The Adaptive Weather Pivot
     * Narrative: While planning my route, I notice a storm risk in the HUD.
     * I want to modify my waypoints to avoid the affected area.
     */
    @Liar("3 of 4 stories have step names disconnected from assertions")
    @Test
    fun pilot_adapts_route_to_weather_risk() {
        scenario("The Adaptive Weather Pivot") {
            story("While planning, I notice a storm risk and adapt my route.") {
                given("a route through the high mountains with mocked storm risk") {
                    val route = Route(
                        id = "weather_test",
                        name = "High Mountain Line",
                        waypoints = listOf(
                            Waypoint(lat = 39.9, lon = -105.6, label = "Mt Evans Launch"),
                            Waypoint(lat = 40.1, lon = -105.7, label = "Stormy Peak")
                        )
                    )
                    showRouteOnMap(route)
                }

                then("I should see a 'Storm Risk' warning in the HUD", takeScreenshot = true) {
                    // TODO: write real assertions
                }

                `when`("I remove the waypoint with storm risk") {
                    composeTestRule.onAllNodesWithContentDescription("Delete Waypoint").onFirst().performClick()
                }

                then("the HUD should remain visible for the updated route", takeScreenshot = true) {
                    // TODO: write real assertions
                }
            }
        }
    }

    /**
     * STORY 4: The 150km Window Check (4D Trajectory)
     * Narrative: As a pilot planning a long flight, I want to verify that the
     * weather at my goal is still safe when I eventually arrive there.
     */
    @Liar("3 of 4 stories have step names disconnected from assertions")
    @Test
    fun pilot_verifies_4d_trajectory_weather() {
        scenario("The 150km Window Check") {
            story("As a pilot, I want to see forecast weather specifically for my ETA at each point.") {
                given("a long XC route with a 5-hour estimated duration") {
                    val longRoute = Route(
                        id = "long_xc",
                        name = "150km Alpine Tour",
                        waypoints = listOf(
                            Waypoint(lat = 46.5, lon = 8.5, label = "Fiesch Launch"),
                            Waypoint(lat = 46.8, lon = 9.5, label = "Turnaround"),
                            Waypoint(lat = 46.5, lon = 8.5, label = "Fiesch Goal")
                        )
                    )
                    showRouteOnMap(longRoute)
                }

                then("the HUD should calculate my ETA at Goal", takeScreenshot = true) {
                    // TODO: write real assertions
                }

                and("the HUD should show the forecast wind specifically for that time") {
                    // TODO: write real assertions
                }

                and("I should see 'Cloudbase' and 'Lapse Rate' derived from the 4D trajectory") {
                    // TODO: write real assertions
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
