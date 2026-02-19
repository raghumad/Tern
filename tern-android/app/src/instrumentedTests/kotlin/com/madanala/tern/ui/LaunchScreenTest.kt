package com.madanala.tern.ui

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.CacheManager
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchScreenTest : MapVisualTest() {

    // composeTestRule is inherited from BaseUITest via BddTest<ComponentActivity>()

    @Test
    fun appLaunchesSuccessfully() {
        scenario("appLaunchesSuccessfully") {
            // Initialize CacheManager
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            com.madanala.tern.utils.CacheManager.initialize(context)

            // Configure caches to use MockServer
            // Clear existing cache to force download
            com.madanala.tern.utils.CacheManager.pgSpotCache.clearCache()
            com.madanala.tern.utils.CacheManager.airspaceCache.clearCache()
            
            // Force CountryUtils to return "gb" (United Kingdom) - Smaller dataset (4.6MB vs 23MB for US)
            // This prevents OOM/Timeouts on Emulator while still using Real URLs
            
            // Disable debounce for testing to prevent race conditions
            val originalDebounce = com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS
            com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = 0L

            try {
                // Launch on London, UK
                givenAppIsLaunchedOnMap(lat = 51.5, lon = -0.1, countryCode = "gb")
                
                // Force map center to London to ensure test stability
                composeTestRule.runOnUiThread {
                    val contentViewGroup = composeTestRule.activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    var mapView: org.osmdroid.views.MapView? = null
                    fun findMapView(view: android.view.View) {
                        if (view is org.osmdroid.views.MapView) {
                            mapView = view
                            return
                        }
                        if (view is android.view.ViewGroup) {
                            for (i in 0 until view.childCount) {
                                findMapView(view.getChildAt(i))
                                if (mapView != null) return
                            }
                        }
                    }
                    findMapView(contentViewGroup)
                    mapView?.controller?.setCenter(org.osmdroid.util.GeoPoint(51.5, -0.1))
                    mapView?.controller?.setZoom(12.0)
                }
                
                this.then("overlays are rendered on the map") {
                    com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Checking for rendered overlays")
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                    
                    // Use robust waiting logic from MapVisualTest
                    waitForMapToRender()
                    
                    // Optional: Double check visibility of specific layers via logs if needed
                    // but waitForMapToRender handles the heavy lifting of waiting for tiles and UI idle.
                }
            } finally {
                // Restore debounce
                com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = originalDebounce
            }
        }
    }
}
