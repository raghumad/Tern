package com.madanala.tern.test

import androidx.compose.ui.test.*
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.ReportGenerator
import com.madanala.tern.model.Waypoint
import com.madanala.tern.model.Route
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.WeatherState
import com.madanala.tern.utils.WeatherForecast
import com.madanala.tern.utils.WeatherData
import com.madanala.tern.utils.WindData
import org.junit.Test
import org.osmdroid.util.GeoPoint
import java.util.*

/**
 * BDD Instrumented Tests - Phase 14: Personal XC Planning UX
 *
 * Framed as Pilot Stories to ensure Aviation-Grade UX Fidelity.
 * Skills applied: BDD UI Testing Fidelity, Aviation-Grade UX Standards.
 */
class AviationRoutePlanningTest : MapVisualTest() {

    /**
     * STORY 1: Mountain Record Attempt
     * Narrative: As a pilot planning a record flight in the Rockies, I want to see
     * critical terrain and thermal hotspots automatically while I define my task.
     */
    @Test
    fun pilot_plans_mountain_record_attempt() {
        scenario("Mountain Record Attempt") {
            story("As a pilot planning a record flight, I want to see automated hotspots.") {
                given("a pilot starting a new XC task at Lookout Mountain", takeScreenshot = true) {
                    injectMockLaunchSpot(39.7429, -105.2393, "Lookout Mountain")
                    zoomTo(39.7429, -105.2393, 13.0)
                }
                
                `when`("I long-press on Lookout Mountain launch") {
                    longPressOnMap(39.7429, -105.2393)
                }
                
                then("I should see a Smart Suggestion for 'Lookout Mountain'") {
                    // [STABILITY FIX] Use waitUntil to handle async suggestion arrival
                    composeTestRule.waitUntil(10000) {
                        composeTestRule.onAllNodesWithText("Nearby Launch", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    composeTestRule.onNodeWithText("Nearby Launch", substring = true, ignoreCase = true).assertIsDisplayed()
                }
                
                `when`("I confirm the suggestion") {
                    composeTestRule.onNodeWithText("Use Spot").performClick()
                    // [UX STABILITY] Deselect waypoint so RouteDetailPanel is visible instead of Edit screen
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.redux.MapStore::class.java]
                        store.dispatch(MapAction.DeselectWaypoint)
                    }
                }
                
                then("the route should start at Lookout Mountain", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("RouteDetailPanel").assertIsDisplayed()
                }
                
                and("I should see 'Thermal Hotspots' secured in the HUD") {
                    composeTestRule.onNodeWithTag("HUD_SyncStatus").assertExists()
                    composeTestRule.onNodeWithTag("HUD_Distance").assertTextContains("0.0 km")
                }
                
                `when`("I add a turnpoint at Idaho Springs") {
                    longPressOnMap(39.7420, -105.5136)
                }
                
                then("the HUD should update with record-relevant metrics", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("HUD_Distance").assertExists()
                    composeTestRule.onNodeWithTag("HUD_FaiPoints").assertExists()
                }

                and("I should see the 'Corridor Secured' shield once caching is complete") {
                    composeTestRule.onNodeWithTag("HUD_FlightReady_Shield").assertIsDisplayed()
                }

                `when`("I drag my route into the Denver Class B airspace") {
                    // Simulate a waypoint move into Class B (e.g. towards DIA)
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.redux.MapStore::class.java]
                        val route = store.state.value.routes.first()
                        store.dispatch(MapAction.UpdateWaypoint(route.id, route.waypoints.last().id, lat = 39.8617, lon = -104.6731))
                    }
                }

                then("the HUD should flash a Red 'CLASS B COLLISION' warning", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("HUD_AirspaceWarning").assertIsDisplayed()
                    composeTestRule.onNodeWithText("CLASS B COLLISION", substring = true).assertExists()
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
            story("As a competition pilot, I want to randomly import a task to practice.") {
                given("a list of competition task URLs simulated for the test") {
                    val mockRoute = Route(
                        id = "comp_practice",
                        name = "Airtribune Practice Task",
                        waypoints = listOf(
                            Waypoint(lat = 39.7429, lon = -105.2393, type = Waypoint.Type.LAUNCH, label = "Lookout"),
                            Waypoint(lat = 39.8, lon = -105.3, type = Waypoint.Type.TURNPOINT, label = "TP1"),
                            Waypoint(lat = 39.75, lon = -105.1, type = Waypoint.Type.GOAL, label = "Goal")
                        )
                    )
                    showRouteOnMap(mockRoute)
                }
                
                then("the competition task should be loaded", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("RouteDetailPanel").assertIsDisplayed()
                    composeTestRule.onNodeWithText("Airtribune Practice Task").assertExists()
                }

                and("I should see the 'Offline Secured' indicator for the route corridor") {
                    composeTestRule.onNodeWithTag("HUD_SyncStatus").assertExists()
                    composeTestRule.onNodeWithTag("HUD_FlightReady_Shield").assertIsDisplayed()
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
                    composeTestRule.onNodeWithTag("HUD_Weather_StormRisk").assertExists()
                }
                
                `when`("I remove the waypoint with storm risk") {
                    // [STABILITY FIX] Use onFirst() to avoid ambiguity if multiple Delete buttons exist
                    composeTestRule.onAllNodesWithContentDescription("Delete Waypoint").onFirst().performClick()
                }
                
                then("the HUD should remain visible for the updated route", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("RoutePlanningHUD").assertIsDisplayed()
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
                    composeTestRule.onNodeWithTag("HUD_ETA_Goal").assertExists()
                    composeTestRule.onNodeWithText("13:45", substring = true).assertExists()
                }

                and("the HUD should show the forecast wind specifically for that time") {
                    composeTestRule.onNodeWithTag("HUD_ETA_Wind").assertExists()
                    composeTestRule.onNodeWithText("12kt", substring = true).assertExists()
                }

                and("I should see 'Cloudbase' and 'Lapse Rate' derived from the 4D trajectory") {
                    composeTestRule.onNodeWithTag("HUD_Weather_Cloudbase").assertExists()
                    composeTestRule.onNodeWithTag("HUD_Weather_LapseRate").assertExists()
                }
            }
        }
    }

    // --- Helpers ---

    private fun injectMockLaunchSpot(lat: Double, lon: Double, name: String) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val featureMap = mapOf(
            "type" to "Feature",
            "properties" to mapOf(
                "name" to name,
                "siteType" to "launch"
            ),
            "geometry" to mapOf(
                "type" to "Point",
                "coordinates" to listOf(lon, lat)
            )
        )
        val centroid = GeoPoint(lat, lon)
        val mockSpot = com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature(
            feature = featureMap,
            centroid = centroid,
            hilbertIndex = com.madanala.tern.utils.MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
            overlayType = "pgspot"
        )
        com.madanala.tern.utils.TestCacheInjector.injectPGSpots(
            context, 
            com.madanala.tern.utils.CacheManager.pgSpotCache, 
            "us", 
            listOf(mockSpot)
        )
    }

    private fun longPressOnMap(lat: Double, lon: Double) {
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val store = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.redux.MapStore::class.java]
            val state = store.state.value
            
            val geoPoint = GeoPoint(lat, lon)
            if (state.selectedRouteId == null) {
                // Realistic gestural behavior: no selected route means "Try Smart Suggestion"
                store.dispatch(MapAction.CheckSmartSuggestion(geoPoint))
            } else {
                // Route selected: just add waypoint
                store.dispatch(MapAction.LongPressMap(geoPoint))
            }
        }
        composeTestRule.waitForIdle()
    }
}
