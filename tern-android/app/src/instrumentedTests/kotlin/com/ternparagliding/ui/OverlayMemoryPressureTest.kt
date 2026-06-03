package com.ternparagliding.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.CacheManager
import com.ternparagliding.utils.CountryUtils
import com.ternparagliding.utils.MapOverlayCacheUtils
import com.ternparagliding.utils.MapOverlayCacheUtils.OverlayFeature
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.PerformanceDebugger
import com.ternparagliding.utils.ReportGenerator
import com.ternparagliding.utils.TestCacheInjector
import com.ternparagliding.utils.WeatherTestHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Overlay memory-pressure scorecard — driven by REAL airspace data.
 *
 * Fixtures are the app's own production source
 * (storage.googleapis.com/…/{cc}_asp.geojson, OpenAIP format) filtered to the
 * three flown regions and bundled as assets, then loaded through the
 * PRODUCTION parser (parseGeoJsonToFeatures) — real CTR/TMA/restricted
 * polygons (up to ~1800 vertices each), real ICAO classes, real positions.
 * No synthetic geometry.
 *
 * The scenario you fly: centre on Boulder, zoom out so Boulder + Colorado
 * Springs are both in frame with Denver centred (initial condition, asserted),
 * then drag the centre Colorado → Washington DC → Annecy, repeatedly:
 *   - CO → DC: same country (US), ~2500 km Hilbert jump — discards the Colorado
 *     feature set, hydrates the DC set from the memory-mapped buffer.
 *   - DC → Annecy: a different country (FR) and the airspace-dense French Alps —
 *     cross-country cache swap (new mmap buffer, LRU eviction), fresh query.
 *
 * Each pan exercises the real path: Hilbert range query → memory-mapped buffer
 * reads → per-feature FlexBuffers hydration of complex polygons → overlay
 * budgeting → GeoJSON rebuild → discard of the previous collection (GC
 * pressure). logHeapUsage() snapshots every phase so the Performance Scorecard
 * reflects the churn over real data.
 */
class OverlayMemoryPressureTest : MapVisualTest() {

    private val BOULDER = GeoPoint(40.0150, -105.2705)
    private val DENVER = GeoPoint(39.7392, -104.9903)        // Boulder ~30 km N, CoSprings ~100 km S
    private val DC = GeoPoint(38.9072, -77.0369)             // east-coast US, far Hilbert jump
    private val ANNECY = GeoPoint(45.8992, 6.1294)           // French Alps — airspace-dense
    private val REGION_ZOOM = 8.0                            // Boulder + Colorado Springs both in frame
    private val CYCLES = 3

    @Before
    fun startMockServer() = WeatherTestHelper.startServer().let { }

    @After
    fun stopMockServer() = WeatherTestHelper.stopServer()

    @Test
    fun testAirspaceChurnAcrossRegionsAndCountries() {
        scenario("Overlay memory pressure: Colorado → Washington DC → Annecy (real airspace)") {
            story("As the app flying long XC across regions and a border, I load/clear dense REAL airspace repeatedly without leaking or stalling.") {
                given("real OpenAIP airspace is cached for the US (Colorado + DC) and France (Alps)") {
                    val target = InstrumentationRegistry.getInstrumentation().targetContext
                    val us = loadAirspaceAsset("airspace/us_asp_test.geojson")
                    val fr = loadAirspaceAsset("airspace/fr_asp_test.geojson")
                    TestCacheInjector.injectAirspaces(target, CacheManager.airspaceCache, "US", us)
                    TestCacheInjector.injectAirspaces(target, CacheManager.airspaceCache, "FR", fr)
                    ReportGenerator.logStep(
                        "GIVEN",
                        "seeded ${us.size} US + ${fr.size} FR REAL airspaces (CTR/TMA/restricted, OpenAIP)",
                        "PASS",
                    )
                    givenAppIsLaunchedOnMap(lat = BOULDER.latitude, lon = BOULDER.longitude, countryCode = "us")
                    PerformanceDebugger.logHeapUsage("BASELINE")
                }

                // INITIAL CONDITION — established AND verified (was the missing part).
                and(
                    "the map is framed on Boulder + Colorado Springs with Denver centred, showing real Colorado airspace",
                    takeScreenshot = true,
                ) {
                    setCountry("US")
                    dispatch(MapAction.AddAirspaceCountry("US"))
                    panTo(DENVER, REGION_ZOOM)
                    waitForAirspaces(minCount = 1, timeoutMillis = 15000)
                    val shot = captureScreenBitmap()
                    val blue = blueDominantPixels(shot, centralBox(shot), minBlue = 50)
                    ReportGenerator.logStep(
                        "ASSERT",
                        "real Colorado airspace blue-px in the Boulder/Denver/CoSprings frame: $blue",
                        if (blue >= 30) "PASS" else "FAIL",
                    )
                    if (blue < 30) {
                        throw AssertionError(
                            "Initial condition not met: no real Colorado airspace rendered in the " +
                                "Boulder + Colorado Springs frame (blue=$blue)",
                        )
                    }
                }

                `when`("the pilot drags Colorado → Washington DC → Annecy repeatedly under churn") {
                    for (cycle in 1..CYCLES) {
                        setCountry("US")
                        panTo(DENVER, REGION_ZOOM)
                        PerformanceDebugger.logHeapUsage("CYCLE${cycle}_US_COLORADO")

                        panTo(DC, REGION_ZOOM)
                        PerformanceDebugger.logHeapUsage("CYCLE${cycle}_US_DC")

                        setCountry("FR")
                        dispatch(MapAction.AddAirspaceCountry("FR"))
                        panTo(ANNECY, REGION_ZOOM)
                        PerformanceDebugger.logHeapUsage("CYCLE${cycle}_FR_ANNECY")

                        ReportGenerator.logStep("AND", "completed churn cycle $cycle/$CYCLES", "PASS")
                    }
                    PerformanceDebugger.logHeapUsage("FINAL")
                }

                then("real Alpine airspace is still rendering at Annecy after the churn", takeScreenshot = true) {
                    val shot = captureScreenBitmap()
                    val blue = blueDominantPixels(shot, centralBox(shot), minBlue = 50)
                    ReportGenerator.logStep(
                        "ASSERT",
                        "real Annecy airspace blue-px after churn: $blue",
                        if (blue >= 30) "PASS" else "FAIL",
                    )
                    if (blue < 30) {
                        throw AssertionError("No real airspace rendered after churn (blue=$blue) — scorecard would be a no-op")
                    }
                }

                and("the app survived the churn (no crash)") {
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                }
            }
        }
    }

    // --- helpers ---

    /** Loads a bundled real-airspace GeoJSON from the TEST apk and parses it
     *  through the production parser (full GeoJSON features, nested props). */
    private fun loadAirspaceAsset(path: String): List<OverlayFeature> {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open(path).readBytes().decodeToString()
        return MapOverlayCacheUtils.parseGeoJsonToFeatures(json, "airspace")
    }

    private fun setCountry(code: String) = CountryUtils.setTestCountryCode(code)

    private fun dispatch(action: MapAction) {
        composeTestRule.runOnUiThread {
            ViewModelProvider(composeTestRule.activity)[MapStore::class.java].dispatch(action)
        }
    }

    /** Programmatic centre move (no gesture, so the gesture-only MapLibre→Redux
     *  feedback won't overwrite it). Sleeps long enough for
     *  AirspaceOverlay's LaunchedEffect(center) to re-query on Dispatchers.IO,
     *  hydrate the complex real polygons, rebuild the GeoJSON and recompose. */
    private fun panTo(p: GeoPoint, zoom: Double) {
        dispatch(MapAction.UpdateCenter(p))
        dispatch(MapAction.UpdateZoom(zoom))
        composeTestRule.waitForIdle()
        Thread.sleep(3000)
    }
}
