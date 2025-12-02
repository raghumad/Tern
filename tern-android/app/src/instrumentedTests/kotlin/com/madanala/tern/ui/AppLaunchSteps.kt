package com.madanala.tern.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.foundation.layout.fillMaxSize
import com.madanala.tern.utils.BddTest

/**
 * Shared BDD Step: App Launch
 * This is defined here to be the source of truth for app launch scenarios.
 */
fun BddTest.givenAppIsLaunchedOnMap(
    lat: Double = 40.0150, // Boulder, CO
    lon: Double = -105.2705
) {
    step("GIVEN", "scenario App Launch to Map ($lat, $lon)", true) {
         // Initialize CacheManager
        com.madanala.tern.utils.CacheManager.initialize(composeTestRule.activity.applicationContext)
        
        // OSMDroid Config
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        org.osmdroid.config.Configuration.getInstance().load(context, androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName

        // Permissions & Location
        com.madanala.tern.utils.MapTestHelper.grantLocationPermissions()
        com.madanala.tern.utils.MapTestHelper.injectMockLocation(composeTestRule, lat, lon)

        // Set Content
        composeTestRule.setContent {
            com.madanala.tern.ui.theme.TernTheme {
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    com.madanala.tern.ui.screens.TernMapScreen()
                }
            }
        }

        // Wait for Map
        composeTestRule.onNodeWithTag("map_view").assertExists()
        
        // Wait for Welcome Screen to disappear (Location Ready)
        // We loop and re-inject location to ensure the map's location provider receives the update
        // as there can be a race condition between enabling the provider and the single injection.
        val timeout = 10000L
        val startTime = System.currentTimeMillis()
        var locationReady = false
        
        while (System.currentTimeMillis() - startTime < timeout) {
            // Re-inject location
            com.madanala.tern.utils.MapTestHelper.injectMockLocation(composeTestRule, lat, lon)
            
            // Check if Welcome Screen is gone (meaning location is ready)
            try {
                val nodes = composeTestRule.onAllNodesWithText("Tern Paragliding").fetchSemanticsNodes()
                if (nodes.isEmpty()) {
                    locationReady = true
                    break
                }
            } catch (e: Exception) {
                // Ignore and retry
            }
            
            Thread.sleep(500)
        }
        
        if (!locationReady) {
            throw AssertionError("Welcome Screen did not disappear within $timeout ms - Location fix not received")
        }
        
        // Settings button should be visible (sanity check)
        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        
        com.madanala.tern.utils.MapTestHelper.waitForMapTiles()
    }
}
