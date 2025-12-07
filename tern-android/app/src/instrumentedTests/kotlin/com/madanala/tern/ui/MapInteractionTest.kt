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
            given("the app is initialized on map") {
                // Initialize CacheManager
                CacheManager.initialize(composeTestRule.activity.applicationContext)
                
                // Initialize OSMDroid Configuration
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                org.osmdroid.config.Configuration.getInstance().load(context, androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
                org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName
                
                // Permissions & Location
                MapTestHelper.grantLocationPermissions()
                MapTestHelper.injectMockLocation(composeTestRule, 40.0150, -105.2705)

                // Add Middleware explicitly to ensure CheckSmartSuggestion is handled
                store.addMiddleware(com.madanala.tern.redux.MapMiddleware(context))
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
                try {
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {

                    println("DEBUG_STATE: Routes=${store.state.value.routes.size}, SelectedRoute=${store.state.value.selectedRouteId}, SelectedWaypoint=${store.state.value.selectedWaypoint}")
                    throw e
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

            and("I have location permissions and mock location") {
                MapTestHelper.grantLocationPermissions()
                MapTestHelper.injectMockLocation(composeTestRule, 40.0150, -105.2705)
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
    
    @Test
    fun testTapOnWaypointSelectsIt() {
        val store = com.madanala.tern.redux.MapStore()
        
        scenario("testTapOnWaypointSelectsIt") {
            given("The app is initialized") {
                CacheManager.initialize(composeTestRule.activity.applicationContext)
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                org.osmdroid.config.Configuration.getInstance().load(context, androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
                
                // Disable animations globally for this test scenario to ensure synchronous updates
                com.madanala.tern.ui.overlays.OverlayCoordinator.ANIMATIONS_ENABLED = false
            }
            
            and("I have location permissions and mock location") {
                MapTestHelper.grantLocationPermissions()
                MapTestHelper.injectMockLocation(composeTestRule, 40.0150, -105.2705)
            }

            `when`("the app content is set") {
                composeTestRule.setContent {
                    TernTheme {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            TernMapScreen(store = store)
                        }
                    }
                }
                
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705)))
                store.dispatch(com.madanala.tern.redux.MapAction.UpdateZoom(18.0))
                composeTestRule.waitForIdle()

                // Crucial: Manually sync MapView to match Redux state because ReduxMapBridge doesn't sync State -> View for movement (only View -> State)
                // This ensures RouteOverlayManager calculates the correct DistanceZone (CORE/NEAR) instead of EXTREME (which has 0 budget)
                composeTestRule.runOnUiThread {
                    val activity = composeTestRule.activity
                    val mapView = MapTestHelper.findMapView(activity.window.decorView)
                    mapView?.controller?.setCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705))
                    mapView?.controller?.setZoom(18.0)
                }
                
                Thread.sleep(2000) // Allow OSMDroid to settle/animate

                // Add route AFTER map is centered to avoid "too far from center" filtering
                val boulder = com.madanala.tern.model.Waypoint(lat = 40.0150, lon = -105.2705, label = "Boulder")
                val route = com.madanala.tern.model.Route(name = "Test Route", waypoints = listOf(boulder))
                store.dispatch(com.madanala.tern.redux.MapAction.AddRoute(route))
                store.dispatch(com.madanala.tern.redux.MapAction.SelectRoute(route.id))
                composeTestRule.waitForIdle()
            }
            
            then("The RouteOverlay is added to the map") {
                val originalDebounce = com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS
                com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = 0L
                com.madanala.tern.ui.overlays.OverlayCoordinator.ANIMATIONS_ENABLED = false

                try {
                    // Wait for Map move to actually happen
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                         val activity = composeTestRule.activity
                         val mapView = MapTestHelper.findMapView(activity.window.decorView)
                         mapView?.zoomLevelDouble == 18.0
                    }

                    try {
                        composeTestRule.waitUntil(timeoutMillis = 10000) {
                            val activity = composeTestRule.activity
                            val mapView = MapTestHelper.findMapView(activity.window.decorView)
                            
                            // Recursive check to find RouteOverlay inside FolderOverlays (Z-Index layers)
                            fun hasRouteOverlay(overlays: List<org.osmdroid.views.overlay.Overlay>): Boolean {
                                return overlays.any { overlay ->
                                    if (overlay is org.osmdroid.views.overlay.FolderOverlay) {
                                        hasRouteOverlay(overlay.items)
                                    } else {
                                        overlay.javaClass.simpleName.contains("RouteOverlay")
                                    }
                                }
                            }
                            
                            // Force invalidate
                            mapView?.postInvalidate()

                            mapView?.overlays?.let { hasRouteOverlay(it) } == true
                        }
                    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                        val activity = composeTestRule.activity
                        val mapView = MapTestHelper.findMapView(activity.window.decorView)
                        val sb = StringBuilder()
                        sb.append("Timed out waiting for RouteOverlay. Map: Center=${mapView?.mapCenter}, Zoom=${mapView?.zoomLevelDouble}, Hash=${System.identityHashCode(mapView)}, ListHash=${System.identityHashCode(mapView?.overlays)}\n")
                        System.err.println("TEST_DEBUG: MapView Hash=${System.identityHashCode(mapView)}")
                        sb.append("Store Routes: ${store.state.value.routes.size}\n")
                        
                        fun dump(overlays: List<org.osmdroid.views.overlay.Overlay>, depth: Int) {
                             val indent = "  ".repeat(depth)
                             overlays.forEach { overlay ->
                                 if (overlay is org.osmdroid.views.overlay.FolderOverlay) {
                                     sb.append("$indent[F] ${overlay.name ?: "Unnamed"} (${overlay.items.size})\n")
                                     android.util.Log.e("MapInteractionTest", "$indent[F] ${overlay.name ?: "Unnamed"} (${overlay.items.size})")
                                     dump(overlay.items, depth + 1)
                                 } else {
                                     sb.append("$indent[O] ${overlay.javaClass.simpleName} (${overlay.javaClass.name})\n")
                                     android.util.Log.e("MapInteractionTest", "$indent[O] ${overlay.javaClass.simpleName} (${overlay.javaClass.name})")
                                 }
                             }
                        }
                        mapView?.overlays?.let { dump(it, 0) }
                        throw RuntimeException(sb.toString(), e)
                    }
                } finally {
                    com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = originalDebounce
                    com.madanala.tern.ui.overlays.OverlayCoordinator.ANIMATIONS_ENABLED = true
                }
            }
            
            `when`("I tap on the waypoint (simulated via Redux action)") {
                // Simulate tap by dispatching the action directly
                // Ideally we would use MapTestHelper.clickOnGeoPoint, but for unit/integration tests,
                // dispatching the action is more reliable and tests the logic we care about (selection).
                // However, to test the *interaction*, we should ideally simulate the touch.
                // For now, let's verify the Redux logic for "TapMap" handling if it exists,
                // or ensure the overlay handles single tap.
                
                // Assuming RouteOverlayManager handles SingleTapConfirmed and dispatches SelectWaypoint
                // We can simulate this by dispatching a "MapTap" action if we have one, 
                // Wait for route to be processed
                composeTestRule.waitForIdle()
                
                // Let's try the screen coordinate click using MapTestHelper
                // We need the activity to find the view
                val activity = composeTestRule.activity
                MapTestHelper.clickOnGeoPoint(activity, 40.0150, -105.2705)
                // Wait for route to be processed
                composeTestRule.waitForIdle()
            }
            
            then("The waypoint is selected") {
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
