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


    @org.junit.Before
    fun setup() {
        ReportGenerator.currentTestName = testNameRule.methodName
        Log.d("MapVisualTest", "=== START ${testNameRule.methodName} ===")
        
        // Proactively clear disk caches before activity is fully ready
        try {
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val airspaceDir = java.io.File(context.cacheDir, "airspace_cache")
            val pgSpotDir = java.io.File(context.cacheDir, "pgspot_cache")
            if (airspaceDir.exists()) airspaceDir.deleteRecursively()
            if (pgSpotDir.exists()) pgSpotDir.deleteRecursively()
            Log.d("MapVisualTest", "Proactively cleared disk caches")
        } catch (e: Exception) {
            Log.e("MapVisualTest", "Failed to proactively clear caches: ${e.message}")
        }

        // Wait for activity to be fully ready and setContent called
        Thread.sleep(2000)
        
        // Clear state before each test
        clearState()
    }

    private fun clearState() {
        composeTestRule.runOnUiThread {
            try {
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                store.dispatch(MapAction.ClearAllRoutes)
                store.dispatch(MapAction.DeselectRoute)
                store.dispatch(MapAction.DeselectWaypoint)
                
                // Clear Caches
                com.madanala.tern.utils.CacheManager.clearAllCaches()
                com.madanala.tern.utils.CacheManager.airspaceCache.resetBaseUrlForTesting()
                com.madanala.tern.utils.CacheManager.pgSpotCache.resetBaseUrlForTesting()
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

    @org.junit.After
    fun tearDown() {
        ReportGenerator.generateFinalReport(testNameRule.methodName)
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
