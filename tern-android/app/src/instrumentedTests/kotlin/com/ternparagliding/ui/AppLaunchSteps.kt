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
    countryCode: String? = null,
    zoom: Double = 12.0
) {
    step("GIVEN", "scenario App Launch to Map ($lat, $lon, $countryCode) @ z$zoom", true) {
        val activity = composeTestRule.activity
        // Initialize CacheManager
        com.ternparagliding.utils.cache.CacheManager.initialize(activity.applicationContext)
        
        // Mock Country Code
        com.ternparagliding.utils.geo.CountryUtils.setTestCountryCode(countryCode)
        
        // Permissions & Location
        com.ternparagliding.utils.MapTestHelper.grantLocationPermissions()
        com.ternparagliding.utils.MapTestHelper.injectMockLocation(composeTestRule, lat, lon)

        // [DETERMINISTIC SYNC] Explicitly move the map to the injected location.
        // This bypasses the unreliable "auto-center on first fix" which might have
        // already fired for the default Boulder location.
        zoomTo(lat, lon, zoom)

        // Wait for Map
        composeTestRule.onNodeWithTag("map_view").assertExists()
        
        // Wait for Welcome Screen to disappear.
        //
        // The welcome screen (TernMapScreen) auto-dismisses via two Compose
        // `delay()` timers — minDisplayTimeReached (1.5s) and welcomeTimedOut
        // (5s) — OR when the app reports a GPS fix. Under ComposeTestRule the
        // frame/recomposition clock is test-controlled: a raw Thread.sleep loop
        // does NOT advance it, so those `delay()` timers never fire and the
        // welcome stays up forever (this is why these tests failed on every
        // emulator regardless of GPU/window — it was never an emulator problem).
        //
        // Fix: explicitly advance the Compose clock so the app's own timers run,
        // exactly as wall-clock time would on a real device. We pump past the 5s
        // welcomeTimedOut safety so the welcome dismisses honestly on the app's
        // real logic (no bypass of the GPS gate).
        val timeout = 10000L
        var locationReady = false

        // Step 1: pump the Compose clock past the welcome's 1.5s + 5s safety
        // timers so they actually fire. We do this with autoAdvance OFF and
        // advanceTimeBy ONLY — we must NOT call waitForIdle()/fetchSemanticsNodes()
        // here: MapLibre renders continuously, so the composition never reaches
        // idle, and any idle-sync while the clock is frozen deadlocks the runner.
        // advanceTimeBy is bounded and returns, so it's safe.
        composeTestRule.mainClock.autoAdvance = false
        try {
            var advanced = 0L
            while (advanced < 8000L) {
                composeTestRule.mainClock.advanceTimeBy(500)
                advanced += 500
            }
        } finally {
            // Step 2: restore normal auto-advancing. Node queries below then sync
            // exactly as the original (hang-free) poll did.
            composeTestRule.mainClock.autoAdvance = true
        }

        // Step 3: confirm the welcome cleared (timers have now fired).
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            val nodes = try {
                composeTestRule.onAllNodesWithText("Tern Paragliding").fetchSemanticsNodes()
            } catch (e: Exception) { emptyList() }
            if (nodes.isEmpty()) {
                locationReady = true
                break
            }
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

