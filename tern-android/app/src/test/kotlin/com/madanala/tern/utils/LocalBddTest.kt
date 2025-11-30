package com.madanala.tern.utils

/**
 * Base class for local unit tests using BDD style.
 * Mimics the API of the instrumentation BddTest but for JVM-only tests.
 * Does NOT support screenshots or Android-specific reporting.
 */
open class LocalBddTest {

    @get:org.junit.Rule
    val testNameRule = org.junit.rules.TestName()

    fun scenario(name: String, block: () -> Unit) {
        LocalReportGenerator.logStep("SCENARIO", name)
        println("\nSCENARIO: $name")
        try {
            block()
            LocalReportGenerator.logStep("RESULT", "Scenario Passed", "PASS")
            println("RESULT: Scenario Passed")
        } catch (e: Throwable) {
            LocalReportGenerator.logStep("RESULT", "Scenario Failed: ${e.message}", "FAIL")
            println("RESULT: Scenario Failed: ${e.message}")
            throw e
        } finally {
            LocalReportGenerator.generateReport(testNameRule.methodName)
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

    fun and(description: String, block: () -> Unit) {
        step("AND", description, block)
    }

    private fun step(type: String, description: String, block: () -> Unit) {
        try {
            block()
            LocalReportGenerator.logStep(type, description, "PASS")
            println("  $type: $description [PASS]")
        } catch (e: Throwable) {
            LocalReportGenerator.logStep(type, description, "FAIL")
            println("  $type: $description [FAIL]")
            throw e
        }
    }
}
