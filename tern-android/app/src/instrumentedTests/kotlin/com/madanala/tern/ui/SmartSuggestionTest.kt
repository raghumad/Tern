package com.madanala.tern.ui

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.MapOverlayCacheUtils
import com.madanala.tern.utils.MapTestHelper
import com.madanala.tern.utils.ReportGenerator
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@RunWith(AndroidJUnit4::class)
class SmartSuggestionTest : MapVisualTest() {

    // composeTestRule is inherited from BaseUITest via BddTest<ComponentActivity>()

    @Test
    fun testSmartSuggestionLogic() {
        val spotLat = 40.0200
        val spotLon = -105.2705
        val spotName = "Wonderland Launch"

        scenario("Smart Suggestion Logic") {
            story("As a pilot arriving at a launch site, I want the app to automatically detect the paragliding spot so I can quickly access its weather and flight stats.") {
                given("I am arriving at 'Wonderland Launch' with my flight gear") {
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

                `when`("The flight computer detects my coordinates at the launch site") {
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    
                    // Get ViewModel and trigger check on main thread
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val viewModel = androidx.lifecycle.ViewModelProvider(composeTestRule.activity).get(com.madanala.tern.ui.components.MapViewModel::class.java)
                        
                        // Initialize Redux Store and connect it
                        val store = com.madanala.tern.redux.MapStore()
                        store.addMiddleware(com.madanala.tern.redux.MapMiddleware(context.applicationContext))
                        viewModel.setMapStore(store)

                        viewModel.checkForSmartSuggestion(
                            geoPoint = GeoPoint(spotLat, spotLon)
                        )
                    }
                }

                then("The app automatically identifies 'Wonderland Launch' as the current spot") {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var foundFeature: com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature? = null

                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val viewModel = androidx.lifecycle.ViewModelProvider(composeTestRule.activity).get(com.madanala.tern.ui.components.MapViewModel::class.java)
                        val store = viewModel.mapStore // Changed from getMapStore() to mapStore
                        
                        if (store != null) {
                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                store.state.collect { state: com.madanala.tern.redux.MapState -> // Explicit type
                                    val spot = state.smartSuggestionState.nearbyPGSpot
                                    if (spot != null) {
                                        foundFeature = spot
                                        latch.countDown()
                                    }
                                }
                            }
                        } else {
                            throw IllegalStateException("MapStore is null")
                        }
                    }

                    val success = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    assert(success) { "Timeout waiting for smart suggestion check" }
                    assert(foundFeature != null) { "Did not find nearby spot" }
                    
                    val name = foundFeature?.feature?.get("properties")?.let { (it as? Map<*, *>)?.get("name") }
                    assert(name == spotName) { "Expected spot name $spotName, but got $name" }

                    // Validate Logcat
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
                }
            }
        }
    }
}
