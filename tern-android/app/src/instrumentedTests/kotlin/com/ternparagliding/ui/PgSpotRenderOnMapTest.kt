package com.ternparagliding.ui

import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.overlay.pgspot.PG_SPOT_TEAL
import com.ternparagliding.utils.cache.CacheManager
import com.ternparagliding.utils.cache.MapOverlayCacheUtils
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.ReportGenerator
import com.ternparagliding.utils.TestCacheInjector
import com.ternparagliding.utils.VisualValidator
import com.ternparagliding.utils.WeatherTestHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * PG-spot overlay — honest on-map render verification.
 *
 * The existing PG-spot tests check the *weather dialog* (Compose) or render
 * the marker bitmap to an offline montage — neither proves the marker
 * actually paints the live map. This drives the real production path
 * (inject cache → PgSpotOverlay queries → UpdatePgSpotGeoJson → PgSpotLayer)
 * and asserts the Tern-bird badge's teal is present on the GPU surface.
 */
class PgSpotRenderOnMapTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Test
    fun testPgSpotRendersOnMap() {
        val lat = 39.7429
        val lon = -105.2393
        scenario("PG spot renders on the map (Lookout Mtn)") {
            story("As a pilot, I want launch sites drawn on the map so I can find somewhere to fly.") {
                given("the app is on the map over Lookout Mountain") {
                    givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                }

                `when`("a PG spot is loaded and the overlay queries it into view") {
                    injectMockPGSpot(lat, lon, "Lookout Mtn")
                    // PgSpotOverlay only re-queries after the pilot moves >2 km
                    // (REQUERY_DISTANCE_KM). The launch query ran before the
                    // injection, so push the camera away then back onto the spot
                    // to force a fresh query that now finds the data, leaving
                    // the spot in the centre of the frame.
                    zoomTo(lat + 0.03, lon, 13.0)
                    zoomTo(lat, lon, 15.0)
                    waitForPGSpots(minCount = 1, timeoutMillis = 15000)
                    waitForMapToRender(2000)
                }

                then("the teal Tern-bird PG-spot badge is painted on the map", takeScreenshot = true) {
                    val shot = captureScreenBitmap()
                    val rect = centralBox(shot)
                    val exact = VisualValidator.findColorSignature(shot, rect, PG_SPOT_TEAL, tolerance = 20)
                    val tealPx = tealDominantPixels(shot, rect)
                    val found = exact || tealPx >= 20
                    ReportGenerator.logStep(
                        "ASSERT",
                        "PG-spot badge teal: exact=$exact, teal-dominant px=$tealPx",
                        if (found) "PASS" else "FAIL",
                    )
                    if (!found) {
                        throw AssertionError(
                            "PG-spot marker not rendered: no teal/cyan badge " +
                                "(exact=$exact, teal-dominant px=$tealPx) in central map region $rect",
                        )
                    }
                }

                and("the map is still alive (no crash)") {
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                }
            }
        }
    }

    private fun injectMockPGSpot(lat: Double, lon: Double, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val featureMap = mapOf(
            "type" to "Feature",
            "properties" to mapOf("name" to name, "siteType" to "launch"),
            "geometry" to mapOf("type" to "Point", "coordinates" to listOf(lon, lat)),
        )
        val centroid = GeoPoint(lat, lon)
        val mockSpot = MapOverlayCacheUtils.OverlayFeature(
            feature = featureMap,
            centroid = centroid,
            hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
            overlayType = "pgspot",
        )
        TestCacheInjector.injectPGSpots(context, CacheManager.pgSpotCache, "US", listOf(mockSpot))
    }
}
