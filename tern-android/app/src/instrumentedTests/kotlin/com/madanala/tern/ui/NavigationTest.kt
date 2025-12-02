package com.madanala.tern.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.screens.TernMapScreen
import com.madanala.tern.ui.theme.TernTheme
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.CacheManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest : BddTest() {

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

            then("I see the Map screen") {
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
