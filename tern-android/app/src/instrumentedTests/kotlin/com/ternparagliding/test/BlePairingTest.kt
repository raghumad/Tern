package com.ternparagliding.test

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.TernParaglidingActivity
import com.ternparagliding.mezulla.pairing.PairingState
import com.ternparagliding.utils.MapVisualTest
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

    companion object {
        private const val EXPECTED_NODE = "4a312aaa"
        private const val BLE_TIMEOUT_MS = 30_000L
        // Read from docs/handoffs/mezulla-deeplink.txt at build time
        // via test instrumentation args, or fall back to this default.
        private const val DEFAULT_PAIR_URI = "tern://p?n=4a312aaa&t=69f5bbbf"

        private fun getPairUri(): String {
            return try {
                val args = androidx.test.platform.app.InstrumentationRegistry
                    .getArguments()
                val uri = args.getString("pairUri")
                if (!uri.isNullOrBlank()) uri else DEFAULT_PAIR_URI
            } catch (_: Exception) {
                DEFAULT_PAIR_URI
            }
        }
    }

    @Test
    fun pilot_pairs_with_mezulla_board_via_ble() {
        scenario("Pilot pairs with Mezulla board via BLE") {

            given("Tern is running and no board is paired") {
                val nodeId = composeTestRule.activity.pairingOrchestrator.getPairedNodeId()
                if (nodeId != null) {
                    composeTestRule.activity.pairingOrchestrator.forgetBoard()
                }
            }

            `when`("a tern:// deep link triggers the pairing flow") {
                val pairUri = getPairUri()
                android.util.Log.i("BlePairingTest", "Using pair URI: $pairUri")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(pairUri)
                }
                composeTestRule.runOnUiThread {
                    composeTestRule.activity.pairingOrchestrator.handleIntent(intent)
                }
            }

            then("the orchestrator scans, connects, and claims the board") {
                // Wait for pairing to complete or fail
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
                assert((finalState as PairingState.Success).nodeIdHex == EXPECTED_NODE) {
                    "Expected node $EXPECTED_NODE but got ${finalState.nodeIdHex}"
                }
            }

            and("the board ID is persisted") {
                val persisted = composeTestRule.activity.pairingOrchestrator.getPairedNodeId()
                assert(persisted == EXPECTED_NODE) {
                    "Expected persisted node $EXPECTED_NODE but got $persisted"
                }
            }

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
