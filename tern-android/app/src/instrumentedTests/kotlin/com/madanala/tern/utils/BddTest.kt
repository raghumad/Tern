package com.madanala.tern.utils

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

import com.madanala.tern.BaseUITest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed

import android.util.Log

open class BddTest : BaseUITest() {

    @get:org.junit.Rule
    val testNameRule = org.junit.rules.TestName()

    @org.junit.Before
    fun clearLogCat() {
        try {
            Runtime.getRuntime().exec("logcat -c")
            // Give logcat a moment to clear
            Thread.sleep(100)
            val maxMemory = Runtime.getRuntime().maxMemory()
            println("=== RUNTIME MAX MEMORY: ${maxMemory / 1024 / 1024} MB ===")
            ReportGenerator.currentTestName = testNameRule.methodName
            Log.d("BddTest", "=== START ${testNameRule.methodName} ===")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @org.junit.Before
    fun clearOsmDroidPrefs() {
        try {
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val prefs = context.getSharedPreferences("org.osmdroid", android.content.Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            
            // Also clear default shared preferences which Configuration.load uses by default
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
            
            org.osmdroid.config.Configuration.getInstance().load(context, androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @org.junit.Before
    fun setupDefaultCountry() {
        com.madanala.tern.utils.CountryUtils.setTestCountryCode("us")
    }

    fun scenario(name: String, block: () -> Unit) {
        ReportGenerator.logStep("SCENARIO", name)
        try {
            block()
            // Capture success screenshot at the end of scenario
            val result = ReportGenerator.captureScreenshot("success_${name.replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario Passed", "PASS", result?.path, result?.hash)
        } catch (e: Throwable) {
            // Capture failure screenshot
            val result = ReportGenerator.captureScreenshot("failure_${name.replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario Failed: ${e.message}", "FAIL", result?.path, result?.hash)
            
            // Print logcat to stdout for debugging
            println("=== LOGCAT DUMP ===")
            println(ReportGenerator.captureLogCat())
            println("===================")
            
            try {
                kotlinx.coroutines.runBlocking {
                    val report = com.madanala.tern.utils.PerformanceDebugger.getPerformanceReport()
                    println("=== PERFORMANCE REPORT ===")
                    println(report)
                    println("==========================")
                }
            } catch (e: Exception) {
                println("Failed to get performance report: $e")
            }
            
            throw e
        } finally {
            val logCatOutput = ReportGenerator.captureLogCat()
            // Finish scenario but don't generate report yet
            ReportGenerator.finishScenario(name, logCatOutput)
        }
    }

    @org.junit.After
    fun generateReport() {
        // Reset CountryUtils mock
        com.madanala.tern.utils.CountryUtils.setTestCountryCode(null)

        // Print Performance Report
        try {
            kotlinx.coroutines.runBlocking {
                val report = com.madanala.tern.utils.PerformanceDebugger.getPerformanceReport()
                println("=== PERFORMANCE REPORT (FINAL) ===")
                println(report)
                println("==================================")
            }
        } catch (e: Exception) {
            println("Failed to get performance report: $e")
        }
        ReportGenerator.generateFinalReport(testNameRule.methodName)
        Log.d("BddTest", "=== END ${testNameRule.methodName} ===")
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

    fun story(description: String, block: () -> Unit) {
        ReportGenerator.logStep("STORY", description)
        block()
    }
}
