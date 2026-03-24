package com.madanala.tern.test

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.MapTestHelper
import com.madanala.tern.utils.WeatherTestHelper
import com.madanala.tern.ui.givenAppIsLaunchedOnMap
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.junit.Before
import org.junit.After
import android.util.Log

/**
 * StormRiskValidationTest: Validates RFC 005 implementation using "Instrumentation Truth".
 */
@RunWith(AndroidJUnit4::class)
class StormRiskValidationTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Test
    fun testStormRiskVisualization() {
        val launchPoint = GeoPoint(19.0433, -99.9142) // Peñon Launch
        val hazardPoint = GeoPoint(19.0650, -99.8800) // Near Turnpoint with high risk
        
        scenario("Truth-First Storm Risk Visualization") {
            given("the pilot is preparing for a flight with convective risk") {
                givenAppIsLaunchedOnMap(lat = launchPoint.latitude, lon = launchPoint.longitude, countryCode = "mx")
            }
            
            and("the weather service reports high CAPE and lightning potential at a waypoint") {
                WeatherTestHelper.setMockWeatherResponse(
                    latitude = hazardPoint.latitude,
                    longitude = hazardPoint.longitude,
                    cape = 1800.0,
                    lightningPotential = 75.0
                )
            }
            
            `when`("the pilot adds a route passing through the hazard zone") {
                MapTestHelper.longPressOnGeoPoint(composeTestRule.activity, launchPoint.latitude, launchPoint.longitude)
                waitForMapToRender(1000)
                // Deselect waypoint to hide edit screen and show RouteDetailPanel
                composeTestRule.runOnUiThread {
                    val store = androidx.lifecycle.ViewModelProvider(composeTestRule.activity)[com.madanala.tern.redux.MapStore::class.java]
                    store.dispatch(com.madanala.tern.redux.MapAction.DeselectWaypoint)
                }
                waitForMapToRender(500)
                
                MapTestHelper.longPressOnGeoPoint(composeTestRule.activity, hazardPoint.latitude, hazardPoint.longitude)
                waitForMapToRender(1000)
                // Deselect waypoint to hide edit screen and show RouteDetailPanel
                composeTestRule.runOnUiThread {
                    val store = androidx.lifecycle.ViewModelProvider(composeTestRule.activity)[com.madanala.tern.redux.MapStore::class.java]
                    store.dispatch(com.madanala.tern.redux.MapAction.DeselectWaypoint)
                }
                waitForMapToRender(500)
            }
            
            then("Tern should render an Amber Hazard Halo and a flashing Lightning Bolt", takeScreenshot = true) {
                waitForMapToRender(3000) // Allow extra time for weather sync
            }
            
            and("the route detail panel should display a high-visibility SSA header with storm risk alert") {
                // Wait for async weather fetch to complete and update state
                composeTestRule.waitUntil(10000) { // Robust wait for async state sync
                    composeTestRule.onAllNodesWithTag("AHV_BANNER").fetchSemanticsNodes().isNotEmpty()
                }

                // Verify SSA mode (collapsed) header and its alert (Auto-minimized after route selection)
                composeTestRule.onNodeWithTag("SSA_Header").assertIsDisplayed()
                composeTestRule.onNodeWithText("! STORM RISK", substring = true).assertIsDisplayed()
                
                // Verify global AHV banner
                composeTestRule.onNodeWithTag("AHV_BANNER").assertIsDisplayed()
            }
            
            `when`("the pilot expands the panel to TEA mode") {
                composeTestRule.onNodeWithTag("SSA_Header").performClick() // Toggle to expanded
                waitForMapToRender(500)
            }
            
            then("the panel should show high-density TEA mode with tactical telemetry") {
                composeTestRule.onNodeWithTag("TEA_Header").assertIsDisplayed()
                composeTestRule.onNodeWithTag("WaypointList").assertIsDisplayed()
                
                // Verify high-density telemetry (ETA and Wind)
                composeTestRule.onNodeWithText("ETA:", substring = true).assertIsDisplayed()
                composeTestRule.onNodeWithText("kt @", substring = true).assertIsDisplayed()
                
                // Verify per-waypoint AHV badge
                composeTestRule.onNodeWithTag("AHV_BADGE", useUnmergedTree = true).assertIsDisplayed()
                composeTestRule.onNodeWithText("THUNDERSTORM RISK DETECTED", substring = true).assertIsDisplayed()
            }
            
            and("the pilot reviews the entire route extents") {
                composeTestRule.runOnUiThread {
                    val activity = composeTestRule.activity
                    val store = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.redux.MapStore::class.java]
                    val routes = store.state.value.routes
                    if (routes.isNotEmpty()) {
                        zoomToRouteEntirely(routes.first())
                    }
                }
            }
            
            then("the map automatically fits the entire route and collapses back to SSA mode", takeScreenshot = true) {
                waitForMapToRender(3000)
                composeTestRule.onNodeWithTag("SSA_Header").assertIsDisplayed()
                composeTestRule.onNodeWithTag("WaypointList").assertDoesNotExist()
            }
        }
    }
}
