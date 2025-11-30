package com.madanala.tern.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.ui.screens.TernMapScreen
import com.madanala.tern.ui.theme.TernTheme
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.MapOverlayCacheUtils
import com.madanala.tern.utils.MapTestHelper
import com.madanala.tern.utils.ReportGenerator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@RunWith(AndroidJUnit4::class)
class SmartSuggestionTest : BddTest() {

    // composeTestRule is inherited from BaseUITest via BddTest<ComponentActivity>()

    @Test
    fun testSmartSuggestionLogic() {
        val spotLat = 40.0200
        val spotLon = -105.2705
        val spotName = "Wonderland Launch"

        scenario("Smart Suggestion Logic") {
            given("the app is initialized with a mock PG spot") {
                // Initialize CacheManager
                ReportGenerator.logStep("SETUP", "Initializing CacheManager")
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                CacheManager.initialize(context)

                // Create a mock OverlayFeature
                ReportGenerator.logStep("SETUP", "Creating mock PG spot: $spotName")
                val featureMap = mapOf(
                    "type" to "Feature",
                    "properties" to mapOf(
                        "name" to spotName,
                        "siteType" to "launch"
                    ),
                    "geometry" to mapOf(
                        "type" to "Point",
                        "coordinates" to listOf(spotLon, spotLat)
                    )
                )
                val centroid = GeoPoint(spotLat, spotLon)
                val mockSpot = MapOverlayCacheUtils.OverlayFeature(
                    feature = featureMap,
                    centroid = centroid,
                    hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
                    overlayType = "pgspot"
                )

                // Inject into cache (LOWERCASE "us" to match CountryUtils override)
                ReportGenerator.logStep("SETUP", "Injecting spot into PGSpotCache")
                com.madanala.tern.utils.TestCacheInjector.injectPGSpots(context, com.madanala.tern.utils.CacheManager.pgSpotCache, "us", listOf(mockSpot))
                
                // Force CountryUtils to return "us" (mocking Geocoder)
                ReportGenerator.logStep("SETUP", "Mocking CountryUtils to return 'us'")
                com.madanala.tern.utils.CountryUtils.setTestCountryCode("us")
            }

            then("MapViewModel finds the spot") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val application = context.applicationContext as android.app.Application
                
                var foundFeature: com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature? = null
                val latch = java.util.concurrent.CountDownLatch(1)

                // Get ViewModel (must be on main thread or use rule)
                // We can use runOnMainSync to get it and trigger
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val viewModel = androidx.lifecycle.ViewModelProvider(composeTestRule.activity).get(com.madanala.tern.ui.components.MapViewModel::class.java)
                    
                    // Initialize Redux Store and connect it
                    val store = com.madanala.tern.redux.MapStore()
                    store.addMiddleware(com.madanala.tern.redux.MapMiddleware(context.applicationContext))
                    viewModel.setMapStore(store)

                    // Launch a collector on the Store's state
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        store.state.collect { state ->
                            val spot = state.smartSuggestionState.nearbyPGSpot
                            if (spot != null) {
                                foundFeature = spot
                                latch.countDown()
                            }
                        }
                    }
                    
                    viewModel.checkForSmartSuggestion(
                        geoPoint = GeoPoint(spotLat, spotLon)
                    )
                }

                val success = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                assert(success) { "Timeout waiting for smart suggestion check" }
                assert(foundFeature != null) { "Did not find nearby spot" }
                
                val name = foundFeature?.feature?.get("properties")?.let { (it as? Map<*, *>)?.get("name") }
                assert(name == spotName) { "Expected spot name $spotName, but got $name" }

                // Validate Logcat
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE UPDATE STORM")
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
                // Note: We can't easily check for "Found X routes" here because this test mocks the cache directly 
                // and bypasses some of the RouteCache logic that logs that specific message.
                // Instead, we verify the feature was found via the assertion above.
            }
        }
    }
}
