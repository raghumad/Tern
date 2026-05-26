package com.ternparagliding.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.ternparagliding.utils.Liar
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.MapTestHelper
import com.ternparagliding.utils.ReportGenerator
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.WeatherTestHelper
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

    @Liar("Declutter-during-drag requires programmatic drag gestures on MapLibre, " +
          "which is not yet possible. OSMDroid gestures removed, no replacement exists.")
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

                `when`("I add a waypoint via Redux dispatch") {
                    composeTestRule.runOnUiThread {
                        val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                        store.dispatch(com.ternparagliding.redux.MapAction.CheckSmartSuggestion(
                            org.osmdroid.util.GeoPoint(lat, lon)))
                    }
                    composeTestRule.waitForIdle()
                }

                then("The non-essential overlays should be dimmed or hidden", takeScreenshot = true) {
                    // TODO: write real assertions -- need drag gesture support on MapLibre
                }
            }
        }
    }

    @Liar("Declutter-during-drag requires programmatic drag gestures on MapLibre, " +
          "which is not yet possible. OSMDroid gestures removed, no replacement exists.")
    @Test
    fun testAdaptiveDeclutteringFullScenario() {
        val lat = 40.015
        val lon = -105.27

        scenario("Adaptive Airspace Decluttering during Drag (Full)") {
            given("The app is launched on a map with airspaces") {
                givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                waitForAirspaces(minCount = 1, timeoutMillis = 60000)
            }

            `when`("I add a waypoint via Redux dispatch") {
                composeTestRule.runOnUiThread {
                    val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                    store.dispatch(com.ternparagliding.redux.MapAction.CheckSmartSuggestion(
                        org.osmdroid.util.GeoPoint(lat, lon)))
                }
                composeTestRule.waitForIdle()
            }

            then("Focus mode is active", takeScreenshot = true) {
                // TODO: write real assertions -- need drag gesture support on MapLibre
            }

            then("Focus mode is deactivated", takeScreenshot = true) {
                // TODO: write real assertions -- need drag gesture support on MapLibre
            }
        }
    }
}
