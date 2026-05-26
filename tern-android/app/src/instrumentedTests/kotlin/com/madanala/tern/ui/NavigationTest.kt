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
            story("As a pilot prepping for flight, I want to open Tern and immediately see a stable moving map with my current position.") {
                // Force clear ViewModelStore to ensure fresh MapViewModel
                composeTestRule.activityRule.scenario.onActivity { activity ->
                    activity.viewModelStore.clear()
                }
                
                given("I have launched Tern from my cockpit mount") {
                    CacheManager.initialize(composeTestRule.activity.applicationContext)
                }

                `when`("the flight application initializes its engine") {
                    com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Waiting for UI readiness")
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                }
                then("The map renders without PerformanceDebugger violations") {
                    com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Asserting map view exists")
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                    
                    com.madanala.tern.utils.ReportGenerator.logStep("WAIT", "Waiting for map tiles to load")
                    // Wait for tiles to load so screenshot is not empty
                    com.madanala.tern.utils.MapTestHelper.waitForMapTiles()

                    // Validate Logcat
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
                }
            }
        }
    }
}
