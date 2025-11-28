package com.madanala.tern.utils

/**
 * Base class for local unit tests using BDD style.
 * Mimics the API of the instrumentation BddTest but for JVM-only tests.
 * Does NOT support screenshots or Android-specific reporting.
 */
open class LocalBddTest {

    fun scenario(name: String, block: () -> Unit) {
        println("\nSCENARIO: $name")
        try {
            block()
            println("RESULT: Scenario Passed")
        } catch (e: Throwable) {
            println("RESULT: Scenario Failed: ${e.message}")
            throw e
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
            println("  $type: $description [PASS]")
        } catch (e: Throwable) {
            println("  $type: $description [FAIL]")
            throw e
        }
    }
}
