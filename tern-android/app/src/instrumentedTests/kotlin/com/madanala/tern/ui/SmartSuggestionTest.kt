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

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testSmartSuggestionLogic() {
        val spotLat = 40.0200
        val spotLon = -105.2705
        val spotName = "Wonderland Launch"

        scenario("Smart Suggestion Logic") {
            given("the app is initialized with a mock PG spot") {
                // Initialize CacheManager
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                CacheManager.initialize(context)

                // Create a mock OverlayFeature
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
                    hilbertIndex = 0L,
                    overlayType = "pgspot"
                )

                // Inject into cache (LOWERCASE "us" to match CountryUtils override)
                CacheManager.pgSpotCache.setTestSpots("us", listOf(mockSpot))
                
                // Force CountryUtils to return "us" (mocking Geocoder)
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
                    
                    // Launch a collector on the ViewModel's scope or a test scope
                    // Since we are in runOnMainSync, we are on Main thread.
                    // We can use a simple observer if it was LiveData, but it is StateFlow.
                    
                    // We can launch a coroutine in the test scope if we had one.
                    // Let's use GlobalScope for simplicity in this specific test context, ensuring we cancel it.
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        viewModel.nearbyPGSpot.collect { spot ->
                            if (spot != null) {
                                foundFeature = spot
                                latch.countDown()
                            }
                        }
                    }
                    
                    viewModel.checkForSmartSuggestion(
                        context = context,
                        geoPoint = GeoPoint(spotLat, spotLon)
                    )
                }

                val success = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                assert(success) { "Timeout waiting for smart suggestion check" }
                assert(foundFeature != null) { "Did not find nearby spot" }
                
                val name = foundFeature?.feature?.get("properties")?.let { (it as? Map<*, *>)?.get("name") }
                assert(name == spotName) { "Expected spot name $spotName, but got $name" }
            }
        }
    }
}
