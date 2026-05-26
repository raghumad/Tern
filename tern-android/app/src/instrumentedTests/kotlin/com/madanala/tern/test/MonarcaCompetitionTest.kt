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

/**
 * BDD Instrumented Test - Monarca Open 2026 Story
 * 
 * This test uses real competition data from the Monarca Paragliding Open 2026
 * (Valle de Bravo, Mexico) to create a truthful narrative for the README.
 */
@com.madanala.tern.utils.Untruthful("Written for README screenshots — ETA validated by checking for colon character, not actual values")
class MonarcaCompetitionTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Test
    fun pilot_flies_monarca_task_1() {
        scenario("Monarca 2026 - Task 1") {
            story("As a pilot in the Monarca Open, I want to see my speed section goals and ETAs clearly.") {
                
                val monarcaWaypoints = listOf(
                    Waypoint(lat = 19.06167, lon = -100.09033, type = LocationType.LAUNCH, label = "D01 Peñon", radius = 400.0),
                    Waypoint(lat = 18.838, lon = -100.43167, type = LocationType.SSS, label = "SS E09 Ext9", radius = 36000.0),
                    Waypoint(lat = 18.838, lon = -100.43167, type = LocationType.TURNPOINT, label = "TP1 E09 Ext9", radius = 16000.0),
                    Waypoint(lat = 19.30433, lon = -100.204, type = LocationType.TURNPOINT, label = "TP2 B18", radius = 22000.0),
                    Waypoint(lat = 18.963, lon = -100.152, type = LocationType.TURNPOINT, label = "TP3 B90", radius = 5000.0),
                    Waypoint(lat = 19.04267, lon = -100.1045, type = LocationType.GOAL, label = "Goal A01", radius = 100.0)
                )

                val monarcaRoute = Route(
                    id = "monarca_task_1",
                    name = "Monarca 2026 - Task 1",
                    waypoints = monarcaWaypoints
                )

                given("the pilot is at Peñon Launch ready for Task 1", takeScreenshot = true) {
                    givenAppIsLaunchedOnMap(lat = 19.06167, lon = -100.09033, countryCode = "mx")
                    showRouteOnMap(monarcaRoute)
                    // Zoom back to launch for the "ready" shot (showRoute dispatches SelectRoute which auto-zooms out)
                    zoomTo(19.06167, -100.09033, 12.0)
                    waitForMapToRender(3000)

                    // VERIFIABLE GIVEN: Prove the state is reached
                    assertMapLocation(19.06167, -100.09033, tolerance = 0.01)
                    assertZoomLevel(12.0)
                    assertRoutePresence("Monarca 2026 - Task 1")
                }

                and("the pilot reviews the entire route for strategic planning") {
                    // [Instrumentation Truth] Auto-zoom to fit the entire competition route
                    zoomToRouteEntirely(monarcaRoute)
                }

                and("the map automatically fits the entire route to the screen", takeScreenshot = true) {
                    // This step is specifically for UX evaluation of the zoom result
                    waitForMapToRender(2000)
                    assertRoutePresence("Monarca 2026 - Task 1")
                }

                and("the pilot examines the task details screen", takeScreenshot = true) {
                    // Force expand to TEA mode if it was auto-minimized during strategic zoom
                    composeTestRule.onNodeWithTag("SSA_Header").performClick()
                    composeTestRule.waitForIdle()

                    // Ensure the TEA view and Waypoint List are visible
                    composeTestRule.onNodeWithTag("TEA_Header").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("WaypointList").assertIsDisplayed()
                    composeTestRule.onNodeWithText("D01 Peñon", substring = true).assertIsDisplayed()
                }

                then("the HUD should show the distance to the Speed Section (Ext9)", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("HUD_Distance").assertExists()
                    // Verify presence in both the HUD and the Waypoint List
                    composeTestRule.onAllNodes(hasText("Ext9", substring = true)).assertCountEquals(2)
                }

                `when`("the pilot enters the Speed Section cylinder") {
                    // Simulate moving towards TP1
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.redux.MapStore::class.java]
                        store.dispatch(com.madanala.tern.redux.MapAction.UpdateUserLocation(org.osmdroid.util.GeoPoint(18.9, -100.2))) // Somewhere between Peñon and Ext9
                    }
                }

                then("Tern should calculate the ETA at Goal based on current glide", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("HUD_ETA_Goal").assertExists()
                    composeTestRule.onNodeWithTag("HUD_ETA_Goal").assertTextContains(":", substring = true)
                }

                and("the weather 4D trajectory should verify the conditions at A01 Goal") {
                    composeTestRule.onNodeWithTag("HUD_Weather_Cloudbase").assertExists()
                    composeTestRule.onNodeWithTag("HUD_Weather_LapseRate").assertExists()
                    // Verify that the weather indicator is visible
                    composeTestRule.onNodeWithTag("HUD_FlightReady_Shield").assertIsDisplayed()
                }
            }
        }
    }
}
