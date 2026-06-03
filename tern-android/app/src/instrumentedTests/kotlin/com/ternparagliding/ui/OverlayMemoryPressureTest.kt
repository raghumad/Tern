package com.ternparagliding.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.ternparagliding.overlay.pgspot.PG_SPOT_TEAL
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.CacheManager
import com.ternparagliding.utils.CountryUtils
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.PerformanceDebugger
import com.ternparagliding.utils.ReportGenerator
import com.ternparagliding.utils.VisualValidator
import com.ternparagliding.utils.WeatherTestHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Overlay memory-pressure scorecard — REAL end-to-end, on a real device.
 *
 * Runs against the now-wired country download path (CountryPreloadMiddleware →
 * UniversalCountryCacheManager): the test pins NO country, so as the map centre
 * moves the app geocodes the country and downloads + builds the real airspace +
 * PG-spot caches over the network (and preloads adjacent countries near
 * borders), exactly as in flight. Requires a device with a live network and
 * Geocoder (the phone) — the managed emulator can't fetch tiles or downloads.
 *
 * Scenario:
 *   1. Boulder → frame Boulder + Colorado Springs with Denver centred; wait for
 *      the real US airspace/PG-spot download to land AND the basemap tiles to
 *      paint, then assert real airspace + PG spots render (initial condition).
 *   2. Slow-drag Colorado → Washington DC (same country: continuous overlay
 *      eviction/reload + streaming tiles).
 *   3. Jump to Annecy, then slow-drag France → Switzerland → Italy across the
 *      Alps, triggering background downloads + LRU eviction of new countries at
 *      each border.
 *
 * Heap is snapshotted at each stop only AFTER tiles + overlays have painted, so
 * the Performance Scorecard reflects real memory pressure (decoded tiles +
 * hydrated airspace polygons + PG-spot bitmaps + cross-country cache swaps).
 */
class OverlayMemoryPressureTest : MapVisualTest() {

    private val BOULDER = GeoPoint(40.0150, -105.2705)
    private val DENVER = GeoPoint(39.7392, -104.9903)
    private val DC = GeoPoint(38.9072, -77.0369)
    private val ANNECY_FR = GeoPoint(45.8992, 6.1294)
    private val GENEVA_CH = GeoPoint(46.2044, 6.1432)
    private val AOSTA_IT = GeoPoint(45.7372, 7.3206)
    private val REGION_ZOOM = 9.0

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Test
    fun testRealDownloadChurnColoradoToDcToAlps() {
        scenario("Overlay memory pressure: real downloads, Colorado → DC → Alps") {
            story("As a pilot flying long XC, the app downloads/clears real airspace + PG spots + tiles across regions and borders without leaking or stalling.") {
                given("no country is pinned and the real airspace/PG-spot endpoints are used") {
                    // Undo MapVisualTest's "TEST" pin so CountryPreloadMiddleware
                    // runs and CountryUtils geocodes the real country.
                    CountryUtils.setTestCountryCode(null)
                    // MapVisualTest.setup() starts the weather mock, which also
                    // redirects the airspace + PG-spot endpoints to localhost.
                    // Restore the real endpoints so this test downloads REAL data
                    // (weather stays mocked — irrelevant here).
                    CacheManager.airspaceCache.resetBaseUrlForTesting()
                    com.ternparagliding.utils.PGSpotCache.resetBaseUrlForTesting()
                    givenAppIsLaunchedOnMap(lat = BOULDER.latitude, lon = BOULDER.longitude, countryCode = null)
                    PerformanceDebugger.logHeapUsage("BASELINE")
                }

                // INITIAL CONDITION — real data downloaded, tiles painted, verified.
                and(
                    "Boulder + Colorado Springs are framed with Denver centred; real US airspace + PG spots download and render over loaded tiles",
                    takeScreenshot = true,
                ) {
                    panTo(DENVER, REGION_ZOOM)
                    val loaded = waitForCountryLoaded("US", timeoutMillis = 180_000)
                    if (!loaded) throw AssertionError("US airspace never downloaded — country download path not working")
                    val tiles = waitForBasemapTiles(timeoutMillis = 60_000)
                    if (!tiles) throw AssertionError("basemap tiles never loaded over Colorado")
                    waitForMapToRender(2000)
                    assertOverlaysRendered("Colorado")
                    PerformanceDebugger.logHeapUsage("US_COLORADO")
                }

                `when`("the pilot slow-drags Colorado → Washington DC (same country)") {
                    slowDrag(DENVER, DC, steps = 12, zoom = REGION_ZOOM)
                    waitForBasemapTiles()
                    waitForMapToRender(2000)
                    PerformanceDebugger.logHeapUsage("US_DC")
                }

                and("the pilot crosses the Alps: France → Switzerland → Italy (background downloads at borders)") {
                    // Jump the Atlantic, then drag across the dense Alpine borders.
                    panTo(ANNECY_FR, REGION_ZOOM)
                    waitForCountryLoaded("FR", timeoutMillis = 120_000)
                    waitForBasemapTiles()
                    PerformanceDebugger.logHeapUsage("FR_ANNECY")

                    slowDrag(ANNECY_FR, GENEVA_CH, steps = 8, zoom = REGION_ZOOM)
                    waitForCountryLoaded("CH", timeoutMillis = 120_000)
                    waitForBasemapTiles()
                    PerformanceDebugger.logHeapUsage("CH_GENEVA")

                    slowDrag(GENEVA_CH, AOSTA_IT, steps = 8, zoom = REGION_ZOOM)
                    waitForCountryLoaded("IT", timeoutMillis = 120_000)
                    waitForBasemapTiles()
                    PerformanceDebugger.logHeapUsage("IT_AOSTA")
                    PerformanceDebugger.logHeapUsage("FINAL")
                }

                then("real airspace + PG spots are still rendering after the cross-border churn", takeScreenshot = true) {
                    waitForMapToRender(2000)
                    assertOverlaysRendered("Aosta/Alps")
                }

                and("the app survived the churn (no crash)") {
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                }
            }
        }
    }

    // --- helpers ---

    /** Polls until the country's data has finished downloading AND the overlay
     *  refresh fired — i.e. the full chain middleware → UCCM download →
     *  onCountryLoaded → AddAirspaceCountry → state.airspaceCountries. */
    private fun waitForCountryLoaded(cc: String, timeoutMillis: Long): Boolean {
        val want = cc.uppercase()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMillis) {
            var has = false
            composeTestRule.runOnUiThread {
                val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                has = store.state.value.airspaceCountries.any { it.equals(want, ignoreCase = true) }
            }
            if (has) {
                ReportGenerator.logStep("AND", "country $want downloaded + overlays refreshed in ${(System.currentTimeMillis() - start) / 1000}s", "PASS")
                return true
            }
            Thread.sleep(2000)
        }
        return false
    }

    private fun assertOverlaysRendered(where: String) {
        val shot = captureScreenBitmap()
        val rect = centralBox(shot)
        val blue = blueDominantPixels(shot, rect, minBlue = 50)
        val teal = tealDominantPixels(shot, rect) +
            if (VisualValidator.findColorSignature(shot, rect, PG_SPOT_TEAL, tolerance = 20)) 50 else 0
        val lum = meanLuminance(shot, rect)
        ReportGenerator.logStep(
            "ASSERT",
            "$where: tile-luminance=${lum.toInt()}, airspace blue-px=$blue, PG-spot teal-px=$teal",
            if (blue >= 30 && teal >= 20) "PASS" else "FAIL",
        )
        if (blue < 30) throw AssertionError("$where: real airspace not rendered (blue=$blue)")
        if (teal < 20) throw AssertionError("$where: PG spots not rendered (teal=$teal)")
    }

    private fun dispatch(action: MapAction) {
        composeTestRule.runOnUiThread {
            ViewModelProvider(composeTestRule.activity)[MapStore::class.java].dispatch(action)
        }
    }

    private fun panTo(p: GeoPoint, zoom: Double) {
        dispatch(MapAction.UpdateCenter(p))
        dispatch(MapAction.UpdateZoom(zoom))
        composeTestRule.waitForIdle()
        Thread.sleep(2500)
    }

    /** Simulates a slow drag by stepping the centre in increments — each step
     *  moves the viewport, evicting out-of-frame overlays and loading new ones,
     *  and (near borders) crossing into new countries. */
    private fun slowDrag(from: GeoPoint, to: GeoPoint, steps: Int, zoom: Double) {
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val lat = from.latitude + (to.latitude - from.latitude) * t
            val lon = from.longitude + (to.longitude - from.longitude) * t
            dispatch(MapAction.UpdateCenter(GeoPoint(lat, lon)))
            dispatch(MapAction.UpdateZoom(zoom))
            composeTestRule.waitForIdle()
            Thread.sleep(1200)
        }
    }
}
