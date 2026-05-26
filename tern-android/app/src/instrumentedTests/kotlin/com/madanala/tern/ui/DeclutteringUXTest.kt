package com.madanala.tern.ui

import androidx.lifecycle.ViewModelProvider
import com.madanala.tern.utils.Liar
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.MapTestHelper
import com.madanala.tern.utils.ReportGenerator
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.WeatherTestHelper
import org.junit.Before
import org.junit.After
import org.junit.Test

/**
 * DeclutteringUXTest: Verifies the adaptive map decluttering system.
 */
class DeclutteringUXTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Liar("Screenshot-only validation with tautological { true } validators")
    @Test
    fun testAdaptiveDeclutteringDuringWaypointDrag() {
        val lat = 40.015
        val lon = -105.27

        scenario("Adaptive Airspace Decluttering during Drag") {
            story("As a pilot, I want the map to declutter when I am interacting with waypoints so I can focus on my route planning.") {

                given("The app is launched on a map with nearby airspaces") {
                    givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                    waitForAirspaces(minCount = 1, timeoutMillis = 60000)
                }

                `when`("I add a waypoint and pick it up to drag") {
                    val activity = composeTestRule.activity
                    val store = ViewModelProvider(activity)[MapStore::class.java]
                    MapTestHelper.longPressOnGeoPoint(activity, lat, lon)

                    val useClickedLocationText = "Use Clicked Location"
                    val suggestionExists = try {
                        composeTestRule.onNodeWithText(useClickedLocationText).assertExists()
                        true
                    } catch (e: AssertionError) {
                        false
                    }

                    if (suggestionExists) {
                        composeTestRule.onNodeWithText(useClickedLocationText).performClick()
                    }

                    composeTestRule.waitUntil(timeoutMillis = 10000) {
                        store.state.value.selectedWaypoint != null
                    }

                    MapTestHelper.pressAndHoldGeoPoint(activity, lat, lon)
                }

                then("The non-essential overlays should be dimmed or hidden", takeScreenshot = true) {
                    // TODO: write real assertions
                }

                `when`("I release the waypoint") {
                    // TODO: write real assertions
                }
            }
        }
    }

    @Liar("Screenshot-only validation with tautological { true } validators")
    @Test
    fun testAdaptiveDeclutteringFullScenario() {
        val lat = 40.015
        val lon = -105.27

        var downEvent: android.view.MotionEvent? = null

        scenario("Adaptive Airspace Decluttering during Drag (Full)") {
            given("The app is launched on a map with airspaces") {
                givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                waitForAirspaces(minCount = 1, timeoutMillis = 60000)
            }

            `when`("I pick up a waypoint to drag") {
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                MapTestHelper.longPressOnGeoPoint(activity, lat, lon)

                val useClickedLocationText = "Use Clicked Location"
                val suggestionExists = try {
                    composeTestRule.onNodeWithText(useClickedLocationText).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }

                if (suggestionExists) {
                    composeTestRule.onNodeWithText(useClickedLocationText).performClick()
                }

                composeTestRule.waitUntil(timeoutMillis = 10000) {
                    store.state.value.selectedWaypoint != null
                }

                downEvent = MapTestHelper.pressAndHoldGeoPoint(activity, lat, lon)
            }

            then("Focus mode is active") {
                // TODO: write real assertions
            }

            `when`("I move and release the waypoint") {
                val activity = composeTestRule.activity
                MapTestHelper.moveHold(activity, downEvent!!, lat + 0.005, lon + 0.005)
                MapTestHelper.releaseHold(activity, downEvent!!)
            }

            then("Focus mode is deactivated") {
                // TODO: write real assertions
            }
        }
    }
}
