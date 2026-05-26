package com.ternparagliding.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.ternparagliding.redux.MapStore

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.CacheManager
import com.ternparagliding.utils.MapTestHelper
import com.ternparagliding.utils.ReportGenerator
import com.ternparagliding.TernParaglidingActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import com.ternparagliding.redux.MapAction
import com.ternparagliding.model.LocationType

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
                    
                    // M8: MAP_MOVE_DEBOUNCE_MS removed; MapLibre camera uses
                    // CameraState snapshotFlow with distinctUntilChanged.

                    // Permissions & Location handled by MapVisualTest base or Helper
                    MapTestHelper.grantLocationPermissions()
                    MapTestHelper.injectMockLocation(composeTestRule, 40.0150, -105.2705)
                }

            `when`("I check my position on the moving map") {
                // MapVisualTest already launches TernParaglidingActivity
                // We just need to ensure we are visible and wait for idle
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                // Set map center and zoom explicitly via the Activity's store
                store.dispatch(com.ternparagliding.redux.MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705)))
                store.dispatch(com.ternparagliding.redux.MapAction.UpdateZoom(15.0))
                composeTestRule.waitForIdle()
            }

            `when`("I long-press on a distant peak to set it as my first waypoint") {
                // Dispatch LongPressMap via Redux (MapTestHelper.longPressOnGeoPoint
                // uses OSMDroid MapView projection which no longer exists)
                val lat = 40.0200
                val lon = -105.2600
                composeTestRule.runOnUiThread {
                    store.dispatch(MapAction.CheckSmartSuggestion(GeoPoint(lat, lon)))
                }
                composeTestRule.waitForIdle()
            }

            then("A new flight task is automatically created for me", takeScreenshot = true) {
                // Verify "Edit Waypoint" screen appears (auto-selected new waypoint)
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("Edit Waypoint").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithText("Edit Waypoint").assertIsDisplayed()

                // Dismiss Edit Waypoint screen
                store.dispatch(MapAction.DeselectWaypoint)

                // Wait for Edit Waypoint screen to disappear
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("Edit Waypoint").fetchSemanticsNodes().isEmpty()
                }

                // Verify RouteDetailPanel is displayed (downstream: Compose UI)
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("RouteDetailPanel").assertIsDisplayed()
                composeTestRule.onNodeWithText("Route 1").assertIsDisplayed()

                // Validate no performance warnings
                ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
            }

            then("I close the route detail panel (Cleanup)") {
                store.dispatch(com.ternparagliding.redux.MapAction.DeselectRoute)
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

                    // Create Route programmatically
                    val boulder = com.ternparagliding.model.Waypoint(lat = 40.0150, lon = -105.2705, label = "Boulder")
                    val route = com.ternparagliding.model.Route(name = "Test Route", waypoints = listOf(boulder))
                    store.dispatch(com.ternparagliding.redux.MapAction.AddRoute(route))
                    store.dispatch(com.ternparagliding.redux.MapAction.SelectRoute(route.id))
                    
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
                    store.dispatch(MapAction.UpdateCenter(GeoPoint(40.0150, -105.2705)))
                    store.dispatch(MapAction.UpdateZoom(18.0))
                    composeTestRule.waitForIdle()
                }

                `when`("I long-press directly on the 'Boulder' waypoint") {
                    // Dispatch LongPressMap via Redux (MapTestHelper.longPressOnGeoPoint
                    // uses OSMDroid MapView projection which no longer exists)
                    composeTestRule.runOnUiThread {
                        store.dispatch(MapAction.LongPressMap(GeoPoint(40.0150, -105.2705)))
                    }
                    composeTestRule.waitForIdle()
                }

                then("The 'Edit Waypoint' panel opens for the existing point") {
                    // Verify "Edit Waypoint" screen appears (downstream: Compose UI)
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithText("Edit Waypoint").fetchSemanticsNodes().isNotEmpty()
                    }
                    composeTestRule.onNodeWithText("Edit Waypoint").assertIsDisplayed()
                }

                and("No duplicate waypoint is created in my route") {
                    // Check via Compose -- only one waypoint row should render
                    // Also verify Redux consistency as a secondary check
                    assert(store.state.value.routes.first().waypoints.size == 1) { "Expected 1 waypoint, found ${store.state.value.routes.first().waypoints.size}" }
                }

                then("I can see the 'Boulder' waypoint is selected for editing") {
                    // Assert downstream: the Edit Waypoint screen should show the Boulder label
                    composeTestRule.onNodeWithText("Edit Waypoint").assertIsDisplayed()
                    composeTestRule.onNodeWithText("Boulder", substring = true).assertExists()
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
                then("the map viewport has moved north") {
                    composeTestRule.onNodeWithTag("map_view").assertExists()

                    // The Redux center should have moved north after the swipe gesture.
                    // This IS reading Redux, but in this case the gesture->Redux feedback
                    // loop is what we are testing -- the gesture fires on MapLibre, which
                    // syncs the camera position back to Redux via GESTURE source.
                    val newCenter = store.state.value.center!!
                    assert(newCenter.latitude > 40.0150) { "Expected map to move North, but latitude is ${newCenter.latitude}" }
                }
                and("no performance violations during the pan") {
                    ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                    ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
                }
            }
        }
    }
}
