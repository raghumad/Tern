package com.ternparagliding.test

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.TernParaglidingActivity
import com.ternparagliding.mezulla.pairing.PairingState
import com.ternparagliding.utils.MapVisualTest
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.rule.GrantPermissionRule

/**
 * BLE pairing integration test. Runs on a REAL PHONE with a real
 * Mezulla board nearby. NOT for the managed device emulator.
 *
 * Run with:
 *   ./gradlew connectedDebugAndroidTest --tests "com.ternparagliding.test.BlePairingTest"
 *
 * Prerequisites:
 *   - Real phone connected via adb
 *   - Mezulla board powered on, unclaimed, showing QR
 *   - BLE permissions granted on phone
 *   - Board within BLE range (~10m)
 */
@RunWith(AndroidJUnit4::class)
class BlePairingTest : MapVisualTest() {

    @get:Rule
    val blePermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Before
    fun requireRealHardware() {
        Assume.assumeFalse(
            "Skipping BlePairingTest: requires real phone + Mezulla board, not emulator",
            isEmulator(),
        )
    }

    private fun isEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT
        val model = android.os.Build.MODEL
        return fp.startsWith("generic") || fp.startsWith("unknown") ||
            fp.contains("emulator") || fp.contains("vbox") ||
            model.contains("Emulator") || model.contains("Android SDK") ||
            android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.HARDWARE.contains("goldfish")
    }

    companion object {
        // Reach Success now requires the post-claim persistent BLE link
        // to actually come up (board reboots into paired-only mode, then
        // re-advertises, then GATT handshake). Observed worst case is
        // ~33s; give 90s so an intermittent re-scan doesn't make this
        // test flaky.
        private const val BLE_TIMEOUT_MS = 90_000L

        private fun getPairUri(): String {
            return try {
                val args = androidx.test.platform.app.InstrumentationRegistry
                    .getArguments()
                val uri = args.getString("pairUri")
                if (!uri.isNullOrBlank()) uri else error("pairUri instrumentation arg required")
            } catch (_: Exception) {
                error("pairUri instrumentation arg required — run via pairing-test-cycle.sh")
            }
        }

        private fun extractNodeFromUri(uri: String): String {
            val parsed = Uri.parse(uri)
            return parsed.getQueryParameter("n")
                ?: error("pairUri missing 'n' parameter: $uri")
        }
    }

    @Test
    fun pilot_pairs_with_mezulla_board_via_ble() {
        val pairUri = getPairUri()
        val expectedNode = extractNodeFromUri(pairUri)

        // Pilot-visible Gherkin (Option A, post-deep-research 2026-05-29):
        // silent BLE bond is not achievable from a non-privileged Android
        // app per BLE Core Spec Vol 3 Part H + AOSP source. The pilot's
        // expected flow is ONE pair-dialog tap (the PIN is shown by Tern
        // and on the OLED so the dialog is unsurprising), then silent
        // reconnects forever after.
        //
        //   Scenario: Pilot pairs with Mezulla board via BLE
        //     Given Tern is running and no board is paired
        //     And the Mezulla board is unclaimed, showing its QR + PIN
        //     When the pilot scans the QR with the phone camera
        //     Then Tern shows the pair-priming screen with the matching PIN
        //     When the Android system pair dialog appears
        //         (UI Automator simulates the pilot tap so the test
        //          is unattended; production: pilot taps the same button)
        //     Then the persistent BLE link reaches PairingState.Success
        //         with the board's node ID from the QR
        //     And the board ID is persisted to disk so it survives a restart
        //     And Settings shows the paired board

        // Watcher thread: clicks "Pair" on Android's system pair dialog
        // whenever it appears. Runs throughout the scenario so it
        // catches the dialog at whatever moment it pops.
        val pairTapper = startPairDialogAutoTapper()

        scenario("Pilot pairs with Mezulla board via BLE") {

            given("Tern is running and no board is paired") {
                val nodeId = composeTestRule.activity.pairingOrchestrator.getPairedNodeId()
                if (nodeId != null) {
                    composeTestRule.activity.pairingOrchestrator.forgetBoard()
                }
            }

            `when`("a tern:// deep link triggers the pairing flow") {
                android.util.Log.i("BlePairingTest", "Using pair URI: $pairUri (node=$expectedNode)")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(pairUri)
                }
                composeTestRule.runOnUiThread {
                    composeTestRule.activity.pairingOrchestrator.handleIntent(intent)
                }
            }

            then("the persistent BLE link reaches Success with the board's node ID") {
                val startTime = System.currentTimeMillis()
                var finalState: PairingState = PairingState.Idle

                while (System.currentTimeMillis() - startTime < BLE_TIMEOUT_MS) {
                    finalState = composeTestRule.activity.pairingOrchestrator.state.value
                    if (finalState is PairingState.Success || finalState is PairingState.Failed) {
                        break
                    }
                    Thread.sleep(500)
                }

                android.util.Log.i("BlePairingTest", "Final state: $finalState")

                assert(finalState is PairingState.Success) {
                    "Expected PairingState.Success but got: $finalState"
                }
                assert((finalState as PairingState.Success).nodeIdHex == expectedNode) {
                    "Expected node $expectedNode but got ${finalState.nodeIdHex}"
                }
            }

            and("the board ID is persisted") {
                val persisted = composeTestRule.activity.pairingOrchestrator.getPairedNodeId()
                assert(persisted == expectedNode) {
                    "Expected persisted node $expectedNode but got $persisted"
                }
            }

            // Stop the pair-dialog watcher now that pairing is settled.
            pairTapper.invoke()

            and("Settings shows the paired board") {
                // Wait for activity to settle after BLE operations
                Thread.sleep(2000)
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithContentDescription("Settings").performClick()
                composeTestRule.waitForIdle()
                Thread.sleep(1000)
                composeTestRule.onNodeWithText("Mezulla").assertIsDisplayed()
                composeTestRule.onNodeWithTag("btn_forget_board").assertExists()
            }
        }
    }
}
