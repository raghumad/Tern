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
            // Use shared BDD step from AppLaunchSteps.kt
            // Defaults to Boulder, CO (40.0150, -105.2705)
            
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
            // Use Dispatcher to handle requests robustly (ignoring weather API calls)
            mockServer.setPGSpotsDispatcher(5)

            // Force CountryUtils to return "us" to ensure PGSpotOverlayManager proceeds
            com.madanala.tern.utils.CountryUtils.setTestCountryCode("us")

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
            
            then("the map screen is displayed and tiles are loaded (waits 5s)") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Checking for map view existence")
                // Verify map view exists
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                // Wait for async data loading and rendering
                Thread.sleep(5000)

                // Verify MockServer received the request (Debugging Network)
        com.madanala.tern.utils.ReportGenerator.assertLogContains(
            "MockServer",
            "DEBUG: MockServer Dispatcher MATCHED PG Spots request"
        )

        // Verify PGSpotCache successfully cached data
        com.madanala.tern.utils.ReportGenerator.assertLogMatchesRegex(
            "PGSpotCache",
            "Successfully cached (\\d+) PG spots"
        ) { match -> match.groupValues[1].toInt() > 0 }

        // Verify PG Spots are rendered (checks intent to render, avoiding animation race conditions)
                com.madanala.tern.utils.ReportGenerator.assertLogMatchesRegex(
                    "OverlayManager-PG_SPOTS", 
                    "PG spots rendered: (\\d+) total"
                ) { matchResult ->
                    val count = matchResult.groupValues[1].toInt()
                    println("DEBUG: PG Spots Rendered: $count")
                    count > 0
                }

        // Verify Airspaces are rendered
                com.madanala.tern.utils.ReportGenerator.assertLogMatchesRegex(
                    "OverlayManager-AIRSPACE", 
                    "Airspace synchronized: (\\d+) total"
                ) { matchResult ->
                    val count = matchResult.groupValues[1].toInt()
                    println("DEBUG: Airspaces Rendered: $count")
                    count > 0
                }
                
                com.madanala.tern.utils.ReportGenerator.assertLogMatchesRegex(
                    "OverlayManager-AIRSPACE", 
                    "Rendered Airspace: Restricted Area 1"
                ) { true }
            }
        }
    }
}
