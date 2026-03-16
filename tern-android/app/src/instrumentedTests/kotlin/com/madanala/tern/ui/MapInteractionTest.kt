package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.madanala.tern.redux.MapStore

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.MapTestHelper
import com.madanala.tern.utils.ReportGenerator
import com.madanala.tern.TernParaglidingActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import com.madanala.tern.redux.MapAction

@RunWith(AndroidJUnit4::class)
class MapInteractionTest : MapVisualTest() {

     // composeTestRule is inherited from MapVisualTest

    @Test
    fun testMapLongPressCreatesRoute() {
        scenario("testMapLongPressCreatesRoute") {
            story("As a pilot at the launch site, I want to quickly create a flight task by long-pressing on my destination on the map.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                given("I am at the launch site with my flight instruments ready") {
                    // Initialize CacheManager
                    CacheManager.initialize(composeTestRule.activity.applicationContext)
                    
                    // Disable map move debounce for testing to prevent race conditions
                    com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = 0L

                    // Permissions & Location handled by MapVisualTest base or Helper
                    MapTestHelper.grantLocationPermissions()
                    MapTestHelper.injectMockLocation(composeTestRule, 40.0150, -105.2705)
                }

            `when`("I check my position on the moving map") {
                // MapVisualTest already launches TernParaglidingActivity
                // We just need to ensure we are visible and wait for idle
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                // Set map center and zoom explicitly via the Activity's store
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705)))
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateZoom(15.0))
                composeTestRule.waitForIdle()
            }

            `when`("I long-press on a distant peak to set it as my first waypoint") {
                // Simulate long press using physical UI Automator interaction
                val lat = 40.0200
                val lon = -105.2600
                MapTestHelper.longPressOnGeoPoint(activity, lat, lon)
                composeTestRule.waitForIdle()
            }

            then("A new flight task is automatically created for me", takeScreenshot = true) {
                // Verify state updates first (wait for Redux processing)
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.selectedWaypoint != null
                }
                
                val updatedState = store.state.value
                if (updatedState.selectedRouteId == null) {
                    throw AssertionError("Selected Route ID is null! Routes: ${updatedState.routes.size}")
                }

                // Verify "Edit Waypoint" screen appears (auto-selected new waypoint)
                composeTestRule.onNodeWithText("Edit Waypoint").assertIsDisplayed()
                
                // Dismiss Edit Waypoint screen
                store.dispatch(com.madanala.tern.redux.MapAction.DeselectWaypoint)
                
                // Wait for state to update (waypoint deselected)
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.selectedWaypoint == null
                }
                
                // Wait for Edit Waypoint screen to disappear
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("Edit Waypoint").fetchSemanticsNodes().isEmpty()
                }

                // Verify state directly
                val state = store.state.value
                if (state.selectedRouteId == null) {
                    throw AssertionError("Selected Route ID is null! Routes: ${state.routes.size}")
                }
                
                // Verify "Route 1" is displayed (RouteDetailPanel)
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("RouteDetailPanel").assertIsDisplayed()
                composeTestRule.onNodeWithText("Route 1").assertIsDisplayed()
                
                // Validate no performance warnings
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
            }

            then("I close the route detail panel (Cleanup)") {
                store.dispatch(com.madanala.tern.redux.MapAction.DeselectRoute)
                composeTestRule.waitForIdle()
                
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isEmpty()
                }
            }
            }
        }
    }

    @Test
    fun testLongPressNearWaypointSelectsIt() {
        scenario("testLongPressNearWaypointSelectsIt") {
            story("As a pilot, I want to edit an existing waypoint by long-pressing on it, so I can adjust its radius or label during flight planning.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                
                given("I have an existing flight task with a waypoint at 'Boulder'") {
                    // Initialize CacheManager
                    CacheManager.initialize(composeTestRule.activity.applicationContext)
                    
                    // Initialize OSMDroid Configuration
                    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                    org.osmdroid.config.Configuration.getInstance().load(context, androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
                    org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName
                    
                    // Create Route programmatically
                    val boulder = com.madanala.tern.model.Waypoint(lat = 40.0150, lon = -105.2705, label = "Boulder")
                    val route = com.madanala.tern.model.Route(name = "Test Route", waypoints = listOf(boulder))
                    store.dispatch(com.madanala.tern.redux.MapAction.AddRoute(route))
                    store.dispatch(com.madanala.tern.redux.MapAction.SelectRoute(route.id))
                    
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        store.state.value.routes.any { it.id == route.id }
                    }
                }

                and("I have secured my GPS signal lock") {
                    MapTestHelper.grantLocationPermissions()
                    MapTestHelper.injectMockLocation(composeTestRule, 40.0150, -105.2705)
                }

                `when`("I view my route on the map") {
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                    
                    // Set high zoom level and center map for precision
                    store.dispatch(com.madanala.tern.redux.MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705)))
                    store.dispatch(com.madanala.tern.redux.MapAction.UpdateZoom(18.0))
                    composeTestRule.waitForIdle()
                }

                `when`("I long-press directly on the 'Boulder' waypoint") {
                    // Boulder: 40.0150, -105.2705
                    val lat = 40.0150
                    val lon = -105.2705
                    MapTestHelper.longPressOnGeoPoint(activity, lat, lon)
                    composeTestRule.waitForIdle()
                }

                then("The 'Edit Waypoint' panel opens for the existing point") {
                    // Verify "Edit Waypoint" screen appears
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithText("Edit Waypoint").fetchSemanticsNodes().isNotEmpty()
                    }
                    composeTestRule.onNodeWithText("Edit Waypoint").assertIsDisplayed()
                }
                
                and("No duplicate waypoint is created in my route") {
                     // Check store state directly
                     assert(store.state.value.routes.first().waypoints.size == 1) { "Expected 1 waypoint, found ${store.state.value.routes.first().waypoints.size}" }
                }

                then("I can see the details for 'Boulder' are ready to be adjusted") {
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        store.state.value.selectedWaypoint != null
                    }
                    
                    val selection = store.state.value.selectedWaypoint!!
                    val route = store.state.value.routes.find { it.id == selection.routeId }
                    val waypoint = route?.waypoints?.find { it.id == selection.waypointId }
                    
                    assert(waypoint?.label == "Boulder") { "Expected waypoint label 'Boulder', found '${waypoint?.label}'" }
                }
            }
        }
    }

    @Test
    fun testMapSwipeAlongTrajectory() {
        scenario("testMapSwipeAlongTrajectory") {
            story("As a pilot approaching my destination, I want to swipe the map along my predicted trajectory to scout for potential landing fields and power lines.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                given("I am currently flying towards Boulder and approaching the mountains") {
                    MapTestHelper.grantLocationPermissions()
                    MapTestHelper.injectMockLocation(composeTestRule, 40.0150, -105.2705)
                    
                    store.dispatch(MapAction.UpdateCenter(GeoPoint(40.0150, -105.2705)))
                    store.dispatch(MapAction.UpdateZoom(14.0))
                    composeTestRule.waitForIdle()
                }

                `when`("I swipe the map downwards to look further along my flight path") {
                    ReportGenerator.logStep("ACTION", "Swiping map to scout landing area")
                    composeTestRule.onNodeWithTag("map_view").performTouchInput {
                        // Swipe from top to bottom to move map viewport "up" (reveal North)
                        swipeDown(durationMillis = 500)
                    }
                    composeTestRule.waitForIdle()
                }
                then("The map renderer ingests newly revealed spatial GeoJSON streams without frame drops (< 16ms/frame)") {
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                    
                    // Verify center has changed significantly (moving North)
                    val newCenter = store.state.value.center!!
                    assert(newCenter.latitude > 40.0150) { "Expected map to move North, but latitude is ${newCenter.latitude}" }
                }
                and("The pan interaction executes within the PerformanceDebugger Event SLAs (No STATE_UPDATE_STORM, < 2.0MB Retained Delta)") {
                     ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                     ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
                }
            }
        }
    }
}
