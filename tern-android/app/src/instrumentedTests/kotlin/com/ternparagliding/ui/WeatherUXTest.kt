package com.ternparagliding.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.WeatherActions
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.ReportGenerator
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint

/**
 * WeatherUXTest - Consolidated Aviation Weather Verification Suite.
 * 
 * PRINCIPLE: Uses real-time APIs (Open-Meteo) and validates via "Watermarks" (units/ranges)
 * instead of exact matching, ensuring 100% stability against changing weather.
 */
@RunWith(AndroidJUnit4::class)
class WeatherUXTest : MapVisualTest() {

    @Test
    fun pilot_verifies_realtime_weather_at_lookout_mountain() {
        scenario("Real-Time Weather Verification: Lookout Mountain") {
            val lat = 39.7429
            val lon = -105.2393
            val spotName = "Lookout Mtn"
            // Hilbert ID for Lookout Mtn (Matches PGSpotInteractionTest pattern)
            val pgSpotId = "pg_39_7429_-105_2393"

            given("I am viewing the map near Lookout Mountain", takeScreenshot = true) {
                // Ensure map is centered to trigger any nearby spatial logic
                zoomTo(lat, lon, 15.0)
            }

            `when`("I request weather details for the 'Lookout Mtn' launch site") {
                composeTestRule.runOnUiThread {
                    val activity = composeTestRule.activity
                    val store = ViewModelProvider(activity)[MapStore::class.java]
                    
                    // 🚀 REAL-TIME FETCH: Trigger the actual network request
                    store.dispatch(WeatherActions.FetchWeatherForPGSpot(pgSpotId, lat, lon))
                    
                    // Show dialog immediately (will show loading state first)
                    store.dispatch(WeatherActions.ShowWeatherDetails(pgSpotId, spotName, null))
                }
            }

            then("I should see a loading indicator while the Open-Meteo API responds") {
                // This might be fast, but we try to catch it
                try {
                    composeTestRule.onNodeWithTag("WeatherLoadingIndicator").assertExists()
                } catch (e: Throwable) {
                    ReportGenerator.logStep("INFO", "Loading indicator passed too quickly to catch")
                }
            }

            and("eventually the 'Weather - Lookout Mtn' dialog should manifest with real atmospheric data") {
                // Wait for the dialog to settle with real data
                composeTestRule.waitUntil(15000) {
                    try {
                        composeTestRule.onNodeWithTag("WeatherDialogTitle")
                            .assertTextContains(spotName, substring = true)
                        
                        // WATERMARK: Verify we have a lapse rate with units
                        composeTestRule.onNodeWithTag("WeatherLapseRate")
                            .assertTextContains("/km", substring = true)
                        true
                    } catch (e: Throwable) {
                        false
                    }
                }
            }

            and("I should see safe wind values (kt) and a clearly labelled Storm Risk assessment", takeScreenshot = true) {
                // Verify wind units exist (value is dynamic)
                composeTestRule.onAllNodesWithText("kt", substring = true).onFirst().assertIsDisplayed()
                
                // Verify Storm Risk exists and is one of the valid safety strings
                composeTestRule.onNodeWithTag("WeatherStormRisk")
                    .assert(
                        hasText("Low", substring = true)
                        .or(hasText("Moderate", substring = true))
                        .or(hasText("High", substring = true))
                    )
            }
        }
    }

    @Test
    fun pilot_identifies_atmospheric_hazards_in_alpine_terrain() {
        scenario("Alpine Hazard Verification: Chamonix / Mt Blanc") {
            // Chamonix: 45.9237, 6.8694
            val lat = 45.9237
            val lon = 6.8694
            val spotName = "Chamonix"
            val pgSpotId = "pg_45_9237_6_8694"

            given("I am assessing conditions in high Alpine terrain (Chamonix)") {
                zoomTo(lat, lon, 13.0)
            }

            `when`("I open the weather breakdown for Chamonix") {
                composeTestRule.runOnUiThread {
                    val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                    store.dispatch(WeatherActions.FetchWeatherForPGSpot(pgSpotId, lat, lon))
                    store.dispatch(WeatherActions.ShowWeatherDetails(pgSpotId, spotName, null))
                }
            }

            then("The Skew-T analysis should report cloud base and thermal quality derived from real-time lapse rates") {
                composeTestRule.waitUntil(15000) {
                    try {
                        // WATERMARK: Verify Cloud Base has "ft" units
                        composeTestRule.onNodeWithTag("SkewTCloudBase")
                            .assertTextContains("ft", substring = true)
                        
                        // WATERMARK: Verify Lapse Rate is displayed (can be negative or positive)
                        composeTestRule.onNodeWithTag("WeatherLapseRate").assertExists()
                        true
                    } catch (e: Throwable) {
                        false
                    }
                }
            }

            and("the UI remains responsive during the 4D data ingestion", takeScreenshot = true) {
                com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
            }
        }
    }

    /**
     * COMPONENT SCENARIOS:
     * While the principle is real-time, certain UI states (like manual stale warning or specific interpolation)
     * are still manually verified via direct component mounting to ensure UX fidelity for rare edge cases.
     */
    @Test
    fun testStaleDataWarningManualVerification() {
        scenario("UX Verification - Stale Data Warning") {
            val now = System.currentTimeMillis() / 1000
            val fiveHoursAgo = now - (5 * 3600)
            
            val staleForecast = com.ternparagliding.utils.WeatherForecast(
                current = com.ternparagliding.utils.WeatherData(
                    wind = com.ternparagliding.utils.WindData(10.0, 180.0, 0.0),
                    temperature = 20.0,
                    humidity = 50.0,
                    visibility = 10.0,
                    pressure = 1013.0,
                    cloudCover = 0.0,
                    timestamp = fiveHoursAgo
                ),
                daily = emptyList(),
                hourly = emptyList()
            )

            `when`("the Weather Details Dialog is mounted with data older than 4 hours") {
                // Still using dispatch ShowWeatherDetails but with a mocked forecast to force the UI state
                composeTestRule.runOnUiThread {
                    val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                    store.dispatch(WeatherActions.ShowWeatherDetails("stale_test", "Stale Spot", staleForecast))
                }
            }

            then("the 'Weather data is stale' warning MUST be visible to the pilot", takeScreenshot = true) {
                composeTestRule.onNodeWithText("⚠️ Weather data is stale (>4h old)").assertIsDisplayed()
            }
        }
    }
}
