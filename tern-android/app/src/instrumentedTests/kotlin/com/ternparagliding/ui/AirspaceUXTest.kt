package com.ternparagliding.ui

import androidx.compose.ui.test.*
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.WeatherTestHelper
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Airspace overlay — honest on-map render verification.
 *
 * Was `@Liar`: the old test injected airspace, panned *away* from it
 * (11 km off-screen), tapped nothing, and only asserted the map didn't
 * crash. The stated reason ("FillLayer can't be verified via Compose")
 * is obsolete — the GPU surface is verifiable with a hardware screenshot,
 * exactly as `thenExpectHazardColorOnMap` does for hazard halos.
 *
 * This version injects a Class-D polygon covering the viewport centre and
 * asserts the translucent blue airspace fill actually paints the map
 * (blue-dominant pixels appear in the centre where there were none before).
 */
class AirspaceUXTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Test
    fun testAirspaceRendersOnMap() {
        val lat = 40.0
        val lon = -105.2
        scenario("Airspace renders on the map (Boulder Class-D)") {
            story("As a pilot planning a cross-country flight, I want restricted airspace drawn on the map so I can see and avoid it.") {
                var baselineBlue = 0

                given("the app is on the map over Boulder with no airspace yet") {
                    givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                    // Sample the centre BEFORE any airspace exists, so the
                    // assertion proves the airspace (not the basemap) is blue.
                    val before = captureScreenBitmap()
                    baselineBlue = blueDominantPixels(before, centralBox(before), minBlue = 50)
                }

                `when`("Boulder Class-D airspace data is loaded and the overlay queries it") {
                    val context = androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation().targetContext
                    // ~±1.3 km polygon: at zoom 13 the whole square (incl. its
                    // solid blue border) sits inside the central scan box, so we
                    // catch both the translucent fill and the opaque line.
                    val airspaceFeature = createTestAirspace("Boulder CTR", lat, lon, halfDeg = 0.012)
                    com.ternparagliding.utils.TestCacheInjector.injectAirspaces(
                        context,
                        com.ternparagliding.utils.cache.CacheManager.airspaceCache,
                        "US",
                        listOf(airspaceFeature),
                    )
                    composeTestRule.runOnUiThread {
                        val store = androidx.lifecycle.ViewModelProvider(
                            composeTestRule.activity
                        )[com.ternparagliding.redux.MapStore::class.java]
                        store.dispatch(com.ternparagliding.redux.MapAction.AddAirspaceCountry("US"))
                    }
                    waitForAirspaces(minCount = 1, timeoutMillis = 15000)
                    // The launch query ran before the injection and the centre
                    // hasn't moved, so AirspaceOverlay's LaunchedEffect(center)
                    // won't re-fire. Hop >2 km away then back onto the polygon to
                    // force a fresh query that now finds the data, leaving the
                    // polygon centred in the frame.
                    zoomTo(lat + 0.03, lon, 13.0)
                    zoomTo(lat, lon, 13.0)
                    waitForMapToRender(2500)
                }

                then("the blue airspace polygon is painted on the map", takeScreenshot = true) {
                    val after = captureScreenBitmap()
                    val blue = blueDominantPixels(after, centralBox(after), minBlue = 50)
                    com.ternparagliding.utils.ReportGenerator.logStep(
                        "ASSERT",
                        "blue-dominant pixels: baseline=$baselineBlue, with-airspace=$blue",
                        if (blue >= baselineBlue + 150) "PASS" else "FAIL",
                    )
                    if (blue < baselineBlue + 150) {
                        throw AssertionError(
                            "Airspace fill not rendered: blue-dominant pixels only $blue " +
                                "(baseline $baselineBlue) in the central map region",
                        )
                    }
                }

                and("the map is still alive (no crash)") {
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                }
            }
        }
    }

    // --- Test data helpers ---

    private fun createTestAirspace(
        name: String,
        lat: Double,
        lon: Double,
        halfDeg: Double = 0.01,
    ): com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature {
        val featureMap = mapOf(
            "type" to "Feature",
            "properties" to mapOf("name" to name, "class" to "D"),
            "geometry" to mapOf(
                "type" to "Polygon",
                "coordinates" to listOf(
                    listOf(
                        listOf(lon - halfDeg, lat - halfDeg),
                        listOf(lon + halfDeg, lat - halfDeg),
                        listOf(lon + halfDeg, lat + halfDeg),
                        listOf(lon - halfDeg, lat + halfDeg),
                        listOf(lon - halfDeg, lat - halfDeg),
                    )
                )
            )
        )
        val centroid = GeoPoint(lat, lon)
        return com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature(
            feature = featureMap,
            centroid = centroid,
            hilbertIndex = com.ternparagliding.utils.cache.MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
            overlayType = "airspace",
        )
    }
}
