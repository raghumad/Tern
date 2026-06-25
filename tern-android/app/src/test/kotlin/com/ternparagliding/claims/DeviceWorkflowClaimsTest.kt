package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.device.ConnectionEvent
import com.ternparagliding.device.ConnectionLog
import com.ternparagliding.device.ConnectionSupervisor
import com.ternparagliding.device.ConnectionSupervisor.step
import com.ternparagliding.device.DeviceCodec
import com.ternparagliding.device.DeviceCommand
import com.ternparagliding.device.DeviceEvent
import com.ternparagliding.device.DeviceRegistry
import com.ternparagliding.device.DeviceType
import com.ternparagliding.device.DropReason
import com.ternparagliding.device.LinkState
import com.ternparagliding.device.RememberedDevice
import com.ternparagliding.device.ScanCandidate
import com.ternparagliding.device.SupervisorState
import com.ternparagliding.device.sortByProximity
import org.junit.Test

/**
 * Claim **K7 · unified BLE device workflow (slice 1, JVM core).** One flow for every device,
 * with a self-healing connection that **never needs intervention** — see
 * `docs/design/ble-device-workflow.md`. These pin the decisions that matter for the pilot:
 * a dropped link heals itself (and is *visible* in the log), recovery is fast in flight but
 * battery-kind when the device is gone, "which one is mine" is the strongest signal, and the
 * remembered list survives a restart with pauses honoured.
 */
class DeviceWorkflowClaimsTest {

    // ── Supervisor: the happy path and the self-heal ────────────────────────────

    @Test
    fun `launch to linked - scan, find, connect`() {
        var s = SupervisorState()
        step(s, DeviceEvent.Resume, 0).let {
            assertThat(it.state.link).isEqualTo(LinkState.SCANNING)
            assertThat(it.command).isEqualTo(DeviceCommand.StartScan)
            assertThat(it.log?.kind).isEqualTo(ConnectionEvent.Kind.SCANNING)
            s = it.state
        }
        step(s, DeviceEvent.Sighted(-48), 100).let {
            assertThat(it.state.link).isEqualTo(LinkState.CONNECTING)
            assertThat(it.command).isEqualTo(DeviceCommand.Connect)
            s = it.state
        }
        step(s, DeviceEvent.Connected, 2_100).let {
            assertThat(it.state.link).isEqualTo(LinkState.CONNECTED)
            assertThat(it.log?.kind).isEqualTo(ConnectionEvent.Kind.LINKED)
            assertThat(it.log?.outageMs).isNull() // first connect, no prior outage
        }
    }

    @Test
    fun `a dropped link heals itself with no human action, and the outage is logged`() {
        val log = ConnectionLog()
        var s = SupervisorState(link = LinkState.CONNECTED, lastSeenMs = 2_100)

        // Drop mid-flight at t=10s.
        step(s, DeviceEvent.Disconnected(DropReason.LINK_LOST), 10_000).let {
            assertThat(it.state.link).isEqualTo(LinkState.SCANNING)   // immediately healing
            assertThat(it.command).isEqualTo(DeviceCommand.StartScan)
            assertThat(it.log!!.kind).isEqualTo(ConnectionEvent.Kind.DROPPED)
            assertThat(it.log!!.reason).isEqualTo(DropReason.LINK_LOST)
            it.log?.let(log::add); s = it.state
        }
        // Re-sighted, reconnect, relinked at t=13s → a 3 s outage recorded.
        s = step(s, DeviceEvent.Sighted(-55), 11_000).state
        step(s, DeviceEvent.Connected, 13_000).let {
            assertThat(it.state.link).isEqualTo(LinkState.CONNECTED)
            assertThat(it.log!!.kind).isEqualTo(ConnectionEvent.Kind.LINKED)
            assertThat(it.log!!.outageMs).isEqualTo(3_000)
            it.log?.let(log::add)
        }
        // The pilot can see exactly that it dropped and healed.
        assertThat(log.entries().map { it.kind })
            .containsExactly(ConnectionEvent.Kind.DROPPED, ConnectionEvent.Kind.LINKED).inOrder()
    }

    @Test
    fun `the heal loop only ever issues automatic commands - never asks the pilot`() {
        // Walk a full drop→heal cycle and assert every command is one the app executes itself.
        var s = SupervisorState(link = LinkState.CONNECTED, lastSeenMs = 0)
        val events = listOf(
            DeviceEvent.Disconnected(DropReason.GATT_ERROR),
            DeviceEvent.ScanTimeout,            // still searching
            DeviceEvent.Sighted(-60),
            DeviceEvent.ConnectFailed(DropReason.GATT_ERROR), // GATT-133 flake
            DeviceEvent.Sighted(-58),
            DeviceEvent.Connected,
        )
        for ((i, e) in events.withIndex()) {
            val t = step(s, e, (i + 1) * 1_000L)
            // Every command is one the app executes itself — never a "tap to reconnect".
            val automatic = t.command is DeviceCommand.StartScan || t.command is DeviceCommand.Connect ||
                t.command is DeviceCommand.Idle || t.command is DeviceCommand.Disconnect ||
                t.command is DeviceCommand.ScheduleRetry
            assertThat(automatic).isTrue()
            s = t.state
        }
        assertThat(s.link).isEqualTo(LinkState.CONNECTED) // it got back on its own
    }

    @Test
    fun `pause is the only intentional stop, and resume restarts the search`() {
        val connected = SupervisorState(link = LinkState.CONNECTED)
        val paused = step(connected, DeviceEvent.Pause, 0)
        assertThat(paused.state.link).isEqualTo(LinkState.OFF)
        assertThat(paused.command).isEqualTo(DeviceCommand.Disconnect)
        assertThat(paused.log?.kind).isEqualTo(ConnectionEvent.Kind.PAUSED)

        val resumed = step(paused.state, DeviceEvent.Resume, 10)
        assertThat(resumed.state.link).isEqualTo(LinkState.SCANNING)
        assertThat(resumed.command).isEqualTo(DeviceCommand.StartScan)
    }

    @Test
    fun `bluetooth off parks the link and bluetooth on resumes it automatically`() {
        val connected = SupervisorState(link = LinkState.CONNECTED)
        val off = step(connected, DeviceEvent.BluetoothOff, 5_000)
        assertThat(off.state.link).isEqualTo(LinkState.OFF)
        assertThat(off.log?.kind).isEqualTo(ConnectionEvent.Kind.BLUETOOTH_OFF)
        assertThat(off.state.droppedAtMs).isEqualTo(5_000) // outage clock started

        val on = step(off.state, DeviceEvent.BluetoothOn, 6_000)
        assertThat(on.state.link).isEqualTo(LinkState.SCANNING) // no pilot action needed
        // And the eventual relink reports the full outage across the BT-off gap.
        val sighted = step(on.state, DeviceEvent.Sighted(-50), 6_500).state
        val linked = step(sighted, DeviceEvent.Connected, 7_000)
        assertThat(linked.log?.outageMs).isEqualTo(2_000)
    }

    @Test
    fun `out of range logs once then keeps searching quietly`() {
        var s = step(SupervisorState(), DeviceEvent.Resume, 0).state // SCANNING
        val first = step(s, DeviceEvent.ScanTimeout, 5_000)
        assertThat(first.log?.kind).isEqualTo(ConnectionEvent.Kind.OUT_OF_RANGE)
        assertThat(first.command).isInstanceOf(DeviceCommand.ScheduleRetry::class.java)
        s = first.state
        val second = step(s, DeviceEvent.ScanTimeout, 10_000)
        assertThat(second.log).isNull() // doesn't spam the log
        assertThat(second.command).isInstanceOf(DeviceCommand.ScheduleRetry::class.java)
    }

    // ── Adaptive backoff ────────────────────────────────────────────────────────

    @Test
    fun `backoff is fast when recently seen, grows while absent, snaps back, and is capped`() {
        val b = ConnectionSupervisor
        // Recently seen ⇒ always fast, even after many retries (mid-thermal recovery).
        assertThat(b.backoffMs(retries = 5, lastSeenMs = 9_000, nowMs = 10_000)).isEqualTo(b.FAST_RETRY_MS)
        // Absent ⇒ exponential growth.
        assertThat(b.backoffMs(retries = 1, lastSeenMs = null, nowMs = 999_000)).isEqualTo(4_000)
        assertThat(b.backoffMs(retries = 2, lastSeenMs = null, nowMs = 999_000)).isEqualTo(8_000)
        assertThat(b.backoffMs(retries = 3, lastSeenMs = null, nowMs = 999_000))
            .isGreaterThan(b.backoffMs(retries = 2, lastSeenMs = null, nowMs = 999_000))
        // Capped, never unbounded.
        assertThat(b.backoffMs(retries = 99, lastSeenMs = null, nowMs = 999_000)).isEqualTo(b.MAX_RETRY_MS)
        // A fresh sighting (lastSeenMs = now) snaps back to fast regardless of retry count.
        assertThat(b.backoffMs(retries = 99, lastSeenMs = 1_000_000, nowMs = 1_000_000)).isEqualTo(b.FAST_RETRY_MS)
    }

    // ── Disambiguation: "which one is mine" ─────────────────────────────────────

    @Test
    fun `scan results sort strongest-first so the pilot's device is on top`() {
        val mine = ScanCandidate("XC Tracer Mini II", "AA:BB", -48, DeviceType.VARIO)
        val theirs = ScanCandidate("XC Tracer Mini II", "CC:DD", -82, DeviceType.VARIO)
        val board = ScanCandidate("Mezulla 4a31", "EE:FF", -61, DeviceType.MEZULLA)
        val sorted = sortByProximity(listOf(theirs, board, mine))
        assertThat(sorted.map { it.mac }).containsExactly("AA:BB", "EE:FF", "CC:DD").inOrder()
    }

    // ── Registry + persistence ──────────────────────────────────────────────────

    private val vario = RememberedDevice("v1", DeviceType.VARIO, "XC Tracer Mini II", "AA:BB:CC")
    private val board = RememberedDevice("m1", DeviceType.MEZULLA, "Mezulla 4a31", "EE:FF:00", claimToken = "tok123")

    @Test
    fun `registry adds, dedups by MAC, forgets, and pauses`() {
        var reg = DeviceRegistry().add(vario).add(board)
        assertThat(reg.devices).hasSize(2)
        // Re-adding the same physical device (same MAC) replaces, never duplicates.
        reg = reg.add(vario.copy(id = "v1b", displayName = "XC Tracer (renamed)"))
        assertThat(reg.devices.filter { it.mac.equals("AA:BB:CC", true) }).hasSize(1)
        assertThat(reg.find("v1b")?.displayName).isEqualTo("XC Tracer (renamed)")
        // Pause is honoured by active().
        reg = reg.setPaused("m1", true)
        assertThat(reg.active().map { it.id }).containsExactly("v1b")
        // Forget removes it.
        reg = reg.forget("v1b")
        assertThat(reg.devices.map { it.id }).containsExactly("m1")
    }

    @Test
    fun `the remembered list round-trips through the codec, lossless`() {
        val devices = listOf(vario, board.copy(paused = true))
        val restored = DeviceCodec.decode(DeviceCodec.encode(devices))
        assertThat(restored).isEqualTo(devices) // incl. claim token + paused flag
        // Empty/garbage decode gracefully (no crash on a fresh install or corrupt prefs).
        assertThat(DeviceCodec.decode(null)).isEmpty()
        assertThat(DeviceCodec.decode("")).isEmpty()
        assertThat(DeviceCodec.decode("not json")).isEmpty()
    }

    // ── Connection log ──────────────────────────────────────────────────────────

    @Test
    fun `the connection log is a bounded ring buffer`() {
        val log = ConnectionLog(capacity = 3)
        repeat(5) { i -> log.add(ConnectionEvent(i.toLong(), ConnectionEvent.Kind.SCANNING)) }
        assertThat(log.size).isEqualTo(3)
        assertThat(log.entries().map { it.atMs }).containsExactly(2L, 3L, 4L).inOrder() // oldest dropped
        assertThat(log.latest?.atMs).isEqualTo(4L)
    }
}
