package com.ternparagliding.test

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.TernParaglidingActivity
import com.ternparagliding.mezulla.pairing.PairingState
import com.ternparagliding.mezulla.pairing.TernPairLink
import com.ternparagliding.utils.MapVisualTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepLinkPairingTest : MapVisualTest() {

    @Test
    fun pilot_scans_qr_and_tern_opens_via_deep_link() {
        scenario("Pilot scans Mezulla QR and Tern opens via deep link") {

            given("a valid tern:// pairing URI from a Mezulla board QR") {
                val link = TernPairLink.parse("tern://p?n=4a312aaa&t=e7f3a1b2c4d5e6f78901a2b3c4d5e6f7")
                assert(link != null) { "TernPairLink.parse returned null for valid URI" }
                assert(link!!.nodeIdHex == "4a312aaa") { "Node ID mismatch" }
                assert(link.nodeNumber == 0x4a312aaaL) { "Node number mismatch" }
            }

            and("the parser rejects malformed URIs") {
                assert(TernPairLink.parse("https://p?n=4a312aaa&t=abcdef01") == null) { "Should reject https scheme" }
                assert(TernPairLink.parse("tern://pair?n=4a312aaa&t=abcdef01") == null) { "Should reject wrong host" }
                assert(TernPairLink.parse("tern://p?t=abcdef01") == null) { "Should reject missing node" }
                assert(TernPairLink.parse("tern://p?n=4a312aaa") == null) { "Should reject missing token" }
                assert(TernPairLink.parse("tern://p?n=ZZZZ&t=abcdef01") == null) { "Should reject non-hex" }
            }

            `when`("the activity receives a tern:// deep link intent") {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("tern://p?n=4a312aaa&t=e7f3a1b2c4d5e6f78901a2b3c4d5e6f7")
                }
                // Send the deep link to the already-running activity
                composeTestRule.runOnUiThread {
                    composeTestRule.activity.onNewIntent(intent)
                }
                composeTestRule.waitForIdle()
            }

            then("the pairing orchestrator receives and parses the link") {
                val state = composeTestRule.activity.pairingOrchestrator.state.value
                assert(state is PairingState.Received) {
                    "Expected PairingState.Received but got $state"
                }
                val received = state as PairingState.Received
                assert(received.link.nodeIdHex == "4a312aaa") {
                    "Node ID mismatch in orchestrator state"
                }
            }

            and("the map remains visible (graceful degradation)") {
                composeTestRule.onNodeWithTag("map_view").assertExists()
            }
        }
    }
}
