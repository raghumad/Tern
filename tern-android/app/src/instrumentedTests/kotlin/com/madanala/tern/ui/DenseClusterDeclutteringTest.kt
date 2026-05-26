package com.madanala.tern.ui

import androidx.compose.ui.test.*
import com.madanala.tern.model.LocationType
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapAction
import com.madanala.tern.utils.Liar
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.ReportGenerator
import org.junit.Test
import org.osmdroid.util.GeoPoint
import androidx.lifecycle.ViewModelProvider
import com.madanala.tern.redux.MapStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * [RSE] Verification: Dense Cluster Decluttering
 * Verifies that overlapping waypoint labels are suppressed in both Edit and View modes.
 */
@RunWith(AndroidJUnit4::class)
class DenseClusterDeclutteringTest : MapVisualTest() {

    @Liar("Waypoint labels are MapLibre SymbolLayer text (GPU-drawn), not Compose nodes. " +
          "onNodeWithText('WP-BETA').assertDoesNotExist() matches the RouteDetailPanel list, " +
          "which always shows all waypoints. Map-level label decluttering cannot be tested via Compose semantics.")
    @Test
    fun testManualSelectionDecluttering() {
        val baseLat = 40.015
        val baseLon = -105.27
        
        val wp1 = Waypoint(id = "wp1", lat = baseLat, lon = baseLon, type = LocationType.TURNPOINT, label = "WP-ALPHA")
        val wp2 = Waypoint(id = "wp2", lat = baseLat + 0.0001, lon = baseLon + 0.0001, type = LocationType.TURNPOINT, label = "WP-BETA")
        
        val route = Route(id = "edit-route", name = "Edit Route", waypoints = listOf(wp1, wp2))
        
        scenario("Manual Selection Decluttering") {
            story("Selecting a waypoint should force it to TARGET status and demote overlapping neighbors.") {
                given("A route with clustered waypoints") {
                    showRouteOnMap(route)
                    zoomTo(baseLat, baseLon, zoom = 12.0) 
                }
                `when`("The first waypoint is selected") {
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = ViewModelProvider(activity)[MapStore::class.java]
                        store.dispatch(MapAction.SelectWaypoint("edit-route", "wp1"))
                    }
                    composeTestRule.waitForIdle()
                    Thread.sleep(2000)
                }
                then("Only the selected waypoint label remains", takeScreenshot = true) {
                    composeTestRule.onNodeWithText("WP-ALPHA").assertExists()
                    composeTestRule.onNodeWithText("WP-BETA").assertDoesNotExist()
                }
            }
        }
    }

    @Liar("Same issue: waypoint labels are MapLibre SymbolLayer text, not Compose nodes. " +
          "assertDoesNotExist() for 'CLUMP' matches RouteDetailPanel, not the map. " +
          "Leg label suppression ('0.1 km') is also a MapLibre rendering concern.")
    @Test
    fun testZoomToRouteAutomaticDecluttering() {
        val baseLat = 40.015
        val baseLon = -105.27
        
        // WP1 (Start) and WP2 (Clumped)
        val wp1 = Waypoint(id = "wp1", lat = baseLat, lon = baseLon, type = LocationType.TURNPOINT, label = "START")
        val wp2 = Waypoint(id = "wp2", lat = baseLat + 0.0001, lon = baseLon + 0.0001, type = LocationType.TURNPOINT, label = "CLUMP")
        
        // WP3 (Short Leg - ~500m away)
        val wp3 = Waypoint(id = "wp3", lat = baseLat + 0.005, lon = baseLon + 0.005, type = LocationType.TURNPOINT, label = "LEG-END")
        
        val route = Route(id = "chamonix-task", name = "Chamonix Task", waypoints = listOf(wp1, wp2, wp3))
        
        scenario("ZoomToRoute Automatic Decluttering") {
            story("In Task View (ZoomToRoute), the map should remain readable via automatic RSE and leg suppression.") {
                
                given("A task with clustered waypoints and short segments") {
                    showRouteOnMap(route)
                }

                `when`("I zoom to the route") {
                    zoomToRouteEntirely(route)
                    // Add manual zoom to ensure density triggers RSE (ZoomToRoute might be too tight)
                    zoomTo(baseLat, baseLon, zoom = 12.0)
                }

                then("Hierarchy is enforced: Target first, then Context, and segments < 2km are hidden", takeScreenshot = true) {
                    // WP1 is first, so active target by default
                    composeTestRule.onNodeWithText("START").assertExists()
                    
                    // WP2 is clumped under WP1, should be hidden
                    composeTestRule.onNodeWithText("CLUMP").assertDoesNotExist()
                    
                    // Leg labels (0.1 km and 0.5 km) should be suppressed
                    composeTestRule.onNodeWithText("0.1 km", substring = true).assertDoesNotExist()
                    composeTestRule.onNodeWithText("0.5 km", substring = true).assertDoesNotExist()
                    
                    ReportGenerator.captureScreenshot("zoom_to_route_decluttered")
                }
            }
        }
    }
}
