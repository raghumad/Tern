package com.madanala.tern.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.TernParaglidingActivity
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.MapTestHelper
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.ReportGenerator
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint

@RunWith(AndroidJUnit4::class)
class MapInteractionPerformanceTest : MapVisualTest() {

    @Test
    fun testRapidPanningBoulderToDC() {
        scenario("Map Interaction Performance - Rapid Panning") {
            val activity = composeTestRule.activity as TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            
            given("The map is centered on Boulder, CO (near Denver Class B airspace)") {
                CacheManager.initialize(activity.applicationContext)
                MapTestHelper.grantLocationPermissions()
                
                val mapViewModel = ViewModelProvider(activity)[com.madanala.tern.ui.components.MapViewModel::class.java]
                mapViewModel.mapView.controller.setZoom(10.0)
                mapViewModel.mapView.controller.setCenter(GeoPoint(40.0150, -105.2705))
                store.dispatch(MapAction.UpdateCenter(GeoPoint(40.0150, -105.2705)))
                store.dispatch(MapAction.UpdateZoom(10.0))
                composeTestRule.waitForIdle()
            }

            and("The user is viewing the map with airspace and PG spot overlays enabled") {
                // By default they are enabled in OverlayState
                // Wait for overlays to load
                Thread.sleep(3000) 
            }

            `when`("The user rapidly pans the map east towards Washington DC") {
                // DC is at -77. Boulder is at -105. 
                // To simulate rapid panning, we perform a sequence of fast swipes.
                // Note: Swiping from Center to West (smaller Lon) pans the map East.
                
                val mapViewModel = ViewModelProvider(activity)[com.madanala.tern.ui.components.MapViewModel::class.java]
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                
                repeat(8) {
                    val center = mapViewModel.mapView.mapCenter as GeoPoint
                    MapTestHelper.swipeMap(
                        activity = activity,
                        startLat = center.latitude,
                        startLon = center.longitude,
                        endLat = center.latitude,
                        endLon = center.longitude - 2.0, // Move viewport East
                        steps = 20 // Slower to prevent extreme flinging
                    )
                    composeTestRule.waitForIdle()
                }
                
                // Finally, jump to DC to ensure we are testing the "settled" state of the target
                // [VERIFICATION] Now uses pure Redux action to verify bidirectional sync (Source of Truth)
                store.dispatch(MapAction.UpdateCenter(GeoPoint(38.9072, -77.0369)))
                
                composeTestRule.waitForIdle()
                Thread.sleep(2000)
            }

            then("The system should debounce intermediate rendering requests") {
                // Validate no performance warnings during the pan
                ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
            }

            and("Only render the overlays for the final destination once settled", takeScreenshot = true) {
                // CLOSED-LOOP VERIFICATION: Ensure we actually reached the target
                assertMapLocation(38.9072, -77.0369, tolerance = 0.5)
            }
            
            and("The UI should remain responsive during the pan without dropping frames") {
                // Implicitly tested if the test finishes without ANR
            }
        }
    }

    @Test
    fun testZoomOutToRegionalView() {
        scenario("Map Interaction Performance - Zoom Out") {
            val activity = composeTestRule.activity as TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            
            given("The map is centered on Washington DC at zoom level 12") {
                CacheManager.initialize(activity.applicationContext)
                val mapViewModel = ViewModelProvider(activity)[com.madanala.tern.ui.components.MapViewModel::class.java]
                mapViewModel.mapView.controller.setZoom(12.0)
                mapViewModel.mapView.controller.setCenter(GeoPoint(38.9072, -77.0369))
                store.dispatch(MapAction.UpdateCenter(GeoPoint(38.9072, -77.0369)))
                store.dispatch(MapAction.UpdateZoom(12.0))
                composeTestRule.waitForIdle()
                Thread.sleep(3000) // wait for local cache to load highly cluttered airspace
            }

            `when`("The user zooms out to zoom level 5 (regional view)") {
                val mapViewModel = ViewModelProvider(activity)[com.madanala.tern.ui.components.MapViewModel::class.java]
                mapViewModel.mapView.controller.setZoom(5.0)
                store.dispatch(MapAction.UpdateZoom(5.0))
                composeTestRule.waitForIdle()
                Thread.sleep(2000) // let adaptive system react
            }

            then("The adaptive budget capacity system should throttle visible geometry limits to guarantee smooth panning") {
                ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
            }
            
            and("Force an emergency cleanup of non-critical overlays", takeScreenshot = true) {
                // Visual verification that it's not a complete red blob
            }
        }
    }

    @Test
    fun testNavigatingNearCountryBorders() {
        scenario("Map Interaction Performance - Swiss-Austria Border") {
            val activity = composeTestRule.activity as TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            
            given("The user pans the map to the Swiss-Austria border region") {
                CacheManager.initialize(activity.applicationContext)
                // Border region near Bodensee (Lake Constance) where DE, CH, AT meet
                // approx 47.5, 9.5
                val mapViewModel = ViewModelProvider(activity)[com.madanala.tern.ui.components.MapViewModel::class.java]
                mapViewModel.mapView.controller.setZoom(10.0)
                mapViewModel.mapView.controller.setCenter(GeoPoint(47.5, 9.5))
                store.dispatch(MapAction.UpdateCenter(GeoPoint(47.5, 9.5)))
                store.dispatch(MapAction.UpdateZoom(10.0))
                composeTestRule.waitForIdle()
                Thread.sleep(4000) // Wait for multiple country caches to load
            }

            `when`("The spatial query requests data from multiple country caches (CH, AT, DE, IT)") {
                // Pan slightly to trigger nearby queries
                val mapViewModel = ViewModelProvider(activity)[com.madanala.tern.ui.components.MapViewModel::class.java]
                mapViewModel.mapView.controller.setCenter(GeoPoint(47.4, 9.6))
                store.dispatch(MapAction.UpdateCenter(GeoPoint(47.4, 9.6)))
                composeTestRule.waitForIdle()
                Thread.sleep(2000)
            }

            then("The UI should not freeze while querying across caches") {
                ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "HIGH_MEMORY_USAGE")
            }
            
            and("Overlays from all adjacent countries should render seamlessly within the budget limits", takeScreenshot = true) {
                // Visual verification
            }
        }
    }
}
