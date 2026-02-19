package com.madanala.tern.ui

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.CacheManager
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest : MapVisualTest() {

    // composeTestRule is inherited from BaseUITest via BddTest<ComponentActivity>()

    @Test
    fun verifyNavigationToMap() {
        scenario("verifyNavigationToMap") {
            // Force clear ViewModelStore to ensure fresh MapViewModel
            composeTestRule.activityRule.scenario.onActivity { activity ->
                activity.viewModelStore.clear()
            }
            
            givenAppIsLaunchedOnMap()

            `when`("I interact with the map") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Interacting with map (Placeholder)")
                // Placeholder for future interactions
            }

            this.then("I see the Map screen") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Asserting map view exists")
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                com.madanala.tern.utils.ReportGenerator.logStep("WAIT", "Waiting for map tiles to load")
                // Wait for tiles to load so screenshot is not empty
                com.madanala.tern.utils.MapTestHelper.waitForMapTiles()

                // Validate Logcat
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE UPDATE STORM")
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
            }
        }
    }
}
