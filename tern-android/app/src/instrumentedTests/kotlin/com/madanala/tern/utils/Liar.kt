package com.madanala.tern.utils

/**
 * Marks a test whose Gherkin describes a real pilot behavior worth
 * validating, but whose assertion body was gutted because the original
 * implementation was dishonest (existence checks, tautologies, or
 * no assertions at all).
 *
 * The Gherkin is preserved as a specification. The test body is a
 * skeleton that needs real assertions written against real behavior.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Liar(val reason: String = "")
