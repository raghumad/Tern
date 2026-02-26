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
    
    val mockServer = com.madanala.tern.test.MockServer()

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
        com.madanala.tern.utils.CountryUtils.setTestCountryCode("us")
    }


    @org.junit.Before
    fun setup() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        com.madanala.tern.utils.CacheManager.initialize(context)

        com.madanala.tern.ui.components.MapViewModel.MAP_MOVE_DEBOUNCE_MS = 0L
        ReportGenerator.currentTestName = testNameRule.methodName

        // 1. Clear logcat FIRST so START tag survives
        try {
            Runtime.getRuntime().exec("logcat -c")
            Thread.sleep(500) // Give it a moment to clear
        } catch (e: Exception) {
            // Log.e("MapVisualTest", "Failed to clear logcat: ${e.message}")
        }

        mockServer.start()
        val mockUrl = mockServer.url("/")
        com.madanala.tern.utils.CacheManager.airspaceCache.setBaseUrlForTesting(mockUrl)
        com.madanala.tern.utils.CacheManager.pgSpotCache.setBaseUrlForTesting(mockUrl)
        
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
        
        // Reset base URLs to prevent test leakage
        com.madanala.tern.utils.CacheManager.airspaceCache.resetBaseUrlForTesting()
        com.madanala.tern.utils.CacheManager.pgSpotCache.resetBaseUrlForTesting()
        
        mockServer.shutdown()
        
        // Final cache clear
        com.madanala.tern.utils.CacheManager.clearAllCaches()
        
        ReportGenerator.generateFinalReport(testNameRule.methodName)
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
                
                // Clear Caches
                com.madanala.tern.utils.CacheManager.clearAllCaches()
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
     * Helper to wait for a specific number of airspaces to be rendered.
     */
    fun waitForAirspaces(minCount: Int = 1, timeoutMillis: Long = 20000) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            var count = 0
            composeTestRule.runOnUiThread {
                try {
                    val activity = composeTestRule.activity
                    val componentActivity = activity as? androidx.activity.ComponentActivity
                    if (componentActivity != null) {
                        val mapViewModel = ViewModelProvider(componentActivity).get(com.madanala.tern.ui.components.MapViewModel::class.java)
                        val field = mapViewModel.javaClass.getDeclaredField("overlayCoordinator")
                        field.isAccessible = true
                        val coordinator = field.get(mapViewModel) as? com.madanala.tern.ui.overlays.OverlayCoordinator
                        count = coordinator?.getRenderedOverlayCount(com.madanala.tern.redux.OverlayType.AIRSPACE) ?: 0
                    }
                } catch (e: Exception) {
                    Log.e("MapVisualTest", "Error checking airspace count: ${e.message}")
                }
            }
            if (count >= minCount) {
                println("DEBUG: waitForAirspaces SUCCESS: Found $count airspaces")
                return
            }
            if (System.currentTimeMillis() % 2000 < 500) {
                println("DEBUG: waitForAirspaces: Current count = $count")
            }
            Thread.sleep(500)
        }
        val activity = composeTestRule.activity
        println("DEBUG: waitForAirspaces TIMEOUT. Activity: $activity")
        throw AssertionError("Timed out waiting for at least $minCount airspaces. Final count: see console logs")
    }

    /**
     * Helper to wait for a specific number of PG spots to be rendered.
     */
    fun waitForPGSpots(minCount: Int = 1, timeoutMillis: Long = 20000) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            var count = 0
            composeTestRule.runOnUiThread {
                try {
                    val activity = composeTestRule.activity
                    val componentActivity = activity as? androidx.activity.ComponentActivity
                    if (componentActivity != null) {
                        val mapViewModel = ViewModelProvider(componentActivity).get(com.madanala.tern.ui.components.MapViewModel::class.java)
                        val field = mapViewModel.javaClass.getDeclaredField("overlayCoordinator")
                        field.isAccessible = true
                        val coordinator = field.get(mapViewModel) as? com.madanala.tern.ui.overlays.OverlayCoordinator
                        count = coordinator?.getRenderedOverlayCount(com.madanala.tern.redux.OverlayType.PG_SPOTS) ?: 0
                    }
                } catch (e: Exception) {
                    Log.e("MapVisualTest", "Error checking PG spot count: ${e.message}")
                }
            }
            if (count >= minCount) return
            Thread.sleep(500)
        }
        throw AssertionError("Timed out waiting for at least $minCount PG spots. Final count: unknown (see logs)")
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
            val result = ReportGenerator.captureScreenshot("success_${name.replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario Passed", "PASS", result?.path, result?.hash)
        } catch (e: Throwable) {
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
