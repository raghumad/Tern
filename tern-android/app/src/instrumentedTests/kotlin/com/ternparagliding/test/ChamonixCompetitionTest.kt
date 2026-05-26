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
import org.osmdroid.util.GeoPoint

/**
 * BDD Instrumented Test - Chamonix Open 2026 Story
 *
 * Verifies high-fidelity route planning and HUD metrics in a complex Alpine environment.
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

    @Liar("Truthful metrics step checks for km substring, not actual distance")
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
                    zoomTo(45.937, 6.843, 12.0)
                    waitForMapToRender(2000)
                    assertMapLocation(45.937, 6.843, tolerance = 0.01)
                    assertRoutePresence("Chamonix Open 2026 - Valley Tour")
                }

                `when`("the map fits the route entirely and we zoom to a clumping level", takeScreenshot = true) {
                    zoomToRouteEntirely(chamonixRoute)
                    zoomTo(45.937, 6.843, 12.0)
                    waitForMapToRender(2000)
                }

                then("the route detail panel should auto-minimize to SSA mode") {
                    // TODO: write real assertions
                }

                `when`("the pilot clicks on the header to expand tactically", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("SSA_Header").performClick()
                    composeTestRule.waitForIdle()
                }

                then("the panel should transition to TEA mode with detailed telemetry") {
                    // TODO: write real assertions
                }

                and("the HUD should display truthful distance and ETA metrics", takeScreenshot = true) {
                    // TODO: write real assertions
                }

                `when`("the pilot enters the Start Speed Section cylinder") {
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]
                        store.dispatch(com.ternparagliding.redux.MapAction.UpdateUserLocation(GeoPoint(45.935, 6.840)))
                    }
                    waitForMapToRender(1000)
                }

                then("the tactical telemetry should update to show distance to the next goal (TP1)", takeScreenshot = true) {
                    // TODO: write real assertions
                }
            }
        }
    }
}
