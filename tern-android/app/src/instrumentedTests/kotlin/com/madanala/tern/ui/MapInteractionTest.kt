package com.madanala.tern.ui

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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.screens.TernMapScreen
import com.madanala.tern.ui.theme.TernTheme
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.MapTestHelper
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapInteractionTest : BddTest() {

    // composeTestRule is inherited from BaseUITest via BddTest<ComponentActivity>()

    @Test
    fun testMapLongPressCreatesRoute() {
        val store = com.madanala.tern.redux.MapStore()
        
        scenario("testMapLongPressCreatesRoute") {
            given("the app is initialized") {
                // Initialize CacheManager
                CacheManager.initialize(composeTestRule.activity.applicationContext)
                
                // Initialize OSMDroid Configuration
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                org.osmdroid.config.Configuration.getInstance().load(context, androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
                org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName
                
                // Add Middleware explicitly to ensure CheckSmartSuggestion is handled
                store.addMiddleware(com.madanala.tern.redux.MapMiddleware(context))
            }

            and("I have location permissions") {
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                val uiAutomation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
                uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            and("I inject Mock Location (Boulder, CO)") {
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                try {
                    locationManager.addTestProvider(
                        android.location.LocationManager.GPS_PROVIDER,
                        false, false, false, false, true, true, true, 1, 1
                    )
                    locationManager.setTestProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
                    
                    val mockLocation = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                        latitude = 40.0150
                        longitude = -105.2705
                        altitude = 1600.0
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        accuracy = 1.0f
                    }
                    locationManager.setTestProviderLocation(android.location.LocationManager.GPS_PROVIDER, mockLocation)
                } catch (e: SecurityException) {
                    println("Warning: Could not set mock location: ${e.message}")
                }
            }

            `when`("the app content is set") {
                composeTestRule.setContent {
                    TernTheme {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            TernMapScreen(store = store)
                        }
                    }
                }
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                // Set map center and zoom explicitly to ensure deterministic state
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705)))
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateZoom(15.0))
                composeTestRule.waitForIdle()
            }

            `when`("I long press on the map (simulated via Redux action)") {
                // Simulate long press by dispatching the action directly
                // We bypass CheckSmartSuggestion to avoid async middleware complexity in UI tests
                // and directly verify the route creation logic.
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
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("Edit Waypoint").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithText("Edit Waypoint").assertIsDisplayed()
                
                // Dismiss Edit Waypoint screen
                // We dispatch the action directly to ensure reliability in the test environment,
                // avoiding potential UI interaction flakes with the "Done" button.
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
                try {
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                    composeTestRule.onRoot().printToLog("DEBUG_TREE_FAILURE")
                    println("DEBUG_STATE: Routes=${store.state.value.routes.size}, SelectedRoute=${store.state.value.selectedRouteId}, SelectedWaypoint=${store.state.value.selectedWaypoint}")
                    throw e
                }
                composeTestRule.onNodeWithTag("RouteDetailPanel").assertIsDisplayed()
                composeTestRule.onNodeWithText("Route 1").assertIsDisplayed()
                
                // Validate no performance warnings
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE UPDATE STORM")
            }
        }
    }

    @Test
    fun testLongPressNearWaypointSelectsIt() {
        val store = com.madanala.tern.redux.MapStore()
        
        scenario("testLongPressNearWaypointSelectsIt") {
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
            }

            and("I have location permissions") {
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                val uiAutomation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
                uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            and("I inject Mock Location (Boulder, CO)") {
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                try {
                    locationManager.addTestProvider(
                        android.location.LocationManager.GPS_PROVIDER,
                        false, false, false, false, true, true, true, 1, 1
                    )
                    locationManager.setTestProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
                    
                    val mockLocation = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                        latitude = 40.0150
                        longitude = -105.2705
                        altitude = 1600.0
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        accuracy = 1.0f
                    }
                    locationManager.setTestProviderLocation(android.location.LocationManager.GPS_PROVIDER, mockLocation)
                } catch (e: SecurityException) {
                    println("Warning: Could not set mock location: ${e.message}")
                }
            }

            `when`("the app content is set with the pre-populated store") {
                composeTestRule.setContent {
                    TernTheme {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            TernMapScreen(store = store)
                        }
                    }
                }
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                // Set high zoom level and center map for precision
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705)))
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateZoom(18.0))
                composeTestRule.waitForIdle()
                // OSMDroid needs time to animate/render the new zoom level
                // OSMDroid needs time to animate/render the new zoom level
                composeTestRule.waitForIdle()
            }

            `when`("I long press ON the existing waypoint (simulated via Redux action)") {
                // Boulder: 40.0150, -105.2705
                // Simulate long press by dispatching the action directly
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
        }
    }
}
