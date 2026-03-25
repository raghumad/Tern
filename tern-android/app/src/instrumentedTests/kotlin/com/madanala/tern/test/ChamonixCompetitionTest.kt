package com.madanala.tern.test
import com.madanala.tern.model.LocationType

import androidx.compose.ui.test.*
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.model.Waypoint
import com.madanala.tern.model.Route
import com.madanala.tern.redux.MapAction
import com.madanala.tern.ui.givenAppIsLaunchedOnMap
import com.madanala.tern.utils.WeatherTestHelper
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.osmdroid.util.GeoPoint

/**
 * BDD Instrumented Test - Chamonix Open 2026 Story
 * 
 * Verifies high-fidelity route planning and HUD metrics in a complex Alpine environment.
 * Validated against Aviation-Grade UX and Instrumentation Truth skills.
 */
class ChamonixCompetitionTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Test
    fun pilot_flies_chamonix_valley_tour() {
        scenario("Chamonix Open 2026 - Valley Tour") {
            story("As a pilot in the Chamonix Open, I want to navigate a complex task across the valley with accurate telemetry.") {
                
                val chamonixWaypoints = listOf(
                    Waypoint(lat = 45.937, lon = 6.843, type = LocationType.LAUNCH, label = "Planpraz Launch", radius = 400.0),
                    Waypoint(lat = 45.934, lon = 6.837, type = LocationType.SSS, label = "SSS Brévent", radius = 2000.0),
                    Waypoint(lat = 45.960, lon = 6.886, type = LocationType.TURNPOINT, label = "TP1 Flégère", radius = 400.0),
                    Waypoint(lat = 45.931, lon = 6.917, type = LocationType.TURNPOINT, label = "TP2 Mer de Glace", radius = 1000.0),
                    Waypoint(lat = 45.928, lon = 6.872, type = LocationType.TURNPOINT, label = "ESS Bois du Bouchet", radius = 400.0),
                    Waypoint(lat = 45.928, lon = 6.869, type = LocationType.GOAL, label = "Goal Clos du Savoy", radius = 100.0)
                )

                val chamonixRoute = Route(
                    id = "chamonix_open_task",
                    name = "Chamonix Open 2026 - Valley Tour",
                    waypoints = chamonixWaypoints
                )

                given("the pilot is at Planpraz Launch ready for the task", takeScreenshot = true) {
                    givenAppIsLaunchedOnMap(lat = 45.937, lon = 6.843, countryCode = "fr")
                    showRouteOnMap(chamonixRoute)
                    
                    // [STABILITY] Re-zoom to launch (showRoute dispatches SelectRoute which auto-zooms to extent)
                    zoomTo(45.937, 6.843, 12.0)
                    waitForMapToRender(2000)

                    // VERIFIABLE GIVEN: Prove state and location
                    assertMapLocation(45.937, 6.843, tolerance = 0.01)
                    assertRoutePresence("Chamonix Open 2026 - Valley Tour")
                }

                `when`("the map automatically fits the entire route to the screen", takeScreenshot = true) {
                    // [UX SKILL] Strategic Auto-Minimize: Fitting entire route should collapse the panel
                    zoomToRouteEntirely(chamonixRoute)
                    waitForMapToRender(2000)
                }

                then("the route detail panel should auto-minimize to SSA mode") {
                    composeTestRule.onNodeWithTag("SSA_Header").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("WaypointList").assertDoesNotExist()
                    composeTestRule.onNodeWithText("Chamonix Open 2026", substring = true).assertIsDisplayed()
                }

                `when`("the pilot clicks on the header to expand tactically", takeScreenshot = true) {
                    // [UX SKILL] High tactility (hit target) and manual toggle
                    composeTestRule.onNodeWithTag("SSA_Header").performClick()
                    composeTestRule.waitForIdle()
                }

                then("the panel should transition to TEA mode with detailed telemetry") {
                    composeTestRule.onNodeWithTag("TEA_Header").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("WaypointList").assertIsDisplayed()
                    
                    // Verify presence of turnpoints in the tactical list
                    composeTestRule.onNodeWithText("TP2 Mer de Glace", substring = true).assertIsDisplayed()
                    composeTestRule.onNodeWithText("r1000m", substring = true).assertIsDisplayed()
                }

                and("the HUD should display truthful distance and ETA metrics", takeScreenshot = true) {
                    // [INSTRUMENTATION TRUTH] Verify Redux-derived metrics (Watermark Assertions)
                    composeTestRule.onNodeWithTag("HUD_Distance").assertExists()
                    composeTestRule.onNodeWithTag("HUD_Distance").assertTextContains("km", substring = true)
                    
                    composeTestRule.onNodeWithTag("HUD_ETA_Goal").assertExists()
                    // Match HH:mm format via substring check or RegEx
                    composeTestRule.onNodeWithTag("HUD_ETA_Goal").assertTextContains(":", substring = true)
                }

                `when`("the pilot enters the Start Speed Section cylinder") {
                    // Simulate movement to a location within the SSS radius (2km from Brévent)
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.redux.MapStore::class.java]
                        store.dispatch(com.madanala.tern.redux.MapAction.UpdateUserLocation(GeoPoint(45.935, 6.840)))
                    }
                    waitForMapToRender(1000)
                }

                then("the tactical telemetry should update to show distance to the next goal (TP1)", takeScreenshot = true) {
                    // HUD matches active waypoint
                    composeTestRule.onAllNodes(hasText("TP1 Flégère", substring = true)).assertCountEquals(1)
                    
                    // Verify the "shield" indicator for weather-verified conditions (4D trajectory)
                    composeTestRule.onNodeWithTag("HUD_FlightReady_Shield").assertIsDisplayed()
                }
            }
        }
    }
}
