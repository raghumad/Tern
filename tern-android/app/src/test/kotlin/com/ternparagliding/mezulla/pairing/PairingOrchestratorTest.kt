package com.ternparagliding.mezulla.pairing

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class PairingOrchestratorTest {

    @Test
    fun `TernPairLink parses valid deep link and extracts node number`() {
        val link = TernPairLink.parse("tern://p?n=4a312aaa&t=e7f3a1b2c4d5e6f78901a2b3c4d5e6f7")!!
        assertThat(link.nodeNumber).isEqualTo(0x4a312aaaL)
        assertThat(link.pairingToken).isEqualTo("e7f3a1b2c4d5e6f78901a2b3c4d5e6f7")
    }

    @Test
    fun `PairingState transitions from Idle to Received`() {
        val state: PairingState = PairingState.Idle
        assertThat(state).isInstanceOf(PairingState.Idle::class.java)

        val received = PairingState.Received(
            TernPairLink.parse("tern://p?n=4a312aaa&t=abcdef01")!!
        )
        assertThat(received.link.nodeIdHex).isEqualTo("4a312aaa")
    }

    @Test
    fun `PairingState Failed carries reason`() {
        val failed = PairingState.Failed("Token mismatch — try scanning again")
        assertThat(failed.reason).contains("Token mismatch")
    }

    @Test
    fun `PairingState covers full flow`() {
        val states = listOf(
            PairingState.Idle,
            PairingState.Received(TernPairLink("aabb", "ccdd")),
            PairingState.Scanning("aabb"),
            PairingState.Connecting("aabb"),
            PairingState.Claiming("aabb"),
            PairingState.EstablishingLink("aabb", "AA:BB:CC:DD:EE:FF"),
            PairingState.Success("aabb", "AA:BB:CC:DD:EE:FF"),
            PairingState.Failed("error"),
        )
        assertThat(states).hasSize(8)
    }

    @Test
    fun `confirmLinkEstablished transitions EstablishingLink to Success`() {
        val orchestrator = newOrchestratorInEstablishingLink("aabb", "AA:BB:CC:DD:EE:FF")

        orchestrator.confirmLinkEstablished()

        val state = orchestrator.state.value
        assertThat(state).isInstanceOf(PairingState.Success::class.java)
        val success = state as PairingState.Success
        assertThat(success.nodeIdHex).isEqualTo("aabb")
        assertThat(success.deviceAddress).isEqualTo("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `confirmLinkFailed transitions EstablishingLink to Failed with reason`() {
        val orchestrator = newOrchestratorInEstablishingLink("aabb", "AA:BB:CC:DD:EE:FF")

        orchestrator.confirmLinkFailed("Could not establish connection — try rebooting the board")

        val state = orchestrator.state.value
        assertThat(state).isInstanceOf(PairingState.Failed::class.java)
        assertThat((state as PairingState.Failed).reason).contains("rebooting")
    }

    @Test
    fun `confirmLinkEstablished is a no-op when state is not EstablishingLink`() {
        // Default state is Idle. Calling confirmLinkEstablished should not
        // promote it to Success — that would be a lie about a connection
        // that never went through the pairing flow.
        val orchestrator = PairingOrchestrator(mockContextWithEmptyPrefs())

        orchestrator.confirmLinkEstablished()

        assertThat(orchestrator.state.value).isEqualTo(PairingState.Idle)
    }

    @Test
    fun `confirmLinkFailed is a no-op when state is not EstablishingLink`() {
        // A later transient link drop (e.g. board off in flight) should
        // not get re-cast as a pairing failure.
        val orchestrator = PairingOrchestrator(mockContextWithEmptyPrefs())

        orchestrator.confirmLinkFailed("ignored")

        assertThat(orchestrator.state.value).isEqualTo(PairingState.Idle)
    }

    @Test
    fun `confirmLinkEstablished is a no-op after Failed`() {
        // If the timeout already fired Failed, a late UP shouldn't quietly
        // resurrect Success and confuse the UI.
        val orchestrator = newOrchestratorInEstablishingLink("aabb", "AA:BB:CC:DD:EE:FF")
        orchestrator.confirmLinkFailed("timeout")
        assertThat(orchestrator.state.value).isInstanceOf(PairingState.Failed::class.java)

        orchestrator.confirmLinkEstablished()

        assertThat(orchestrator.state.value).isInstanceOf(PairingState.Failed::class.java)
    }

    /**
     * Build a PairingOrchestrator and drive its state into EstablishingLink
     * via reflection on the private MutableStateFlow. We do not exercise
     * the full claim flow here because that requires the Bluetooth stack;
     * for these tests we only care about the confirm* transitions.
     */
    private fun newOrchestratorInEstablishingLink(
        nodeIdHex: String,
        deviceAddress: String,
    ): PairingOrchestrator {
        val orchestrator = PairingOrchestrator(mockContextWithEmptyPrefs())
        val stateField = PairingOrchestrator::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = stateField.get(orchestrator) as kotlinx.coroutines.flow.MutableStateFlow<PairingState>
        flow.value = PairingState.EstablishingLink(nodeIdHex, deviceAddress)
        return orchestrator
    }

    /**
     * The orchestrator's constructor doesn't touch Context, and the
     * confirm* methods we exercise here don't either. A bare mock is
     * enough; we never call into the SharedPreferences-backed methods.
     */
    private fun mockContextWithEmptyPrefs(): Context = mock(Context::class.java)
}
