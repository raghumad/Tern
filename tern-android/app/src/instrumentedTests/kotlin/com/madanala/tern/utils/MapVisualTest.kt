package com.madanala.tern.utils

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.madanala.tern.TernParaglidingActivity
import com.madanala.tern.model.Route
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import org.junit.Rule
import org.osmdroid.util.GeoPoint
import androidx.compose.ui.graphics.asAndroidBitmap
import android.Manifest
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Base class for Visual Tests that require the real Map UI.
 * Provides BDD-style helpers but launches TernParaglidingActivity.
 */
open class MapVisualTest {
    

    @get:org.junit.Rule
    val testNameRule = org.junit.rules.TestName()

    @get:org.junit.Rule
    val composeTestRule = createAndroidComposeRule<TernParaglidingActivity>()

    @get:org.junit.Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    init {
        // M8: locationProviderFactory was removed from MapViewModel when
        // OSMDroid was replaced by MapLibre. Location is now handled by
        // ReduxLocationService in the Compose tree, not by an OSMDroid
        // IMyLocationProvider. The test still sets a country code to
        // prevent real data downloads during test runs.
        com.madanala.tern.utils.CountryUtils.setTestCountryCode("TEST")
    }


    @org.junit.Before
    fun setup() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        
        // 1. ISOLATION: Set test country code BEFORE anything else to prevent real US data downloads
        com.madanala.tern.utils.CountryUtils.setTestCountryCode("TEST")
        
        com.madanala.tern.utils.CacheManager.initialize(context)
        // 2. HYGIENE: Clear all caches immediately to ensure no state leakage from previous runs
        com.madanala.tern.utils.CacheManager.clearAllCaches()

        // M8: MAP_MOVE_DEBOUNCE_MS was removed from MapViewModel when
        // OSMDroid was replaced by MapLibre. MapLibre camera updates flow
        // through CameraState snapshotFlow with distinctUntilChanged.
        val className = this.javaClass.simpleName
        ReportGenerator.currentTestClass = className
        ReportGenerator.currentTestName = testNameRule.methodName

        // 1. Clear logcat FIRST so START tag survives
        try {
            Runtime.getRuntime().exec("logcat -c")
            Thread.sleep(500) // Give it a moment to clear
        } catch (e: Exception) {
            // Log.e("MapVisualTest", "Failed to clear logcat: ${e.message}")
        }
        
        Log.i("MapVisualTest", "=== START ${testNameRule.methodName} ===")

        VideoHelper.startRecording(testNameRule.methodName.replace(" ", "_"))

        // 2. Proactively clear disk caches and performance metrics
        com.madanala.tern.utils.CacheManager.clearAllCaches()
        com.madanala.tern.utils.PerformanceDebugger.clearMetrics()
        Log.d("MapVisualTest", "Proactively cleared all caches and performance metrics")

        // M8: MAP_MOVE_DEBOUNCE_MS removed (see note above).

        // Start mock server to intercept all weather/spot requests and prevent 404 state resets
        WeatherTestHelper.startServer()

        // Wait for activity to be fully ready and setContent called
        Thread.sleep(2000)
        
        // Clear state before each test
        clearState()
    }

    @org.junit.After
    fun tearDown() {
        VideoHelper.stopRecording()
        WeatherTestHelper.stopServer()
        Log.i("MapVisualTest", "=== END ${testNameRule.methodName} ===")

        // [HYGIENE FIX] Proactively clear ViewModelStore to trigger onCleared() 
        // and shutdown leaking coordinators/managers before the next test starts.
        composeTestRule.runOnUiThread {
            try {
                val activity = composeTestRule.activity
                activity.viewModelStore.clear()
                Log.d("MapVisualTest", "Proactively cleared Activity ViewModelStore")
            } catch (e: Exception) {
                Log.w("MapVisualTest", "Failed to clear ViewModelStore: ${e.message}")
            }
        }
        
        // Final cache clear
        com.madanala.tern.utils.CacheManager.clearAllCaches()
        
        val className = this.javaClass.simpleName
        ReportGenerator.generateFinalReport(className, testNameRule.methodName)
    }

    private fun clearState() {
        composeTestRule.runOnUiThread {
            try {
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                // M8: OverlayCoordinator was removed when OSMDroid overlay
                // managers were deleted. MapLibre layers are stateless
                // Compose composables driven by Redux state -- no separate
                // coordinator lifecycle to reset.

                store.dispatch(MapAction.ClearAllRoutes)
                store.dispatch(MapAction.DeselectRoute)
                store.dispatch(MapAction.DeselectWaypoint)
                
                // Reset map to baseline location to prevent cross-test state leakage
                store.dispatch(MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(40.015, -105.27)))
                store.dispatch(MapAction.UpdateZoom(12.0))
                store.dispatch(MapAction.SetLocationReady(true))
                
                // [OFFLINE-FIRST DESIGN] 
                // Do NOT invoke CacheManager.clearAllCaches() here. 
                // Tests must reuse .flex indexes to avoid rate-limiting the CI pipeline and faithfully validate the app's offline geometry mapping capabilities.
            } catch (e: Exception) {
                Log.e("MapVisualTest", "Error clearing state: ${e.message}")
            }
        }
        composeTestRule.waitForIdle()
    }

    /**
     * Helper to show a route on the map by injecting it into the Redux store.
     */
    fun showRouteOnMap(route: Route) {
        ReportGenerator.logStep("TEST", "Showing route: ${route.name}")
        
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            store.dispatch(MapAction.AddRoute(route))
            store.dispatch(MapAction.SelectRoute(route.id))
        }
        
        composeTestRule.waitForIdle()
    }

    /**
     * Helper to zoom the map to a specific location.
     */
    fun zoomTo(lat: Double, lon: Double, zoom: Double = 15.0) {
        ReportGenerator.logStep("ACTION", "Zooming to ($lat, $lon) @ $zoom")
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            store.dispatch(MapAction.UpdateCenter(GeoPoint(lat, lon)))
            store.dispatch(MapAction.UpdateZoom(zoom))
        }
        composeTestRule.waitForIdle()
        // Let the MapLibre camera animation settle before polling Redux.
        // The Redux→MapLibre→Redux round-trip takes time: dispatch writes
        // Redux, LaunchedEffect picks it up, cameraState.animateTo runs,
        // then the camera→Redux feedback updates the store with the final
        // position. Without this delay the feedback loop can overwrite
        // the dispatched center with the old camera position.
        Thread.sleep(2000)
        waitForMapLocation(lat, lon, timeoutMillis = 10000)
    }

    /**
     * Helper to zoom the map to fit the entire route.
     * Uses the dynamic bounding box mechanism (Instrumentation Truth).
     */
    fun zoomToRouteEntirely(route: Route) {
        ReportGenerator.logStep("ACTION", "Auto-zooming to fit entire route: ${route.name}")
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            store.dispatch(MapAction.ZoomToRoute(route.id))
        }
        composeTestRule.waitForIdle()
        // Give it a moment to animate and settle
        Thread.sleep(2000)
    }

    /**
     * Polls the Redux store until its center is at the expected location.
     *
     * M8: Rewritten to use MapStore state instead of OSMDroid MapView.
     * The MapLibre camera is driven by Redux; dispatching UpdateCenter
     * immediately updates the store's center, and the CameraState
     * snapshotFlow propagates it to the native renderer.
     */
    fun waitForMapLocation(expectedLat: Double, expectedLon: Double, tolerance: Double = 0.01, timeoutMillis: Long = 5000) {
        val startTime = System.currentTimeMillis()
        var lastActual: GeoPoint? = null

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            composeTestRule.runOnUiThread {
                try {
                    val activity = composeTestRule.activity
                    val store = ViewModelProvider(activity)[MapStore::class.java]
                    lastActual = store.state.value.center
                } catch (e: Exception) {}
            }

            val actual = lastActual
            if (actual != null) {
                val latDiff = Math.abs(actual.latitude - expectedLat)
                val lonDiff = Math.abs(actual.longitude - expectedLon)
                if (latDiff <= tolerance && lonDiff <= tolerance) {
                    Log.d("MapVisualTest", "Map reached location ($expectedLat, $expectedLon) after ${System.currentTimeMillis() - startTime}ms")
                    return
                }
            }
            Thread.sleep(200)
        }

        throw AssertionError("Timed out waiting for map to reach ($expectedLat, $expectedLon). Last seen: $lastActual")
    }

    /**
     * Helper to wait for map rendering.
     */
    fun waitForMapToRender(timeout: Long = 2000) {
        composeTestRule.waitForIdle()
        Thread.sleep(timeout)
    }

    /**
     * Asserts that the map is currently centered at the expected location.
     *
     * M8: Rewritten to use MapStore state instead of OSMDroid MapView.
     */
    fun assertMapLocation(expectedLat: Double, expectedLon: Double, tolerance: Double = 0.01) {
        var actualCenter: GeoPoint? = null
        composeTestRule.runOnUiThread {
            try {
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                actualCenter = store.state.value.center
            } catch (e: Exception) {
                Log.e("MapVisualTest", "Error getting map center: ${e.message}")
            }
        }

        val actual = actualCenter
        if (actual == null) {
            throw AssertionError("Could not determine map center")
        }

        val latDiff = Math.abs(actual.latitude - expectedLat)
        val lonDiff = Math.abs(actual.longitude - expectedLon)

        if (latDiff > tolerance || lonDiff > tolerance) {
            ReportGenerator.logStep("ASSERT", "Map location mismatch: Expected ($expectedLat, $expectedLon), Actual (${actual.latitude}, ${actual.longitude})", "FAIL")
            throw AssertionError("Map location mismatch. Expected (~$expectedLat, ~$expectedLon), but was (${actual.latitude}, ${actual.longitude}). Tolerance: $tolerance")
        }

        ReportGenerator.logStep("ASSERT", "Map location verified: near ($expectedLat, $expectedLon)", "PASS")
    }

    /**
     * Asserts that the map zoom level is within the expected range.
     *
     * M8: Rewritten to use MapStore state instead of OSMDroid MapView.
     */
    fun assertZoomLevel(expectedZoom: Double, tolerance: Double = 0.5) {
        var actualZoom = 0.0
        composeTestRule.runOnUiThread {
            try {
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                actualZoom = store.state.value.zoom
            } catch (e: Exception) {
                Log.e("MapVisualTest", "Error getting zoom level: ${e.message}")
            }
        }

        if (Math.abs(actualZoom - expectedZoom) > tolerance) {
            ReportGenerator.logStep("ASSERT", "Zoom level mismatch: Expected $expectedZoom, Actual $actualZoom", "FAIL")
            throw AssertionError("Zoom level mismatch. Expected ~$expectedZoom, but was $actualZoom. Tolerance: $tolerance")
        }

        ReportGenerator.logStep("ASSERT", "Zoom level verified: $actualZoom", "PASS")
    }

    /**
     * Asserts that a route with the given name exists in the Redux store.
     */
    fun assertRoutePresence(routeName: String) {
        var routeExists = false
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            routeExists = store.state.value.routes.any { it.name == routeName }
        }

        if (!routeExists) {
            ReportGenerator.logStep("ASSERT", "Route '$routeName' not found in store", "FAIL")
            throw AssertionError("Route '$routeName' not found in store.")
        }
        
        ReportGenerator.logStep("ASSERT", "Route '$routeName' presence verified", "PASS")
    }

    /**
     * Waits for airspace and PG-spot data to arrive in the Redux store.
     *
     * M8: Rewritten to poll MapStore state instead of counting OSMDroid
     * overlay objects. MapLibre layers are driven by GeoJSON sources in
     * the Redux state; the source-of-truth for "data has loaded" is the
     * store, not the rendered view hierarchy.
     */
    fun waitForMapData(minAirspaces: Int = 1, minPGSpots: Int = 1, timeoutMillis: Long = 45000) {
        val startTime = System.currentTimeMillis()

        Log.i("MapVisualTest", "Waiting for Map Data (Redux): Airspaces >= $minAirspaces, PG Spots >= $minPGSpots (Timeout: ${timeoutMillis}ms)")

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            var hasAirspaces = minAirspaces <= 0
            var hasPGSpots = minPGSpots <= 0

            composeTestRule.runOnUiThread {
                try {
                    val activity = composeTestRule.activity
                    val store = ViewModelProvider(activity)[MapStore::class.java]
                    val state = store.state.value
                    // Airspace data is loaded when the store has country codes
                    if (minAirspaces > 0 && state.airspaceCountries.isNotEmpty()) {
                        hasAirspaces = true
                    }
                    // PG spots are loaded when the store has pgSpotGeoJson
                    if (minPGSpots > 0 && state.pgSpotGeoJson != null) {
                        hasPGSpots = true
                    }
                } catch (e: Exception) {
                    Log.e("MapVisualTest", "Error checking Redux state: ${e.message}")
                }
            }

            if (hasAirspaces && hasPGSpots) {
                Log.i("MapVisualTest", "waitForMapData SUCCESS after ${System.currentTimeMillis() - startTime}ms")
                return
            }

            Thread.sleep(1000)
        }

        throw AssertionError("Timed out waiting for map data after ${timeoutMillis}ms.")
    }

    /**
     * Actively polls the physical disk layer to determine if the UniversalCountryCacheManager
     * has successfully closed the memory streams and written the Hilbert Curve .idx files for a country.
     * This prevents premature test timeouts strictly based on visual UI rendering checks.
     */
    fun waitForCacheReadiness(countryCode: String, timeoutMillis: Long = 60000) {
        val startTime = System.currentTimeMillis()
        Log.i("MapVisualTest", "Waiting for Physical Cache Readiness (.idx / .flex) for Country: $countryCode")
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            val isReady = com.madanala.tern.utils.CacheManager.airspaceCache.isCached(countryCode)
            if (isReady) {
                Log.i("MapVisualTest", "Cache successfully indexed for $countryCode!")
                return
            }
            Thread.sleep(2000)
        }
        
        throw AssertionError("Timed out waiting for $countryCode cache to serialize to disk.")
    }

    fun waitForAirspaces(minCount: Int = 1, timeoutMillis: Long = 30000) {
        waitForMapData(minAirspaces = minCount, minPGSpots = 0, timeoutMillis = timeoutMillis)
    }

    fun waitForPGSpots(minCount: Int = 1, timeoutMillis: Long = 30000) {
        waitForMapData(minAirspaces = 0, minPGSpots = minCount, timeoutMillis = timeoutMillis)
    }


    // M8: findMapViewRecursive and countOverlaysRecursively removed.
    // OSMDroid MapView is no longer in the view hierarchy. All overlay
    // data is in the Redux store; rendering is MapLibre's concern.

    // BDD Helpers (Copied from BddTest to avoid rule conflicts)
    fun scenario(name: String, block: () -> Unit) {
        ReportGenerator.logStep("SCENARIO", name)
        // Ensure UI is ready before any scenario logic
        try {
            composeTestRule.onNode(hasTestTag("map_view")).assertExists()
        } catch (e: Throwable) {
            Log.w("MapVisualTest", "map_view not found at scenario start. Waiting for idle.")
            composeTestRule.waitForIdle()
        }
        
        try {
            block()
            composeTestRule.waitForIdle()
            val result = ReportGenerator.captureScreenshot("success_${name.replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario Passed: Physical UI and Redux State Validated", "PASS", result?.path, result?.hash)
        } catch (e: Throwable) {
            composeTestRule.waitForIdle()
            val result = ReportGenerator.captureScreenshot("failure_${name.replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario Failed: ${e.message}", "FAIL", result?.path, result?.hash)
            throw e
        } finally {
            ReportGenerator.finishScenario(name, ReportGenerator.captureLogCat())
        }
    }

    fun story(description: String, block: () -> Unit) {
        ReportGenerator.logStep("STORY", description)
        block()
    }

    fun given(description: String, takeScreenshot: Boolean = false, block: () -> Unit) {
        step("GIVEN", description, takeScreenshot, block)
    }

    fun `when`(description: String, takeScreenshot: Boolean = false, block: () -> Unit) {
        step("WHEN", description, takeScreenshot, block)
    }

    fun then(description: String, takeScreenshot: Boolean = true, block: () -> Unit) {
        step("THEN", description, takeScreenshot, block)
    }

    fun and(description: String, takeScreenshot: Boolean = true, block: () -> Unit) {
        step("AND", description, takeScreenshot, block)
    }

    fun step(type: String, description: String, takeScreenshot: Boolean, block: () -> Unit) {
        try {
            block()
            val result = if (takeScreenshot) {
                // [STABILITY FIX] Synchronize with UI and Redux before capture
                composeTestRule.waitForIdle()
                Thread.sleep(200) 
                ReportGenerator.captureScreenshot("step_${type}_${description.take(20).replace(" ", "_")}")
            } else {
                null
            }
            ReportGenerator.logStep(type, description, "PASS", result?.path, result?.hash)
        } catch (e: Throwable) {
            val result = ReportGenerator.captureScreenshot("failure_${type}_${description.take(20).replace(" ", "_")}")
            ReportGenerator.logStep(type, description, "FAIL", result?.path, result?.hash)
            throw e
        }
    }

    /**
     * Asserts that a specific color signature exists within the bounds of a test tag.
     * Validates safety-critical visual indicators (e.g. Amber Convective Halos).
     */
    fun thenExpectHazardFidelity(tag: String, targetColor: Int, waitMillis: Long = 500) {
        then("Expect hazard fidelity on $tag (Color + Animation)") {
            val node = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
            
            // Get screen coordinates of the node in PIXELS
            val bounds = node.getUnclippedBoundsInRoot()
            val density = composeTestRule.density
            
            // Get Activity window offset to convert Window coords to Screen coords (for Hardware Screenshot)
            val activityLocation = IntArray(2)
            composeTestRule.runOnUiThread {
                composeTestRule.activity.window.decorView.getLocationOnScreen(activityLocation)
            }
            val offsetX = activityLocation[0]
            val offsetY = activityLocation[1]

            val screenRect = android.graphics.Rect(
                with(density) { bounds.left.roundToPx() } + offsetX,
                with(density) { bounds.top.roundToPx() } + offsetY,
                with(density) { bounds.right.roundToPx() } + offsetX,
                with(density) { bounds.bottom.roundToPx() } + offsetY
            ).apply {
                // Hazards are often slightly outside the reported node bounds (e.g. Halo shadow, Bolt offset)
                // We expand significantly to ensure we capture the pixels.
                inset(-16, -16) 
            }
            
            val scanRect = screenRect // Use the same larger rect for both

            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            
            // Step 1: Verify Color Signature (Occupancy Check)
            // Initial frame for baseline
            composeTestRule.mainClock.autoAdvance = false
            composeTestRule.waitForIdle()
            
            val bitmap1 = automation.takeScreenshot()
            val found = VisualValidator.findColorSignature(bitmap1, scanRect, targetColor)
            if (!found) {
                // If not found at T=0, maybe it's the "off" state of a blink. Pulse the clock.
                composeTestRule.mainClock.advanceTimeBy(400)
                composeTestRule.waitForIdle()
                Thread.sleep(150)
                val bitmapRetry = automation.takeScreenshot()
                if (!VisualValidator.findColorSignature(bitmapRetry, scanRect, targetColor)) {
                    throw AssertionError("Algorithmic Validation Failed: Color signature ${Integer.toHexString(targetColor)} not found in $tag at $scanRect")
                }
            }
            Log.i("MapVisualTest", "Verified: Color signature exists for $tag")

            // Step 2: Fidelity Check (Deterministic Animation)
            // We advance the clock in steps to ensure we catch the transition regardless of starting phase
            var bestDelta = 0f
            
            val advances = listOf(300L, 300L, 300L) // Total 900ms scan
            for (ms in advances) {
                composeTestRule.mainClock.advanceTimeBy(ms)
                composeTestRule.waitForIdle()
                Thread.sleep(200) // Buffer flush
                val bitmapN = automation.takeScreenshot()
                val delta = VisualValidator.getRegionDelta(bitmap1, bitmapN, screenRect, pixelTolerance = 5)
                bestDelta = Math.max(bestDelta, delta)
                if (bestDelta > 0.001f) break
            }
            
            Log.i("MapVisualTest", "Deterministic Max Delta for $tag: $bestDelta")
            
            if (bestDelta <= 0.001f) {
                throw AssertionError("Algorithmic Validation Failed: Animation fidelity zero for $tag in scan area $screenRect. Hazards MUST animate for safety.")
            }
            
            // Re-enable for subsequent steps
            composeTestRule.mainClock.autoAdvance = true

            Log.i("MapVisualTest", "Verified: Animation active for $tag (Delta: $bestDelta)")
        }
    }
}
