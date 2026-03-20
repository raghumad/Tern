package com.madanala.tern.utils

import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

/**
 * Marks a test as Unstable or Flaky.
 * These tests should be executed after all stable tests have completed,
 * to prevent crashes or side effects from affecting the main test suite.
 */
@kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Unstable(val reason: String = "")
