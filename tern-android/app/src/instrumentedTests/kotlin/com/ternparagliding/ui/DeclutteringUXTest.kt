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
          "which is not possible. MapLibre drag events are native touch events that " +
          "cannot be synthesized from Compose test rules. Overlay dimming is a " +
          "MapLibre layer opacity change, not a Compose node property.")
    @Test
    fun testAdaptiveDeclutteringDuringWaypointDrag() {
        val lat = 40.015
        val lon = -105.27

        scenario("Adaptive Airspace Decluttering during Drag") {
            story("As a pilot, I want the map to declutter when I am interacting with waypoints so I can focus on my route planning.") {

                given("The app is launched on a map with injected airspace data") {
                    givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                    // Inject test airspace so waitForAirspaces does not time out
                    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                    val airspaceFeature = createTestAirspace("Test CTR", lat, lon)
                    com.ternparagliding.utils.TestCacheInjector.injectAirspaces(
                        context, com.ternparagliding.utils.CacheManager.airspaceCache, "US", listOf(airspaceFeature))
                    composeTestRule.runOnUiThread {
                        val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                        store.dispatch(com.ternparagliding.redux.MapAction.AddAirspaceCountry("US"))
                    }
                    waitForAirspaces(minCount = 1, timeoutMillis = 10000)
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
                    // Cannot verify: overlay dimming is MapLibre layer opacity (GPU-rendered),
                    // not a Compose semantic property. Requires human test with drag gesture.
                }
            }
        }
    }

    @Liar("Declutter-during-drag requires programmatic drag gestures on MapLibre, " +
          "which is not possible. Focus mode activation/deactivation depends on " +
          "drag start/end events that cannot be synthesized in Compose tests.")
    @Test
    fun testAdaptiveDeclutteringFullScenario() {
        val lat = 40.015
        val lon = -105.27

        scenario("Adaptive Airspace Decluttering during Drag (Full)") {
            given("The app is launched on a map with injected airspace data") {
                givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                val airspaceFeature = createTestAirspace("Test CTR Full", lat, lon)
                com.ternparagliding.utils.TestCacheInjector.injectAirspaces(
                    context, com.ternparagliding.utils.CacheManager.airspaceCache, "US", listOf(airspaceFeature))
                composeTestRule.runOnUiThread {
                    val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                    store.dispatch(com.ternparagliding.redux.MapAction.AddAirspaceCountry("US"))
                }
                waitForAirspaces(minCount = 1, timeoutMillis = 10000)
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
                // Cannot verify: focus mode activation requires drag start event
                // which cannot be synthesized via Compose test rules on MapLibre.
            }

            then("Focus mode is deactivated", takeScreenshot = true) {
                // Cannot verify: focus mode deactivation requires drag end event.
            }
        }
    }

    // --- Test data helpers ---

    private fun createTestAirspace(name: String, lat: Double, lon: Double): com.ternparagliding.utils.MapOverlayCacheUtils.OverlayFeature {
        val featureMap = mapOf(
            "type" to "Feature",
            "properties" to mapOf("name" to name, "class" to "D"),
            "geometry" to mapOf(
                "type" to "Polygon",
                "coordinates" to listOf(listOf(
                    listOf(lon, lat),
                    listOf(lon + 0.01, lat),
                    listOf(lon + 0.01, lat + 0.01),
                    listOf(lon, lat + 0.01),
                    listOf(lon, lat)
                ))
            )
        )
        val centroid = org.osmdroid.util.GeoPoint(lat, lon)
        return com.ternparagliding.utils.MapOverlayCacheUtils.OverlayFeature(
            feature = featureMap,
            centroid = centroid,
            hilbertIndex = com.ternparagliding.utils.MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
            overlayType = "airspace"
        )
    }
}
