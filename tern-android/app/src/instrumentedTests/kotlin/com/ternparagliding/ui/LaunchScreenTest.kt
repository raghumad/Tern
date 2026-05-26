package com.ternparagliding.ui

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.CacheManager
import com.ternparagliding.utils.ReportGenerator
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchScreenTest : MapVisualTest() {

    // composeTestRule is inherited from BaseUITest via BddTest<ComponentActivity>()

    @Test
    fun appLaunchesSuccessfully() {
        scenario("appLaunchesSuccessfully") {
            story("As a pilot arriving at a remote takeoff location, I expect the app to launch quickly and reliably, immediately showing me the local map and airspaces so I can begin my pre-flight checks without delay.") {
                // Initialize CacheManager
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                com.ternparagliding.utils.CacheManager.initialize(context)

                given("I am at a new flying site (London, UK) and need to see the local terrain") {
                    // Clear existing cache to force download
                    com.ternparagliding.utils.CacheManager.pgSpotCache.clearCache()
                    com.ternparagliding.utils.CacheManager.airspaceCache.clearCache()
                    
                    // Force CountryUtils to return "gb" (United Kingdom) - Smaller dataset (4.6MB vs 23MB for US)
                    // This prevents OOM/Timeouts on Emulator while still using Real URLs
                    
                    // M8: MAP_MOVE_DEBOUNCE_MS removed; MapLibre uses
                    // CameraState snapshotFlow with distinctUntilChanged.

                    // Launch on London, UK
                    givenAppIsLaunchedOnMap(lat = 51.5, lon = -0.1, countryCode = "gb")

                    // M8: MapLibre camera is centered via Redux dispatch
                    // (givenAppIsLaunchedOnMap calls zoomTo internally).
                    // No OSMDroid MapView lookup needed.

                    `when`("the application initializes and starts its background data syncing") {
                        ReportGenerator.logStep("ACTION", "Waiting for map initialization")
                    }

                    then("the map view is present and renders without crashing") {
                        composeTestRule.onNodeWithTag("map_view").assertExists()
                        waitForMapToRender()
                    }
                }
            }
        }
    }
}
