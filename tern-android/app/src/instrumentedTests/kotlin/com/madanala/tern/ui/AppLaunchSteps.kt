package com.madanala.tern.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.utils.ReportGenerator
import com.madanala.tern.utils.MapVisualTest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Shared BDD Step: App Launch
 * This is defined here to be the source of truth for app launch scenarios.
 */
// BddTest extension removed as it's being deprecated/migrated

fun MapVisualTest.givenAppIsLaunchedOnMap(
    lat: Double = 40.0150,
    lon: Double = -105.2705,
    countryCode: String? = null
) {
    step("GIVEN", "scenario App Launch to Map ($lat, $lon, $countryCode)", true) {
        val activity = composeTestRule.activity
        // Initialize CacheManager
        com.madanala.tern.utils.CacheManager.initialize(activity.applicationContext)
        
        // Mock Country Code
        com.madanala.tern.utils.CountryUtils.setTestCountryCode(countryCode)
        
        // Permissions & Location
        com.madanala.tern.utils.MapTestHelper.grantLocationPermissions()
        com.madanala.tern.utils.MapTestHelper.injectMockLocation(composeTestRule, lat, lon)

        // Wait for Map
        composeTestRule.onNodeWithTag("map_view").assertExists()
        
        // Wait for Welcome Screen to disappear
        val timeout = 10000L
        val startTime = System.currentTimeMillis()
        var locationReady = false
        
        while (System.currentTimeMillis() - startTime < timeout) {
            com.madanala.tern.utils.MapTestHelper.injectMockLocation(composeTestRule, lat, lon)
            try {
                val nodes = composeTestRule.onAllNodesWithText("Tern Paragliding").fetchSemanticsNodes()
                if (nodes.isEmpty()) {
                    locationReady = true
                    break
                }
            } catch (e: Exception) {}
            Thread.sleep(500)
        }
        
        if (!locationReady) {
            throw AssertionError("Welcome Screen did not disappear within $timeout ms")
        }
        
        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        
        com.madanala.tern.utils.MapTestHelper.waitForMapTiles()
    }
}

