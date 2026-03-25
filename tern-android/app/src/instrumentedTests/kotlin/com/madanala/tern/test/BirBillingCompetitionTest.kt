package com.madanala.tern.test

import com.madanala.tern.model.LocationType
import androidx.compose.ui.test.*
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.model.Waypoint
import com.madanala.tern.model.Route
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.WeatherActions
import com.madanala.tern.ui.givenAppIsLaunchedOnMap
import com.madanala.tern.utils.WeatherTestHelper
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.osmdroid.util.GeoPoint

/**
 * BDD Instrumented Test - Bir Billing XC Task
 * 
 * Verifies high-fidelity route planning and HUD metrics in the Indian Himalayas.
 * Validated against Aviation-Grade UX and Instrumentation Truth skills.
 */
class BirBillingCompetitionTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Test
    fun pilot_flies_bir_billing_himalayan_odyssey() {
        scenario("Bir Billing XC - Himalayan Odyssey") {
            story("As a pilot in Bir Billing, I want to navigate a high-altitude task into the Dhauladhar back-range, managing midday convective risks.") {
                
                // 11:00 AM IST = 05:30 AM UTC
                val startTimeIST = "11:00 AM IST"
                
                val birWaypoints = listOf(
                    Waypoint(lat = 32.052, lon = 76.738, type = LocationType.LAUNCH, label = "Billing Takeoff", radius = 400.0),
                    Waypoint(lat = 32.055, lon = 76.745, type = LocationType.SSS, label = "SSS Billing", radius = 2000.0),
                    Waypoint(lat = 32.2462, lon = 76.4617, type = LocationType.TURNPOINT, label = "Dhauladhar Peak", radius = 1000.0),
                    Waypoint(lat = 32.3417, lon = 77.0408, type = LocationType.TURNPOINT, label = "Hanuman Tibba", radius = 2000.0),
                    Waypoint(lat = 32.1, lon = 76.93, type = LocationType.TURNPOINT, label = "Dehnasar Lake", radius = 1000.0),
                    Waypoint(lat = 32.029, lon = 76.717, type = LocationType.ESS, label = "ESS Bir Landing", radius = 400.0),
                    Waypoint(lat = 32.029, lon = 76.715, type = LocationType.GOAL, label = "Bir Landing", radius = 100.0)
                )

                val birRoute = Route(
                    id = "bir_billing_himalayan_odyssey",
                    name = "Bir Billing XC - Himalayan Odyssey",
                    waypoints = birWaypoints
                )

                given("the pilot is at Billing Takeoff at $startTimeIST", takeScreenshot = true) {
                    givenAppIsLaunchedOnMap(lat = 32.052, lon = 76.738, countryCode = "in")
                    showRouteOnMap(birRoute)
                    
                    // Zoom to task area
                    zoomTo(32.052, 76.738, 13.0)
                    waitForMapToRender(2000)

                    // VERIFIABLE GIVEN: Prove state and location
                    assertMapLocation(32.052, 76.738, tolerance = 0.01)
                    assertRoutePresence("Bir Billing XC - Himalayan Odyssey")
                }

                `when`("the map automatically fits the entire route to the screen", takeScreenshot = true) {
                    zoomToRouteEntirely(birRoute)
                    waitForMapToRender(2000)
                }

                then("the route detail panel should show the deep mountain waypoints") {
                    composeTestRule.onNodeWithTag("SSA_Header").assertIsDisplayed()
                    composeTestRule.onNodeWithText("Himalayan Odyssey", substring = true).assertIsDisplayed()
                }

                `when`("the pilot clicks on the header to expand tactically", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("SSA_Header").performClick()
                    composeTestRule.waitForIdle()
                }

                then("the tactical telemetry should list Hanuman Tibba and Dehnasar Lake") {
                    composeTestRule.onNodeWithTag("TEA_Header").assertIsDisplayed()
                    composeTestRule.onNodeWithText("Hanuman Tibba", substring = true).assertIsDisplayed()
                    composeTestRule.onNodeWithText("Dehnasar Lake", substring = true).assertIsDisplayed()
                }

                `when`("midday convection triggers thunderstorm risks at Hanuman Tibba", takeScreenshot = true) {
                    // Simulate high-altitude weather update with thunderstorm risk
                    WeatherTestHelper.setMockWeatherResponse(lightningPotential = 1.0)
                    
                    // Trigger weather fetch for the specific route
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.redux.MapStore::class.java]
                        store.dispatch(WeatherActions.FetchWeatherForRoute("bir_billing_himalayan_odyssey"))
                    }
                    
                    // Zoom towards the back-range to see the hazard
                    zoomTo(32.3417, 77.0408, 12.0)
                    waitForMapToRender(1000)
                }

                then("the Hazard Layer should display lightning bolts over the high peaks") {
                    composeTestRule.onNodeWithTag("HazardBolt_Hanuman Tibba").assertExists()
                    composeTestRule.onNodeWithTag("HazardBolt_Dehnasar Lake").assertExists()
                }

                `when`("the pilot enters the Start Speed Section cylinder near midday") {
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.redux.MapStore::class.java]
                        store.dispatch(com.madanala.tern.redux.MapAction.UpdateUserLocation(GeoPoint(32.054, 76.740)))
                    }
                    waitForMapToRender(1000)
                }

                then("the HUD should display tactical distance to the back-range targets", takeScreenshot = true) {
                    // Verify the "shield" indicator for weather-verified conditions
                    composeTestRule.onNodeWithTag("HUD_FlightReady_Shield").assertIsDisplayed()
                }
            }
        }
    }
}
