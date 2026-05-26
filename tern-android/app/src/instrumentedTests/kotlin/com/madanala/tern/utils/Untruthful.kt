package com.madanala.tern.utils

/**
 * Marks a test whose assertions do not match what its name or BDD step
 * descriptions claim to validate. These tests compile and pass but
 * prove nothing about the behavior they describe.
 *
 * Tagged tests should be rewritten with honest assertions once the
 * test framework is solid. Until then they run but their results
 * carry no weight.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Untruthful(val reason: String = "")
