package com.ternparagliding.utils

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

import com.ternparagliding.BaseUITest
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
            val className = this.javaClass.simpleName
            ReportGenerator.currentTestClass = className
            ReportGenerator.currentTestName = testNameRule.methodName
            Log.d("BddTest", "=== START ${testNameRule.methodName} ===")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    /**
     * Run a BDD scenario. When [recordVideo] is true, the device
     * screen is recorded for the duration of the scenario block.
     * Videos are saved to `/sdcard/tern-tests/` and can be pulled
     * with `adb pull /sdcard/tern-tests/` after the run.
     *
     * Video recording is opt-in per scenario (default off) because
     * it slows tests and produces large files.
     */
    fun scenario(name: String, recordVideo: Boolean = true, block: () -> Unit) {
        ReportGenerator.logStep("SCENARIO", name)
        if (recordVideo) {
            VideoHelper.startRecording(name.replace(" ", "_"))
        }
        try {
            block()
            // Capture success screenshot at the end of scenario
            val result = ReportGenerator.captureScreenshot("success_${name.replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario completed without assertion failure", "PASS", result?.path, result?.hash)
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
                    val report = com.ternparagliding.utils.PerformanceDebugger.getPerformanceReport()
                    println("=== PERFORMANCE REPORT ===")
                    println(report)
                    println("==========================")
                }
            } catch (e: Exception) {
                println("Failed to get performance report: $e")
            }

            throw e
        } finally {
            if (recordVideo) {
                VideoHelper.stopRecording()
            }
            val logCatOutput = ReportGenerator.captureLogCat()
            // Finish scenario but don't generate report yet
            ReportGenerator.finishScenario(name, logCatOutput)
        }
    }

    @org.junit.After
    fun generateReport() {
        // Reset CountryUtils mock
        com.ternparagliding.utils.CountryUtils.setTestCountryCode(null)

        // Print Performance Report
        try {
            kotlinx.coroutines.runBlocking {
                val report = com.ternparagliding.utils.PerformanceDebugger.getPerformanceReport()
                println("=== PERFORMANCE REPORT (FINAL) ===")
                println(report)
                println("==================================")
            }
        } catch (e: Exception) {
            println("Failed to get performance report: $e")
        }
        val className = this.javaClass.simpleName
        ReportGenerator.generateFinalReport(className, testNameRule.methodName)
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
                // [STABILITY FIX] Basic delay for non-Compose BDD tests
                Thread.sleep(300)
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
