package com.madanala.tern.utils

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

import com.madanala.tern.BaseUITest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.onNodeWithTag

open class BddTest : BaseUITest() {

    @get:org.junit.Rule
    val testNameRule = org.junit.rules.TestName()

    @org.junit.Before
    fun clearLogCat() {
        try {
            Runtime.getRuntime().exec("logcat -c")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun scenario(name: String, block: () -> Unit) {
        ReportGenerator.logStep("SCENARIO", name)
        try {
            block()
            // Capture success screenshot at the end of scenario
            val screenshot = ReportGenerator.captureScreenshot("success_${name.replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario Passed", "PASS", screenshot)
        } catch (e: Throwable) {
            // Capture failure screenshot
            val screenshot = ReportGenerator.captureScreenshot("failure_${name.replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario Failed: ${e.message}", "FAIL", screenshot)
            throw e
        } finally {
            val logCatOutput = ReportGenerator.captureLogCat()
            // Use the actual test method name for the report filename to enable auto-linking
            ReportGenerator.generateReport(testNameRule.methodName, logCatOutput)
        }
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

    private fun step(type: String, description: String, takeScreenshot: Boolean, block: () -> Unit) {
        try {
            block()
            val screenshot = if (takeScreenshot) {
                ReportGenerator.captureScreenshot("step_${type}_${description.take(20).replace(" ", "_")}")
            } else {
                null
            }
            ReportGenerator.logStep(type, description, "PASS", screenshot)
        } catch (e: Throwable) {
            val screenshot = ReportGenerator.captureScreenshot("failure_${type}_${description.take(20).replace(" ", "_")}")
            ReportGenerator.logStep(type, description, "FAIL", screenshot)
            throw e
        }
    }
    fun givenAppIsLaunchedOnMap(
        lat: Double = 40.0150, // Boulder, CO
        lon: Double = -105.2705
    ) {
        step("GIVEN", "App is launched on Map at $lat, $lon", true) {
             // Initialize CacheManager
            com.madanala.tern.utils.CacheManager.initialize(composeTestRule.activity.applicationContext)
            
            // OSMDroid Config
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            org.osmdroid.config.Configuration.getInstance().load(context, androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
            org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName

            // Permissions & Location
            MapTestHelper.grantLocationPermissions()
            MapTestHelper.injectMockLocation(composeTestRule, lat, lon)

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
            MapTestHelper.waitForMapTiles()
        }
    }
}
