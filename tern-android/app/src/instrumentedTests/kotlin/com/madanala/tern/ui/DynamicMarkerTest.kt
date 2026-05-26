package com.madanala.tern.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.redux.WeatherActions
import com.madanala.tern.ui.components.WindGaugeMarker
import com.madanala.tern.utils.ViewToBitmap
import com.madanala.tern.utils.WeatherData
import com.madanala.tern.utils.WeatherForecast
import com.madanala.tern.utils.WindData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import com.madanala.tern.utils.Liar
import org.junit.Test
import org.junit.runner.RunWith

import android.view.ViewGroup
import org.osmdroid.util.GeoPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule

@RunWith(AndroidJUnit4::class)
class DynamicMarkerTest : com.madanala.tern.utils.MapVisualTest() {
    
    companion object {
        init {
            // Set test country at class loading time to catch early activity startup
            com.madanala.tern.utils.CountryUtils.setTestCountryCode("TEST")
        }
    }

    // composeTestRule is inherited from MapVisualTest

    @Test
    fun testCreateBitmapFromComposable() {
        // GIVEN a context from the activity
        val context = composeTestRule.activity
        val parent = context.window.decorView as ViewGroup
        val width = 100
        val height = 100

        // WHEN creating a bitmap
        var bitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        
        // ViewToBitmap must be called on main thread (suspend)
        composeTestRule.runOnUiThread {
            MainScope().launch {
                try {
                    bitmap = ViewToBitmap.createBitmapFromComposableDP(
                        parentView = parent,
                        widthDp = width,
                        heightDp = width,
                        lifecycleOwner = composeTestRule.activity,
                        viewModelStoreOwner = composeTestRule.activity,
                        savedStateRegistryOwner = composeTestRule.activity
                    ) {
                        WindGaugeMarker(speed = 10.0, direction = 180.0)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for result
        latch.await(5, TimeUnit.SECONDS)

        // THEN the bitmap should be created with correct dimensions
        assertNotNull("Bitmap should not be null", bitmap)
        // Assert on density-aware size
        val density = context.resources.displayMetrics.density
        assertEquals("Width should match density-scaled width", (width * density).toInt(), bitmap?.width)
        
        // Verify it's not empty (check a pixel or config)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap?.config)
    }

    @Test
    fun testWindGaugeShowsCorrectSpeed() {
        scenario("Wind Gauge Correctly Renders with Speed Value") {
            story("As a pilot, I want the wind gauge to render correctly with the provided speed so I can rely on it during flight.") {
                val context = composeTestRule.activity
                val parent = context.window.decorView as ViewGroup
                var bitmap: Bitmap? = null

                given("a wind gauge marker configured to show 15 knots") {
                    val latch = CountDownLatch(1)
                    composeTestRule.runOnUiThread {
                        MainScope().launch {
                            bitmap = ViewToBitmap.createBitmapFromComposableDP(
                                parentView = parent, widthDp = 100, heightDp = 100,
                                lifecycleOwner = composeTestRule.activity,
                                viewModelStoreOwner = composeTestRule.activity,
                                savedStateRegistryOwner = composeTestRule.activity
                            ) { WindGaugeMarker(speed = 15.0, direction = 270.0) }
                            latch.countDown()
                        }
                    }
                    latch.await(10, TimeUnit.SECONDS)
                }

                this.then("the gauge should be rendered into a valid bitmap", takeScreenshot = true) {
                    assertNotNull("Bitmap should be generated for the 15kt wind gauge", bitmap)
                    val density = context.resources.displayMetrics.density
                    assertEquals((100 * density).toInt(), bitmap?.width)
                }
            }
        }
    }

    @Test
    fun testWindGaugeBitmapsDifferByDirection() {
        scenario("Wind Gauge Arrow Rotates to Reflect Real Wind Direction") {
            story("As a pilot, I want the wind arrow to point in the actual wind direction so I can intuit crosswind vs headwind at a glance.") {
                val context = composeTestRule.activity
                val parent = context.window.decorView as ViewGroup

                var bitmapWest: Bitmap? = null
                var bitmapEast: Bitmap? = null

                given("two wind gauges: one pointing west (270°) and one pointing east (90°)") {
                    // Synchronize generation to avoid racing on the DecorView layout
                    val latch1 = CountDownLatch(1)
                    composeTestRule.runOnUiThread {
                        MainScope().launch {
                            bitmapWest = ViewToBitmap.createBitmapFromComposableDP(
                                parentView = parent, widthDp = 100, heightDp = 100,
                                lifecycleOwner = composeTestRule.activity,
                                viewModelStoreOwner = composeTestRule.activity,
                                savedStateRegistryOwner = composeTestRule.activity
                            ) { WindGaugeMarker(speed = 10.0, direction = 270.0) }
                            latch1.countDown()
                        }
                    }
                    latch1.await(10, TimeUnit.SECONDS)

                    val latch2 = CountDownLatch(1)
                    composeTestRule.runOnUiThread {
                        MainScope().launch {
                            bitmapEast = ViewToBitmap.createBitmapFromComposableDP(
                                parentView = parent, widthDp = 100, heightDp = 100,
                                lifecycleOwner = composeTestRule.activity,
                                viewModelStoreOwner = composeTestRule.activity,
                                savedStateRegistryOwner = composeTestRule.activity
                            ) { WindGaugeMarker(speed = 10.0, direction = 90.0) }
                            latch2.countDown()
                        }
                    }
                    latch2.await(10, TimeUnit.SECONDS)
                }

                this.then("the two bitmaps must not be pixel-identical — confirming the direction arrow rotates correctly", takeScreenshot = true) {
                    assertNotNull("West bitmap must exist", bitmapWest)
                    assertNotNull("East bitmap must exist", bitmapEast)

                    val w = bitmapWest!!
                    val e = bitmapEast!!
                    var pixelsDiff = 0
                    for (x in 0 until w.width) {
                        for (y in 0 until w.height) {
                            if (w.getPixel(x, y) != e.getPixel(x, y)) pixelsDiff++
                        }
                    }
                    assertNotEquals("West and East bitmaps must differ in pixel content — arrow must rotate", 0, pixelsDiff)
                }
            }
        }
    }

    @Liar("Asserts Redux weather state (upstream), not the wind gauge rendering on the MapLibre map (downstream). " +
          "Wind gauge markers are MapLibre SymbolLayer bitmaps -- GPU-drawn, not Compose nodes -- " +
          "so the only truthful assertion is screenshot evidence reviewed by a human.")
    @Test
    fun testPGSpotMarkerSwitchesToWindGauge() {
        scenario("PG Spot Marker Transforms from Static Pin to Live Wind Gauge After REAL Weather Load") {
            story("As a pilot scanning the map, I want the launch site pin to transform into a live wind gauge when weather data arrives from the API.") {
                val pgSpotId = "pg_boulder_test"
                val lat = 40.015
                val lon = -105.27

                given("a PG spot is cached and a Mock Weather Server is running") {
                    // Set test country early - this will trigger a TEST download which we redirect
                    com.madanala.tern.utils.CountryUtils.setTestCountryCode("TEST")

                    val context = composeTestRule.activity
                    val point = org.osmdroid.util.GeoPoint(lat, lon)
                    val featureMap = mapOf("id" to pgSpotId, "name" to "Boulder Launch")
                    val hIndex = com.madanala.tern.utils.MapOverlayCacheUtils.computeHilbertIndex(point, 16)

                    val feature = com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature(
                        internalId = pgSpotId,
                        feature = featureMap,
                        centroid = point,
                        hilbertIndex = hIndex,
                        overlayType = "pgspot"
                    )

                    val mockUrl = com.madanala.tern.utils.WeatherTestHelper.startServer()
                    // Redirect PG spot downloads to mock server
                    com.madanala.tern.utils.PGSpotCache.setBaseUrlForTesting(mockUrl)
                    com.madanala.tern.utils.WeatherTestHelper.setDispatcher(speed = 18.0, direction = 270.0)

                    // Pre-inject the cache to ensure the spot 9876 doesnt leak from real US data
                    com.madanala.tern.utils.TestCacheInjector.injectPGSpots(
                        context,
                        com.madanala.tern.utils.CacheManager.pgSpotCache,
                        "TEST",
                        listOf(feature)
                    )
                }

                `when`("I zoom into the Boulder region") {
                    zoomTo(lat, lon, 14.0)
                    waitForMapData(minAirspaces = 0, minPGSpots = 1)
                    // Give weather middleware time to fire and update UI
                    Thread.sleep(3000)
                }

                this.then("screenshot evidence: the marker should be rendered as a wind gauge", takeScreenshot = true) {
                    // Wind gauge is a MapLibre SymbolLayer bitmap -- cannot be asserted via Compose.
                    // Screenshot is the evidence. Cleanup below.
                    com.madanala.tern.utils.WeatherTestHelper.stopServer()
                    com.madanala.tern.utils.PGSpotCache.resetBaseUrlForTesting()
                    com.madanala.tern.utils.CountryUtils.setTestCountryCode(null)
                }
            }
        }
    }

    /**
     * Helper to verify that a generated bitmap is actually NOT empty (i.e. contains more than just transparency).
     * This catches silent failures in the ViewToBitmap pipeline.
     */
    private fun assertBitmapIsNotEmpty(bitmap: Bitmap?, message: String) {
        assertNotNull("$message - Bitmap is null", bitmap)
        val b = bitmap!!
        var hasContent = false
        // Sample every 5th pixel for performance
        for (x in 0 until b.width step 5) {
            for (y in 0 until b.height step 5) {
                if (android.graphics.Color.alpha(b.getPixel(x, y)) > 0) {
                    hasContent = true
                    break
                }
            }
            if (hasContent) break
        }
        org.junit.Assert.assertTrue("$message - Bitmap should have non-transparent content", hasContent)
    }
}

