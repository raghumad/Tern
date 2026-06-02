package com.ternparagliding.utils

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Marks a test as part of the **BLE link-reliability track** — real
 * pilot-safety scenarios that exercise the phone ↔ Mezulla board BLE link
 * (connection survival, reconnect, handshake resilience, link-state
 * visibility). These require real hardware (phone + paired board) and are
 * never expected to pass on an emulator.
 *
 * This is deliberately NOT `@Ignore`. `@Ignore` reads as "dead/abandoned";
 * these scenarios are a living roadmap. A scenario that can't run yet
 * declares *why* via [blockedOn] — it is reported as **skipped with a
 * reason** (a JUnit assumption failure), not silently ignored and not a
 * red failure.
 *
 * @param blockedOn If non-blank, the concrete capability this scenario is
 *   waiting on (a firmware command, a host sidecar, a foreground service,
 *   etc.). Blank means the scenario is runnable on real hardware today.
 *
 * To force-run blocked scenarios (e.g. once their dependency lands):
 *   `-e runBlockedBle true`
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ble(val blockedOn: String = "")

/**
 * Honors [Ble.blockedOn]: a scenario blocked on an unbuilt capability is
 * skipped (assumption failure → reported "skipped: <reason>") instead of
 * running its stub and failing red. Pass `-e runBlockedBle true` to run
 * blocked scenarios anyway. Method-level [Ble] wins over class-level.
 */
class BleTestRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val ble = description.getAnnotation(Ble::class.java)
                    ?: description.testClass?.getAnnotation(Ble::class.java)
                val blockedOn = ble?.blockedOn?.takeIf { it.isNotBlank() }
                if (blockedOn != null && !forceRunBlocked()) {
                    Assume.assumeTrue(
                        "BLE scenario blocked on: $blockedOn " +
                            "(pass -e runBlockedBle true to force)",
                        false,
                    )
                }
                base.evaluate()
            }
        }

    private fun forceRunBlocked(): Boolean =
        try {
            InstrumentationRegistry.getArguments()
                .getString("runBlockedBle") == "true"
        } catch (_: Exception) {
            false
        }
}
