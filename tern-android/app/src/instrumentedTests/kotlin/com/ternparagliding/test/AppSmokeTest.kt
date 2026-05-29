package com.ternparagliding.test

import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.TernParaglidingActivity
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.BufferedReader

/**
 * Smoke test: launch the app, let it run for a few seconds, and assert
 * no MapLibre runtime errors in logcat. Catches type mismatches in
 * expressions, missing images, style property errors, etc. that only
 * manifest at render time on a real MapLibre instance.
 */
class AppSmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(TernParaglidingActivity::class.java)

    @Test
    fun app_launches_without_maplibre_errors() {
        // Clear logcat, then let the app run
        Runtime.getRuntime().exec("logcat -c").waitFor()

        Thread.sleep(5_000)

        // Capture logcat
        val process = Runtime.getRuntime().exec("logcat -d -v brief")
        val errors = process.inputStream.bufferedReader().useLines { lines ->
            lines.filter { line ->
                line.contains("Mbgl") && line.contains("Error")
            }.toList()
        }

        if (errors.isNotEmpty()) {
            val summary = errors.take(5).joinToString("\n")
            fail("MapLibre errors detected (${errors.size} total):\n$summary")
        }
    }
}
