package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.MapTestHelper
import com.madanala.tern.utils.ReportGenerator
import com.madanala.tern.redux.MapStore
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import android.util.Log

/**
 * DeclutteringUXTest: Verifies the adaptive map decluttering system.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeclutteringUXTest : MapVisualTest() {

    @Test
    fun testAdaptiveDeclutteringDuringWaypointDrag() {
        val lat = 40.015
        val lon = -105.27
        
        // 1. GIVEN the dispatcher is set BEFORE launching (though activity might be started by rule)
        
        scenario("Adaptive Airspace Decluttering during Drag") {
            story("As a pilot, I want the map to declutter when I am interacting with waypoints so I can focus on my route planning.") {
                
                given("The app is launched on a map with nearby airspaces") {
                    givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                    
                    // Verify airspaces are loaded
                    ReportGenerator.logStep("WAIT", "Waiting for airspaces to load...")
                    waitForAirspaces(minCount = 1, timeoutMillis = 60000)
                }

                `when`("I add a waypoint and pick it up to drag") {
                    val activity = composeTestRule.activity
                    val store = ViewModelProvider(activity)[MapStore::class.java]
                    
                    ReportGenerator.logStep("ACTION", "Adding and selecting waypoint")
                    MapTestHelper.longPressOnGeoPoint(activity, lat, lon)
                    
                    composeTestRule.waitUntil(timeoutMillis = 10000) {
                        store.state.value.selectedWaypoint != null
                    }
                    
                    ReportGenerator.logStep("ACTION", "Starting waypoint drag")
                    val downEvent = MapTestHelper.pressAndHoldGeoPoint(activity, lat, lon)
                    
                    // Store the event for later release
                    ReportGenerator.logStep("DEBUG", "Waypoint held")
                    
                    // THEN the system should enter "Focus Mode"
                    ReportGenerator.waitForLogMatching("OverlayManager-AIRSPACE", "Focus mode changed: true") { true }
                }

                then("The non-essential overlays should be dimmed or hidden", takeScreenshot = true) {
                    ReportGenerator.captureScreenshot("decluttering_focus_mode_active")
                }

                `when`("I release the waypoint") {
                    val activity = composeTestRule.activity
                    // We need the downEvent. Unfortunately we can't easily pass it between blocks in BDD style 
                    // without a shared variable. So we'll redo the press/hold/release in a single block if needed
                    // but let's try to just use a local variable in the scenario.
                }
            }
        }
    }
    
    // Since BDD blocks are closures, I'll nest them or use shared vars
    @Test
    fun testAdaptiveDeclutteringFullScenario() {
        val lat = 40.015
        val lon = -105.27

        // Setup shared state for the BDD flow
        var downEvent: android.view.MotionEvent? = null

        scenario("Adaptive Airspace Decluttering during Drag (Full)") {
             given("The app is launched on a map with airspaces") {
                 givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                 waitForAirspaces(minCount = 1, timeoutMillis = 60000)
             }

             `when`("I pick up a waypoint to drag") {
                 val activity = composeTestRule.activity
                 val store = ViewModelProvider(activity)[MapStore::class.java]
                 
                 MapTestHelper.longPressOnGeoPoint(activity, lat, lon)
                 composeTestRule.waitUntil(timeoutMillis = 10000) {
                     store.state.value.selectedWaypoint != null
                 }
                 
                 downEvent = MapTestHelper.pressAndHoldGeoPoint(activity, lat, lon)
                 
                 ReportGenerator.waitForLogMatching("OverlayManager-AIRSPACE", "Focus mode changed: true") { true }
             }

             then("Focus mode is active") {
                 ReportGenerator.captureScreenshot("focus_mode_active")
             }

             `when`("I move and release the waypoint") {
                 val activity = composeTestRule.activity
                 MapTestHelper.moveHold(activity, downEvent!!, lat + 0.005, lon + 0.005)
                 MapTestHelper.releaseHold(activity, downEvent!!)
                 
                 ReportGenerator.waitForLogMatching("OverlayManager-AIRSPACE", "Focus mode changed: false") { true }
             }

             then("Focus mode is deactivated") {
                 ReportGenerator.captureScreenshot("focus_mode_deactivated")
             }
        }
    }
}
