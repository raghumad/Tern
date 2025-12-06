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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.screens.TernMapScreen
import com.madanala.tern.ui.theme.TernTheme
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.CacheManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchScreenTest : BddTest() {

    // composeTestRule is inherited from BaseUITest via BddTest<ComponentActivity>()

    @Test
    fun appLaunchesSuccessfully() {
        scenario("appLaunchesSuccessfully") {
            // Initialize CacheManager
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            com.madanala.tern.utils.CacheManager.initialize(context)

            // Configure caches to use MockServer
            val mockBaseUrl = mockServer.url("").toString().removeSuffix("/")
            com.madanala.tern.utils.CacheManager.pgSpotCache.setBaseUrlForTesting(mockBaseUrl)
            com.madanala.tern.utils.CacheManager.airspaceCache.setBaseUrlForTesting(mockBaseUrl)

            // Clear existing cache to force download
            com.madanala.tern.utils.CacheManager.pgSpotCache.clearCache()
            com.madanala.tern.utils.CacheManager.airspaceCache.clearCache()
            
            val mockUrl = mockServer.url("")
            // Remove trailing slash if present to match expected base URL format
            val baseUrl = if (mockUrl.endsWith("/")) mockUrl.dropLast(1) else mockUrl
            com.madanala.tern.utils.CacheManager.pgSpotCache.setBaseUrlForTesting(baseUrl)
            // Use Dispatcher to handle requests robustly
            mockServer.setPGSpotsDispatcher(5)

            // Force CountryUtils to return "us"
            com.madanala.tern.utils.CountryUtils.setTestCountryCode("us")

            // Disable debounce for testing to prevent race conditions
            val originalDebounce = com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS
            com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = 0L

            try {
                givenAppIsLaunchedOnMap()
                
                // Force map center to Boulder to ensure test stability
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
                    mapView?.controller?.setCenter(org.osmdroid.util.GeoPoint(40.0150, -105.2705))
                    mapView?.controller?.setZoom(12.0)
                }
                
                then("overlays are rendered on the map") {
                    com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Checking for rendered overlays")
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                    
                    // Poll for overlays instead of waiting blindly
                    val timeout = 5000L
                    val startTime = System.currentTimeMillis()
                    var markersCount = 0
                    var polygonsCount = 0
                    
                    while (System.currentTimeMillis() - startTime < timeout) {
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
                            
                            mapView?.let { map ->
                                fun countRecursive(overlays: List<org.osmdroid.views.overlay.Overlay>): Pair<Int, Int> {
                                    var m = 0
                                    var p = 0
                                    for (overlay in overlays) {
                                        if (overlay is org.osmdroid.views.overlay.Marker) m++
                                        else if (overlay is org.osmdroid.views.overlay.Polygon) p++
                                        else if (overlay is org.osmdroid.views.overlay.FolderOverlay) {
                                            val (subM, subP) = countRecursive(overlay.items)
                                            m += subM
                                            p += subP
                                        }
                                    }
                                    return Pair(m, p)
                                }

                                val (m, p) = countRecursive(map.overlays)
                                markersCount = m
                                polygonsCount = p

                                android.util.Log.e("LaunchScreenTest", "DEBUG: Map Overlays (Recursive): Markers=$markersCount, Polygons=$polygonsCount")
                            }
                        }
                        
                        if (markersCount > 0 || polygonsCount > 0) {
                            break
                        }
                        Thread.sleep(500)
                    }

                    if (markersCount == 0 && polygonsCount == 0) {
                        throw AssertionError("No overlays rendered after $timeout ms. Markers: $markersCount, Polygons: $polygonsCount")
                    }
                    
                }
            } finally {
                // Restore debounce
                com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = originalDebounce
            }
        }
    }
}
