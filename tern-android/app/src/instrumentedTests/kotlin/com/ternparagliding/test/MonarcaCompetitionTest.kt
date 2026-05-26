package com.ternparagliding.test

import androidx.compose.ui.test.*
import com.ternparagliding.model.LocationType
import com.ternparagliding.utils.Liar
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.model.Waypoint
import com.ternparagliding.model.Route
import com.ternparagliding.ui.givenAppIsLaunchedOnMap
import com.ternparagliding.utils.WeatherTestHelper
import org.junit.Test
import org.junit.Before
import org.junit.After

/**
 * BDD Instrumented Test - Monarca Open 2026 Story
 *
 * This test uses real competition data from the Monarca Paragliding Open 2026
 * (Valle de Bravo, Mexico).
 */
class MonarcaCompetitionTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Liar("ETA validated by checking for colon character, not actual values")
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
                    zoomTo(19.06167, -100.09033, 12.0)
                    waitForMapToRender(3000)
                    assertMapLocation(19.06167, -100.09033, tolerance = 0.01)
                    assertZoomLevel(12.0)
                    assertRoutePresence("Monarca 2026 - Task 1")
                }

                and("the pilot reviews the entire route for strategic planning") {
                    zoomToRouteEntirely(monarcaRoute)
                }

                and("the map automatically fits the entire route to the screen", takeScreenshot = true) {
                    waitForMapToRender(2000)
                    assertRoutePresence("Monarca 2026 - Task 1")
                }

                and("the pilot examines the task details screen", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("SSA_Header").performClick()
                    composeTestRule.waitForIdle()
                }

                then("the HUD should show the distance to the Speed Section (Ext9)", takeScreenshot = true) {
                    // TODO: write real assertions
                }

                `when`("the pilot enters the Speed Section cylinder") {
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]
                        store.dispatch(com.ternparagliding.redux.MapAction.UpdateUserLocation(org.osmdroid.util.GeoPoint(18.9, -100.2)))
                    }
                }

                then("Tern should calculate the ETA at Goal based on current glide", takeScreenshot = true) {
                    // TODO: write real assertions
                }

                and("the weather 4D trajectory should verify the conditions at A01 Goal") {
                    // TODO: write real assertions
                }
            }
        }
    }
}
