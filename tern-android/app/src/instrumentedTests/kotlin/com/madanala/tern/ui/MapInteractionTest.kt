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
import androidx.compose.ui.test.performClick
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

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testMapLongPressCreatesRoute() {
        scenario("testMapLongPressCreatesRoute") {
            given("the app is initialized") {
                // Initialize CacheManager
                CacheManager.initialize(composeTestRule.activity.applicationContext)
                
                // Initialize OSMDroid Configuration
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                org.osmdroid.config.Configuration.getInstance().load(context, androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
                org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName
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
                            TernMapScreen()
                        }
                    }
                }
                composeTestRule.onNodeWithTag("map_view").assertExists()
            }

            `when`("I long press on the map") {
                // Long press at a location slightly offset from center to avoid clicking user location
                // Boulder: 40.0150, -105.2705
                // Click at: 40.0200, -105.2600
                MapTestHelper.longPressOnGeoPoint(composeTestRule.activity, 40.0200, -105.2600)
                composeTestRule.waitForIdle()
            }

            then("A new route is created", takeScreenshot = true) {
                // Check for Smart Suggestion dialog (it might appear if cache has data or logic triggers it)
                // We use onAllNodes to check existence without crashing
                if (composeTestRule.onAllNodesWithText("Nearby", substring = true).fetchSemanticsNodes().isNotEmpty()) {
                    // Dialog appeared, click "Use Clicked Location" to proceed with route creation
                    composeTestRule.onNodeWithText("Use Clicked Location").performClick()
                    composeTestRule.waitForIdle()
                }
                
                // Verify "Edit Waypoint" screen appears (auto-selected new waypoint)
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("Edit Waypoint").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithText("Edit Waypoint").assertIsDisplayed()
                
                // Dismiss Edit Waypoint screen
                composeTestRule.onNodeWithText("Done").performClick()
                composeTestRule.waitForIdle()
                
                // Verify "Route 1" is displayed (RouteDetailPanel)
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("Route 1").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithText("Route 1").assertIsDisplayed()
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
                Thread.sleep(2000)
            }

            `when`("I long press ON the existing waypoint") {
                // Boulder: 40.0150, -105.2705
                // Click exactly on it to minimize projection errors
                MapTestHelper.longPressOnGeoPoint(composeTestRule.activity, 40.0150, -105.2705)
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
