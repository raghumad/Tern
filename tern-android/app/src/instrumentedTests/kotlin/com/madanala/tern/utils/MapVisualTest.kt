package com.madanala.tern.utils

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.madanala.tern.TernParaglidingActivity
import com.madanala.tern.model.Route
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import org.junit.Rule
import org.osmdroid.util.GeoPoint
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.rule.GrantPermissionRule
import android.Manifest

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
        // Essential: Set the location provider factory BEFORE the Activity starts.
        // Since createAndroidComposeRule starts the Activity as soon as it's evaluated,
        // we must set this in the init block of the test class.
        com.madanala.tern.ui.components.MapViewModel.locationProviderFactory = { 
            // Default to Boulder, US for tests
            MockLocationProvider(40.015, -105.27) 
        }
        
        // Also set a default test country code to avoid race conditions with initial location updates
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
        
        com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = 0L
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
        
        // 2. Proactively clear disk caches and performance metrics
        com.madanala.tern.utils.CacheManager.clearAllCaches()
        com.madanala.tern.utils.PerformanceDebugger.clearMetrics()
        Log.d("MapVisualTest", "Proactively cleared all caches and performance metrics")

        // Reset global state to ensure test isolation (0L for immediate updates)
        com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = 0L

        // Wait for activity to be fully ready and setContent called
        Thread.sleep(2000)
        
        // Clear state before each test
        clearState()
    }

    @org.junit.After
    fun tearDown() {
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

                // RESET universal coordinator state via MapViewModel (Priority: Regression Fix)
                try {
                    val componentActivity = activity as? androidx.activity.ComponentActivity
                    if (componentActivity != null) {
                        val mapViewModel = ViewModelProvider(componentActivity).get(com.madanala.tern.ui.components.MapViewModel::class.java)
                        val field = mapViewModel.javaClass.getDeclaredField("overlayCoordinator")
                        field.isAccessible = true
                        val coordinator = field.get(mapViewModel) as? com.madanala.tern.ui.overlays.OverlayCoordinator
                        coordinator?.reset()
                        Log.d("MapVisualTest", "Reset OverlayCoordinator via MapViewModel")
                    } else {
                        Log.w("MapVisualTest", "Activity is not a ComponentActivity, could not access ViewModel")
                    }
                } catch (e: Exception) {
                    Log.w("MapVisualTest", "Could not reset coordinator via ViewModel: ${e.message}")
                }

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
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            store.dispatch(MapAction.UpdateCenter(GeoPoint(lat, lon)))
            store.dispatch(MapAction.UpdateZoom(zoom))
        }
        composeTestRule.waitForIdle()
        // Wait for tiles to at least start loading
        Thread.sleep(1000)
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
     */
    fun assertMapLocation(expectedLat: Double, expectedLon: Double, tolerance: Double = 0.5) {
        var actualCenter: GeoPoint? = null
        composeTestRule.runOnUiThread {
            try {
                val activity = composeTestRule.activity
                val rootView = activity.findViewById<android.view.View>(android.R.id.content)
                val mapView = findMapViewRecursive(rootView)
                actualCenter = mapView?.mapCenter as? GeoPoint
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
     * Consolidates waiting for both airspaces and PG spots with shared timeout/polling
     * Uses recursive counting to handle nested FolderOverlays (Priority: Stability Fix)
     */
    fun waitForMapData(minAirspaces: Int = 1, minPGSpots: Int = 1, timeoutMillis: Long = 45000) {
        val startTime = System.currentTimeMillis()
        var aCount = 0
        var pCount = 0
        
        Log.i("MapVisualTest", "Waiting for Map Data: Airspaces >= $minAirspaces, PG Spots >= $minPGSpots (Timeout: ${timeoutMillis}ms)")
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            var currentMapHash = 0
            composeTestRule.runOnUiThread {
                try {
                    val activity = composeTestRule.activity
                    val rootView = activity.findViewById<android.view.View>(android.R.id.content)
                    val mapView = findMapViewRecursive(rootView)
                    if (mapView != null) {
                        currentMapHash = System.identityHashCode(mapView)
                        val counts = countOverlaysRecursively(mapView.overlays)
                        aCount = counts.first
                        pCount = counts.second
                    }
                } catch (e: Exception) {
                    Log.e("MapVisualTest", "Error checking overlay counts: ${e.message}")
                }
            }
            
            if (aCount >= minAirspaces && pCount >= minPGSpots) {
                println("MapVisualTest: waitForMapData SUCCESS: Found $aCount airspaces and $pCount spots on MapView@$currentMapHash after ${System.currentTimeMillis() - startTime}ms")
                return
            }
            
            Thread.sleep(1000)
        }

        // On failure, dump EVERYTHING for diagnostics
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val rootView = activity.findViewById<android.view.View>(android.R.id.content)
            val mapView = findMapViewRecursive(rootView)
            val mapHash = System.identityHashCode(mapView)
            
            fun dumpOverlays(list: List<org.osmdroid.views.overlay.Overlay>, indent: String = "  ") {
                list.forEachIndexed { index, overlay ->
                    val type = overlay::class.simpleName
                    val hash = System.identityHashCode(overlay)
                    Log.e("MapVisualTest", "$indent[$index] Type: $type, Hash: $hash")
                    
                    if (overlay is org.osmdroid.views.overlay.Polygon) {
                        @Suppress("DEPRECATION")
                        val points = overlay.getPoints()
                        val pointsSize = points.size
                        val center = if (points.isNotEmpty()) {
                            val latMean = points.map { it.latitude }.average()
                            val lonMean = points.map { it.longitude }.average()
                            "~($latMean, $lonMean)"
                        } else "N/A"
                        Log.e("MapVisualTest", "$indent  Polygon Points: $pointsSize, Approx Center: $center")
                    } else if (overlay is org.osmdroid.views.overlay.Marker) {
                         Log.e("MapVisualTest", "$indent  Marker Position: ${overlay.position}")
                    } else if (overlay is org.osmdroid.views.overlay.FolderOverlay) {
                        dumpOverlays(overlay.items, "$indent  ")
                    }
                }
            }

            Log.e("MapVisualTest", "DIAGNOSTIC FAILURE DUMP for MapView@$mapHash")
            if (mapView != null) {
                Log.e("MapVisualTest", "Top-level Overlay count: ${mapView.overlays.size}")
                dumpOverlays(mapView.overlays)
            } else {
                Log.e("MapVisualTest", "ERROR: MapView NOT FOUND in view hierarchy!")
            }
        }
        
        throw AssertionError("Timed out waiting for map data after ${timeoutMillis}ms. Final: Airspaces=$aCount (min $minAirspaces), PGSpots=$pCount (min $minPGSpots)")
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


    private fun findMapViewRecursive(view: android.view.View): org.osmdroid.views.MapView? {
        if (view is org.osmdroid.views.MapView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findMapViewRecursive(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    private fun countOverlaysRecursively(overlays: List<org.osmdroid.views.overlay.Overlay>): Pair<Int, Int> {
        var aCount = 0
        var pCount = 0
        
        fun process(list: List<org.osmdroid.views.overlay.Overlay>) {
            list.forEach { overlay ->
                when (overlay) {
                    is org.osmdroid.views.overlay.Polygon -> aCount++
                    is org.osmdroid.views.overlay.Marker -> pCount++
                    is org.osmdroid.views.overlay.FolderOverlay -> process(overlay.items)
                }
            }
        }
        
        process(overlays)
        return Pair(aCount, pCount)
    }

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
}
