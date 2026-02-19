package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.madanala.tern.redux.MapStore

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
            val activity = composeTestRule.activity as TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            given("the app is initialized on map") {
                // Initialize CacheManager
                CacheManager.initialize(composeTestRule.activity.applicationContext)
                
                // Permissions & Location handled by MapVisualTest base or Helper
                MapTestHelper.grantLocationPermissions()
                MapTestHelper.injectMockLocation(composeTestRule, 40.0150, -105.2705)
                
                // Add Middleware explicitly to ensure CheckSmartSuggestion is handled
                store.addMiddleware(com.madanala.tern.redux.MapMiddleware(composeTestRule.activity))
            }

            `when`("the app content is set") {
                // MapVisualTest already launches TernParaglidingActivity
                // We just need to ensure we are visible and wait for idle
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                // Set map center and zoom explicitly via the Activity's store
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705)))
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateZoom(15.0))
                composeTestRule.waitForIdle()
            }

            `when`("I long press on the map (simulated via Redux action)") {
                // Simulate long press by dispatching the action directly
                val geoPoint = org.osmdroid.util.GeoPoint(40.0200, -105.2600)
                store.dispatch(com.madanala.tern.redux.MapAction.LongPressMap(geoPoint))
                composeTestRule.waitForIdle()
            }

            then("A new route is created", takeScreenshot = true) {
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
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE UPDATE STORM")
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

    @Test
    fun testLongPressNearWaypointSelectsIt() {
        scenario("testLongPressNearWaypointSelectsIt") {
            val activity = composeTestRule.activity as TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            
            given("I have a route with a waypoint at Boulder") {
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

            and("I have location permissions and mock location") {
                MapTestHelper.grantLocationPermissions()
                MapTestHelper.injectMockLocation(composeTestRule, 40.0150, -105.2705)
            }

            `when`("the app content is set with the pre-populated store") {
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                // Set high zoom level and center map for precision
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705)))
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateZoom(18.0))
                composeTestRule.waitForIdle()
            }

            `when`("I long press ON the existing waypoint (simulated via Redux action)") {
                // Boulder: 40.0150, -105.2705
                val geoPoint = org.osmdroid.util.GeoPoint(40.0150, -105.2705)
                store.dispatch(com.madanala.tern.redux.MapAction.LongPressMap(geoPoint))
                composeTestRule.waitForIdle()
            }

            then("The existing waypoint is selected (Edit Waypoint screen appears)") {
                // Verify "Edit Waypoint" screen appears
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("Edit Waypoint").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithText("Edit Waypoint").assertIsDisplayed()
            }
            
            and("No new waypoint is added") {
                 // Check store state directly
                 assert(store.state.value.routes.first().waypoints.size == 1) { "Expected 1 waypoint, found ${store.state.value.routes.first().waypoints.size}" }
            }

            then("The waypoint is actually selected in the store") {
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
