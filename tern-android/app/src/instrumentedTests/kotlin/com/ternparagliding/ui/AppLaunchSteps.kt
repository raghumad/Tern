package com.ternparagliding.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.utils.ReportGenerator
import com.ternparagliding.utils.MapVisualTest

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
        com.ternparagliding.utils.CacheManager.initialize(activity.applicationContext)
        
        // Mock Country Code
        com.ternparagliding.utils.CountryUtils.setTestCountryCode(countryCode)
        
        // Permissions & Location
        com.ternparagliding.utils.MapTestHelper.grantLocationPermissions()
        com.ternparagliding.utils.MapTestHelper.injectMockLocation(composeTestRule, lat, lon)

        // [DETERMINISTIC SYNC] Explicitly move the map to the injected location.
        // This bypasses the unreliable "auto-center on first fix" which might have 
        // already fired for the default Boulder location.
        zoomTo(lat, lon, 12.0)

        // Wait for Map
        composeTestRule.onNodeWithTag("map_view").assertExists()
        
        // Wait for Welcome Screen to disappear
        val timeout = 10000L
        val startTime = System.currentTimeMillis()
        var locationReady = false
        
        while (System.currentTimeMillis() - startTime < timeout) {
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
        
        // Additional wait for data overlays if a country was specified
        if (countryCode != null) {
            com.ternparagliding.utils.MapTestHelper.waitForMapTiles(2000)
        }
    }
}

