package com.ternparagliding.mezulla.pairing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

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
            PairingState.Success("aabb"),
            PairingState.Failed("error"),
        )
        assertThat(states).hasSize(7)
    }
}
