package com.madanala.tern.utils

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

open class BddTest {

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
            ReportGenerator.generateReport(name.replace(" ", "_"))
        }
    }

    fun given(description: String, block: () -> Unit) {
        step("GIVEN", description, block)
    }

    fun `when`(description: String, block: () -> Unit) {
        step("WHEN", description, block)
    }

    fun then(description: String, block: () -> Unit) {
        step("THEN", description, block)
    }

    private fun step(type: String, description: String, block: () -> Unit) {
        try {
            block()
            ReportGenerator.logStep(type, description, "PASS")
        } catch (e: Throwable) {
            val screenshot = ReportGenerator.captureScreenshot("failure_${type}_${description.take(10).replace(" ", "_")}")
            ReportGenerator.logStep(type, description, "FAIL", screenshot)
            throw e
        }
    }
}
