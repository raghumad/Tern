package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.device.PairingEnvironment
import com.ternparagliding.device.PairingError
import com.ternparagliding.device.PairingFlow
import com.ternparagliding.device.PairingPhase
import com.ternparagliding.device.PairingReadiness
import com.ternparagliding.device.Precondition
import org.junit.Test

/**
 * Claim **K7 · add-a-device flow (slice 2 core).** Pairing must *just work*, and when it can't,
 * fail with one clear message + one clear fix — and **never** cascade (fix one thing, then fail
 * for a different reason). See `docs/design/ble-device-workflow.md`. These pin the structural
 * guarantee: every precondition is surfaced up front, a fix never reveals a fresh blocker, an
 * attempt only ever starts from an all-green gate, and we don't demand needless actions.
 */
class PairingFlowClaimsTest {

    private fun env(
        perm: Boolean = true, bt: Boolean = true, loc: Boolean = true, locReq: Boolean = true,
    ) = PairingEnvironment(perm, bt, loc, locReq)

    @Test
    fun `the readiness gate surfaces every blocker at once, not one at a time`() {
        val broken = env(perm = false, bt = false, loc = false, locReq = true)
        val phase = PairingFlow.begin(broken)
        assertThat(phase).isInstanceOf(PairingPhase.GetReady::class.java)
        // All three are shown from the start — location isn't hidden until Bluetooth is fixed.
        assertThat(PairingReadiness.unmet(broken)).containsExactly(
            Precondition.BLUETOOTH_PERMISSION, Precondition.BLUETOOTH_ON, Precondition.LOCATION_ON,
        ).inOrder()
    }

    @Test
    fun `fixing one precondition never reveals a new one - no cascade`() {
        // Walk the pilot fixing things in order; the unmet set only ever SHRINKS, and never
        // introduces a precondition that wasn't in the original list.
        val initial = PairingReadiness.unmet(env(perm = false, bt = false, loc = false)).toSet()

        val afterPerm = PairingReadiness.unmet(env(perm = true, bt = false, loc = false)).toSet()
        val afterBt = PairingReadiness.unmet(env(perm = true, bt = true, loc = false)).toSet()
        val afterLoc = PairingReadiness.unmet(env(perm = true, bt = true, loc = true)).toSet()

        assertThat(initial.containsAll(afterPerm)).isTrue()
        assertThat(afterPerm.containsAll(afterBt)).isTrue()
        assertThat(afterBt.containsAll(afterLoc)).isTrue()
        // Nothing new ever appears.
        assertThat(initial.containsAll(afterBt)).isTrue()
        // And once everything's fixed, re-checking proceeds to searching — not another blocker.
        assertThat(PairingFlow.recheck(env(perm = true, bt = true, loc = true)))
            .isEqualTo(PairingPhase.Searching)
    }

    @Test
    fun `a pairing attempt only ever starts from an all-green gate`() {
        // Any environment with an unmet precondition stays on the gate — never Searching, so a
        // scan/connect/claim can never fail for a precondition reason discovered mid-attempt.
        val blockedEnvs = listOf(
            env(perm = false), env(bt = false), env(loc = false, locReq = true),
            env(perm = false, bt = false), env(bt = false, loc = false),
        )
        blockedEnvs.forEach { e ->
            assertThat(PairingFlow.begin(e)).isInstanceOf(PairingPhase.GetReady::class.java)
            assertThat(PairingReadiness.isReady(e)).isFalse()
        }
    }

    @Test
    fun `location is not demanded when the platform does not need it`() {
        // API 31+ with neverForLocation: location off is fine — we don't nag for a needless action.
        val e = env(perm = true, bt = true, loc = false, locReq = false)
        assertThat(PairingReadiness.isReady(e)).isTrue()
        assertThat(PairingFlow.begin(e)).isEqualTo(PairingPhase.Searching)
        assertThat(PairingReadiness.checklist(e).map { it.precondition })
            .doesNotContain(Precondition.LOCATION_ON)
    }

    @Test
    fun `every precondition prompt is clear and distinct`() {
        val msgs = Precondition.entries.map { PairingReadiness.message(it) }
        msgs.forEach {
            assertThat(it.title).isNotEmpty()
            assertThat(it.body).isNotEmpty()
            assertThat(it.cta).isNotEmpty()
        }
        assertThat(msgs.map { it.title }.toSet()).hasSize(Precondition.entries.size) // no two say the same thing
    }

    @Test
    fun `every attempt error has a friendly message and one corrective action`() {
        PairingError.entries.forEach { err ->
            val m = PairingFlow.message(err, deviceName = "XC Tracer Mini II")
            assertThat(m.title).isNotEmpty()
            assertThat(m.body).isNotEmpty()
            assertThat(m.cta).isNotEmpty()
        }
        // Device-specific errors name the device so the message is unambiguous.
        assertThat(PairingFlow.message(PairingError.CONNECT_FAILED, "XC Tracer Mini II").body)
            .contains("XC Tracer Mini II")
        assertThat(PairingFlow.message(PairingError.CLAIM_ALREADY_OWNED, "Mezulla 4a31").body)
            .contains("Mezulla 4a31")
        // The "already owned" case tells the pilot the concrete fix (reset the board).
        assertThat(PairingFlow.message(PairingError.CLAIM_ALREADY_OWNED, "Mezulla 4a31").body.lowercase())
            .contains("reset")
    }
}
