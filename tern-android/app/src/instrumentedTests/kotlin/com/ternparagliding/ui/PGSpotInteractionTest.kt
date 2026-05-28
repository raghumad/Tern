package com.ternparagliding.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.WeatherActions
import com.ternparagliding.utils.BddTest
import com.ternparagliding.utils.CacheManager
import com.ternparagliding.utils.MapOverlayCacheUtils
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.TestCacheInjector
import com.ternparagliding.utils.WeatherData
import com.ternparagliding.utils.WeatherForecast
import com.ternparagliding.utils.WindData
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint

/**
 * PGSpotInteractionTest - High-Fidelity Pilot Journey Integration Test
 * Validates the transition from Map interaction to Weather Analysis.
 */
@RunWith(AndroidJUnit4::class)
class PGSpotInteractionTest : MapVisualTest() {

    @Test
    fun pilot_scans_mountain_launch_for_safe_flying_conditions() {
        scenario("The Mountain Scan: Assessing Safety via Map Interop") {
            story("As a pilot at the launch site, I want to click a spot to see a full safety breakdown.") {
                given("a pilot located at Lookout Mountain with a PG spot nearby", takeScreenshot = true) {
                    val lat = 39.7429
                    val lon = -105.2393
                    injectMockPGSpot(lat, lon, "Lookout Mtn")
                    
                    // Center map on spot
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = ViewModelProvider(activity)[MapStore::class.java]
                        store.dispatch(MapAction.UpdateCenter(GeoPoint(lat, lon)))
                        store.dispatch(MapAction.UpdateZoom(15.0))
                    }
                    composeTestRule.waitForIdle()
                }

                `when`("I click the 'Lookout Mtn' spot on the map and weather data is requested") {
                    val lat = 39.7429
                    val lon = -105.2393
                    val pgSpotId = "pg_39_7429_-105_2393" // Hilbert ID generation pattern
                    
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = ViewModelProvider(activity)[MapStore::class.java]
                        
                        // 🚀 HIGH-FIDELITY: Dispatch the real fetch action
                        store.dispatch(WeatherActions.FetchWeatherForPGSpot(pgSpotId, lat, lon))
                        
                        // 🎯 TRIGGER DIALOG: Show dialog with null forecast to trigger the 'Loading' state
                        // This mimics what PGSpotOverlayManager.showPGSpotWeatherDetails(feature) does
                        store.dispatch(WeatherActions.ShowWeatherDetails(pgSpotId, "Lookout Mtn", null))
                    }
                    
                    // Wait for the loading indicator to disappear and dialog to show real data
                    // Open-Meteo is usually fast, but 15s timeout is safer for CI
                    composeTestRule.waitUntil(15000) {
                        try {
                            composeTestRule.onNodeWithTag("WeatherDialogTitle").assertIsDisplayed()
                            // Verification: Ensure NOT in loading state anymore
                            composeTestRule.onAllNodesWithTag("WeatherLoadingIndicator").fetchSemanticsNodes().isEmpty() &&
                                composeTestRule.onAllNodesWithTag("WeatherLapseRate").fetchSemanticsNodes().isNotEmpty()
                        } catch (e: Exception) {
                            false
                        }
                    }
                }

                then("the 'Weather - Lookout Mtn' dialog should manifest on the Compose canvas with real-world analytics", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("WeatherDialogTitle").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("WeatherDialogTitle").assertTextContains("Lookout Mtn", substring = true)
                }

                and("the weather analysis (Lapse Rate, Storm Risk) should be calculated from live atmospheric data") {
                    // We can't predict exact values, but we can verify they are valid numbers/labels
                    composeTestRule.onNodeWithTag("WeatherLapseRate")
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assert(hasText("/km", substring = true))
                    
                    // Check that Storm Risk contains a valid safety label
                    composeTestRule.onNodeWithTag("WeatherStormRisk")
                        .assertIsDisplayed()
                        .assert(
                            hasText("Low", substring = true)
                                .or(hasText("Moderate", substring = true))
                                .or(hasText("High", substring = true))
                        )
                }
            }
        }
    }

    private fun injectMockPGSpot(lat: Double, lon: Double, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val featureMap = mapOf(
            "type" to "Feature",
            "properties" to mapOf(
                "name" to name,
                "siteType" to "launch"
            ),
            "geometry" to mapOf(
                "type" to "Point",
                "coordinates" to listOf(lon, lat)
            )
        )
        val centroid = GeoPoint(lat, lon)
        val mockSpot = MapOverlayCacheUtils.OverlayFeature(
            feature = featureMap,
            centroid = centroid,
            hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
            overlayType = "pgspot"
        )
        TestCacheInjector.injectPGSpots(
            context, 
            CacheManager.pgSpotCache, 
            "us", 
            listOf(mockSpot)
        )
    }
}
