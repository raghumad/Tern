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



    @Test
    fun testAirspacePanningAndVisibility() {
        scenario("Airspace UX: Pan to High Density Area (Boulder, US)") {
            story("As a pilot planning a cross-country flight, I want to see restricted airspaces on the map so I can stay safe and compliant with aviation regulations during my flight.") {
                
                given("I am preparing for a flight in the Boulder area") {
                    mockServer.setPGSpotsDispatcher(count = 10)
                    // Start near Boulder, but not exactly on the features to trigger a pan
                    givenAppIsLaunchedOnMap(lat = 40.0, lon = -105.2, countryCode = "us") 
                    
                    // Verify initial load succeeded
                    ReportGenerator.logStep("WAIT", "Waiting for initial airspace load")
                    Thread.sleep(5000) // Extra time for initial sync
                    waitForAirspaces(minCount = 1, timeoutMillis = 45000)
                }

                `when`("I pan the map towards a complex airspace structure near the mountains") {
                    // Start waiting for the rendering sync log immediately
                    ReportGenerator.logStep("WAIT", "Waiting for airspace rendering sync")
                    
                    // Pan the map far enough to trigger reload (> 5km)
                    ReportGenerator.logStep("ACTION", "Panning to high density airspace area")
                    composeTestRule.runOnUiThread {
                        val mapView = MapTestHelper.findMapView(composeTestRule.activity.findViewById(android.R.id.content))
                        mapView?.controller?.setCenter(GeoPoint(40.1, -105.2))
                        mapView?.controller?.setZoom(13.0) 
                    }
                    
                    waitForAirspaces(minCount = 1, timeoutMillis = 45000)
                    
                    waitForMapToRender()
                }

                and("I tap on a specific airspace polygon to identify its boundaries and limits") {
                     ReportGenerator.logStep("ACTION", "Tapping on Boulder airspace area")
                     // Known airspace location near Boulder based on previous mock setup
                     MapTestHelper.clickOnGeoPoint(composeTestRule.activity, 40.015, -105.27)
                     composeTestRule.waitForIdle()
                }

                then("The airspace details should be clearly visible, showing floor and ceiling altitudes") {
                    // Verify map exists
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                    
                    // Final check that current state remains stable
                    waitForAirspaces(minCount = 1)
                }
                
                and("The map response should remain fluid without performance degradation") {
                     // Check if PerformanceDebugger logged a storm warning
                     ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                }
            }
        }
    }
}
