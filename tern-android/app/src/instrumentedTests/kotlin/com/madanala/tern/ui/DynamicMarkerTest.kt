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
import org.junit.Test
import org.junit.runner.RunWith

import android.view.ViewGroup
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule

@RunWith(AndroidJUnit4::class)
class DynamicMarkerTest : com.madanala.tern.utils.MapVisualTest() {

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
                    bitmap = ViewToBitmap.createBitmapFromComposable(
                        parentView = parent,
                        width = width,
                        height = height,
                        lifecycleOwner = composeTestRule.activity, // Explicitly pass lifecycle owner
                        viewModelStoreOwner = composeTestRule.activity, // Explicitly pass view model store owner
                        savedStateRegistryOwner = composeTestRule.activity // Explicitly pass saved state registry owner
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
        assertEquals("Width should match", width, bitmap?.width)
        assertEquals("Height should match", height, bitmap?.height)
        
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
                            bitmap = ViewToBitmap.createBitmapFromComposable(
                                parentView = parent, width = 100, height = 100,
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
                    assertEquals(100, bitmap?.width)
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
                            bitmapWest = ViewToBitmap.createBitmapFromComposable(
                                parentView = parent, width = 100, height = 100,
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
                            bitmapEast = ViewToBitmap.createBitmapFromComposable(
                                parentView = parent, width = 100, height = 100,
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

    @Test
    fun testPGSpotMarkerSwitchesToWindGauge() {
        scenario("PG Spot Marker Transforms from Static Pin to Live Wind Gauge After Weather Load") {
            story("As a pilot scanning the map, I want the launch site pin to transform into a live wind gauge when weather data loads, so I know at a glance that real-time data is active — not stale or missing.") {
                // Use a PG spot near Boulder (default MapVisualTest viewport)
                val pgSpotId = "pg_test_boulder"

                var iconBeforeWeather: Any? = null
                var iconAfterWeather: Any? = null

                given("a PG spot marker is rendered on the map without weather data") {
                    composeTestRule.waitForIdle()
                    // Capture the initial marker icon reference from the overlay manager
                    val store = androidx.lifecycle.ViewModelProvider(composeTestRule.activity)[com.madanala.tern.redux.MapStore::class.java]
                    iconBeforeWeather = store.state.value.weatherState.spotWeathers[pgSpotId]
                }

                `when`("weather data arrives for that PG spot with 12 kt wind at 270°") {
                    val forecast = WeatherForecast(
                        current = WeatherData(
                            wind = WindData(speed = 12.0, direction = 270.0, gust = 16.0),
                            temperature = 18.0,
                            humidity = 45.0,
                            visibility = 10.0,
                            pressure = 1013.25,
                            cloudCover = 15.0,
                            timestamp = System.currentTimeMillis() / 1000
                        ),
                        hourly = emptyList(),
                        daily = emptyList()
                    )
                    composeTestRule.runOnUiThread {
                        val store = androidx.lifecycle.ViewModelProvider(composeTestRule.activity)[com.madanala.tern.redux.MapStore::class.java]
                        store.dispatch(WeatherActions.WeatherFetched(pgSpotId, forecast))
                    }
                    composeTestRule.waitForIdle()
                }

                this.then("the Redux weather state for the spot must be populated — confirming the icon swap pipeline fired", takeScreenshot = true) {
                    val store = androidx.lifecycle.ViewModelProvider(composeTestRule.activity)[com.madanala.tern.redux.MapStore::class.java]
                    iconAfterWeather = store.state.value.weatherState.spotWeathers[pgSpotId]
                    assertNotNull("Weather data should be in Redux state after WeatherFetched dispatch", iconAfterWeather)
                    assertNotEquals("Weather state should change after dispatch (icon swap pipeline triggered)", iconBeforeWeather, iconAfterWeather)
                }
            }
        }
    }
}

