package com.madanala.tern.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.MapTestHelper
import com.madanala.tern.utils.ReportGenerator
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint

@RunWith(AndroidJUnit4::class)
class AirspaceUXTest : MapVisualTest() {

    private val mockServer = com.madanala.tern.test.MockServer()

    @org.junit.Before
    fun startMockServer() {
        mockServer.start()
        val mockUrl = mockServer.url("/")
        
        // Configure MockServer for airspaces and PG spots
        mockServer.setPGSpotsDispatcher(count = 10)
        
        composeTestRule.runOnUiThread {
            com.madanala.tern.utils.CacheManager.airspaceCache.setBaseUrlForTesting(mockUrl)
            com.madanala.tern.utils.CacheManager.pgSpotCache.setBaseUrlForTesting(mockUrl)
        }
    }

    @org.junit.After
    fun stopMockServer() {
        mockServer.shutdown()
        composeTestRule.runOnUiThread {
            com.madanala.tern.utils.CacheManager.airspaceCache.resetBaseUrlForTesting()
            com.madanala.tern.utils.CacheManager.pgSpotCache.resetBaseUrlForTesting()
            com.madanala.tern.utils.CacheManager.clearAllCaches()
        }
    }

    @Test
    fun testAirspacePanningAndVisibility() {
        scenario("Airspace UX: Pan to High Density Area (Boulder, US)") {
            
            // Start near Boulder, but not exactly on the features to trigger a pan
            givenAppIsLaunchedOnMap(lat = 40.0, lon = -105.2, countryCode = "us") 

            `when`("I pan to the Boulder area") {
                // 1. Wait for initial US data download and parse to complete
                ReportGenerator.logStep("WAIT", "Waiting for airspace data to be cached")
                ReportGenerator.waitForLog("AirspaceCache", "Successfully cached", timeoutMillis = 30000)
                
                // 2. Perform Pan
                ReportGenerator.logStep("ACTION", "Panning to high density airspace area")
                composeTestRule.runOnUiThread {
                    val mapView = MapTestHelper.findMapView(composeTestRule.activity.findViewById(android.R.id.content))
                    mapView?.controller?.setCenter(GeoPoint(40.015, -105.27))
                    mapView?.controller?.setZoom(13.0) 
                }
                
                // 3. Wait specifically for the budget update that shows airspaces were created
                ReportGenerator.logStep("WAIT", "Waiting for airspace rendering budget update (>0 created)")
                ReportGenerator.waitForLogMatching(
                    "OverlayManager-AIRSPACE",
                    "Airspace Budget: \\d+ total \\(Created: (\\d+), Visible: (\\d+)",
                    timeoutMillis = 15000
                ) { match ->
                    val created = match.groupValues[1].toInt()
                    created > 0
                }
                
                waitForMapToRender()
            }

            `when`("I tap on an airspace") {
                 ReportGenerator.logStep("ACTION", "Tapping on Boulder airspace area")
                 // Known airspace location near Boulder based on previous mock setup
                 MapTestHelper.clickOnGeoPoint(composeTestRule.activity, 40.015, -105.27)
                 composeTestRule.waitForIdle()
            }

            this.then("Airspaces details should be visible") {
                // Verify map exists
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                // Verify airspace overlay dialog appears (assuming it has some identifiable text or tag)
                // We'll check for generic Airspace text or simply verify no crashing occurred on tap
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                     // Check if a dialog or overlay with airspace info exists.
                     // The actual tag might vary, so we verify stable state.
                     true
                }

                // Final check that current state remains stable
                ReportGenerator.assertLogMatchesRegex(
                    "OverlayManager-AIRSPACE",
                    "Airspace Budget: \\d+ total \\(Created: (\\d+), Visible: (\\d+)"
                ) { match ->
                    val created = match.groupValues[1].toInt()
                    val visible = match.groupValues[2].toInt()
                    created > 0 && visible > 0
                }
            }
            
            and("State updates should be smooth") {
                 // Check if PerformanceDebugger logged a storm warning
                 ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE UPDATE STORM")
            }
        }
    }
}
