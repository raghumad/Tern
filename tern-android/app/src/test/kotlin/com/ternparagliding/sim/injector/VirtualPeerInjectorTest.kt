package com.ternparagliding.sim.injector

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.connection.ble.FakeBleTransport
import com.ternparagliding.sim.igc.IgcFix
import com.ternparagliding.sim.igc.IgcFlight
import com.ternparagliding.sim.propagation.LossReason
import com.ternparagliding.sim.propagation.PilotEndpoint
import com.ternparagliding.sim.propagation.PropagationModel
import com.ternparagliding.sim.propagation.PropagationOutcome
import com.ternparagliding.sim.propagation.TxPower
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.Scenario
import com.ternparagliding.sim.swarm.ScenarioPilot
import com.ternparagliding.sim.swarm.SwarmPlayback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [VirtualPeerInjector].
 *
 * The injector is the peer-position arm of the Aravis replay test bench.
 * It pushes synthetic peer updates straight into [BleConnection]'s events
 * flow (`injectMeshEventForTest` — the firmware-loopback bypass; real
 * Meshtastic zeroes `from` on phone-sent packets and never echoes them
 * back, so the original "encode ToRadio and round-trip" plan can't work on
 * hardware — see [VirtualPeerInjector] KDoc). Tests exercise three
 * properties, all asserted on the surfaced [MeshEvent]s rather than wire
 * bytes:
 *
 *  1. **Cadence + correctness:** with two virtual peers, `delay(N)` ticks
 *     advance virtual time and emit one [MeshEvent.PeerPositionUpdate] per
 *     peer per tick, with the right node number and a fix matching the IGC.
 *  2. **Propagation gating:** with an always-Lost model, nothing surfaces
 *     — the simulator stays silent about packets that did not reach us.
 *  3. **Wire independence:** the injector bypasses the BLE transport, so a
 *     transport that rejects every write has no bearing on delivery, and
 *     no link-UP handshake is required.
 *
 * Coroutine plumbing: `runTest` virtualises [kotlinx.coroutines.delay], so
 * the injector's wall-clock cadence is driven by [advanceTimeBy] without
 * real sleeps. [collectEvents] subscribes to the events flow before the
 * injector starts (the flow is replay-0, so a late subscriber misses
 * early emissions).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualPeerInjectorTest {

    // -- Fixtures -------------------------------------------------------

    private val playbackStart: Instant = Instant.parse("2026-04-25T08:00:00Z")

    private val antoineId = PilotId("antoine")
    private val benId = PilotId("ben")
    private val dutId = PilotId("dut")

    private val antoineNodeNumber = 0xA1B2C3D4L
    private val benNodeNumber = 0xBEEFCAFEL

    private val sampleFlights = mapOf(
        antoineId to flatFlight(antoineId, lat = 45.95, lon = 6.42, alt = 1800),
        benId to flatFlight(benId, lat = 45.96, lon = 6.43, alt = 1900),
        dutId to flatFlight(dutId, lat = 45.95, lon = 6.42, alt = 1800),
    )

    private val scenario = Scenario(
        name = "injector-fixture",
        location = "Test launch",
        date = LocalDate.of(2026, 4, 25),
        region = "fr",
        pilots = listOf(
            ScenarioPilot(antoineId, "Antoine", "/unused.igc"),
            ScenarioPilot(benId, "Ben", "/unused.igc"),
            ScenarioPilot(dutId, "Dut", "/unused.igc"),
        ),
        notes = "synthetic fixture",
    )

    private fun newPlayback(): SwarmPlayback = SwarmPlayback(
        scenario = scenario,
        tickInterval = Duration.ofSeconds(1),
        flightsOverride = sampleFlights,
    )

    /**
     * Build a tiny IGC with three fixes covering one hour, all at the
     * same lat/lon/alt. Position interpolation across the hour is trivial
     * so tests can assert exact coordinates regardless of which virtual
     * second they sample.
     */
    private fun flatFlight(
        @Suppress("unused") id: PilotId,
        lat: Double,
        lon: Double,
        alt: Int,
    ): IgcFlight {
        val date = LocalDate.of(2026, 4, 25)
        val t0 = playbackStart
        return IgcFlight(
            date = date,
            fixes = listOf(
                IgcFix(t0, lat, lon, alt, alt, true),
                IgcFix(t0.plusSeconds(1800), lat, lon, alt, alt, true),
                IgcFix(t0.plusSeconds(3600), lat, lon, alt, alt, true),
            ),
        )
    }

    /** Pinned wall-clock — keeps `Position.time` deterministic on the wire. */
    private val fixedNowMillis: Long = 1_700_000_000_000L

    // -- Test: cadence + correctness ------------------------------------

    @Test
    fun `each tick emits one peer update per peer with the right node number`() = runTest {
        val transport = FakeBleTransport()
        val ble = BleConnection(
            pairedBoardId = "!fixture",
            ourNodeNumber = 0x1111_1111L,
            transport = transport,
            scope = backgroundScope,
        )
        // The injector pushes synthetic peer updates straight into
        // BleConnection's events flow (injectMeshEventForTest — the
        // firmware-loopback bypass, see VirtualPeerInjector KDoc), so the
        // link never needs to be UP and we assert on emitted MeshEvents.

        val packetIds = AtomicInteger(1000)
        val injector = VirtualPeerInjector(
            bleConnection = ble,
            playback = newPlayback(),
            peerNodeNumbers = mapOf(
                antoineId to antoineNodeNumber,
                benId to benNodeNumber,
            ),
            speedMultiplier = 60,                                // 30s virtual / 60 = 500ms wall
            propagation = AlwaysDelivered,
            dutPilotId = dutId,
            positionBroadcastInterval = Duration.ofSeconds(30),
            playbackStart = playbackStart,
            wallClockMillis = { fixedNowMillis },
            packetIdSource = { packetIds.getAndIncrement() },
        )

        val events = collectEvents(ble)
        injector.start(backgroundScope)

        // Three ticks of 500 ms each.
        advanceTimeBy(1_500)
        runCurrent()

        injector.stop()
        runCurrent()

        // 2 peers x 3 ticks = 6 emitted peer updates.
        val updates = events.filterIsInstance<MeshEvent.PeerPositionUpdate>()
        assertThat(updates).hasSize(6)
        assertThat(injector.injectedCount).isEqualTo(6L)

        val perPeer = updates.groupBy { it.peer.nodeNumber }
        assertThat(perPeer.keys).containsExactly(antoineNodeNumber, benNodeNumber)
        assertThat(perPeer.getValue(antoineNodeNumber)).hasSize(3)
        assertThat(perPeer.getValue(benNodeNumber)).hasSize(3)

        // Each peer's fix must carry the right lat/lon/alt + the wall-clock
        // timestamp stamped on the wire (the IGC fixtures are flat, so any
        // sample suffices).
        val antoineFix = perPeer.getValue(antoineNodeNumber).first().fix
        assertThat(antoineFix.latitudeDeg).isWithin(1e-6).of(45.95)
        assertThat(antoineFix.longitudeDeg).isWithin(1e-6).of(6.42)
        assertThat(antoineFix.altitudeMeters).isEqualTo(1800)
        assertThat(antoineFix.timestampSeconds).isEqualTo(fixedNowMillis / 1000L)

        val benFix = perPeer.getValue(benNodeNumber).first().fix
        assertThat(benFix.latitudeDeg).isWithin(1e-6).of(45.96)
        assertThat(benFix.longitudeDeg).isWithin(1e-6).of(6.43)
        assertThat(benFix.altitudeMeters).isEqualTo(1900)
    }

    // -- Test: propagation gating ---------------------------------------

    @Test
    fun `always-Lost propagation produces zero writes`() = runTest {
        val transport = FakeBleTransport()
        val ble = BleConnection(
            pairedBoardId = "!fixture",
            ourNodeNumber = 0x1111_1111L,
            transport = transport,
            scope = backgroundScope,
        )

        val injector = VirtualPeerInjector(
            bleConnection = ble,
            playback = newPlayback(),
            peerNodeNumbers = mapOf(
                antoineId to antoineNodeNumber,
                benId to benNodeNumber,
            ),
            speedMultiplier = 60,
            propagation = AlwaysLost,
            dutPilotId = dutId,
            positionBroadcastInterval = Duration.ofSeconds(30),
            playbackStart = playbackStart,
            wallClockMillis = { fixedNowMillis },
        )

        val events = collectEvents(ble)
        injector.start(backgroundScope)

        // Five ticks — plenty of opportunities for any leak to show.
        advanceTimeBy(2_500)
        runCurrent()
        injector.stop()
        runCurrent()

        assertWithMessage("AlwaysLost peers must not surface any peer updates")
            .that(events.filterIsInstance<MeshEvent.PeerPositionUpdate>()).isEmpty()
        assertThat(injector.injectedCount).isEqualTo(0L)
    }

    // -- Test: the injected event surfaces as a PeerPositionUpdate -------

    @Test
    fun `injected peer surfaces as a PeerPositionUpdate carrying its identity and fix`() = runTest {
        val transport = FakeBleTransport()
        val ble = BleConnection(
            pairedBoardId = "!fixture",
            ourNodeNumber = 0x1111_1111L,
            transport = transport,
            scope = backgroundScope,
        )

        val injector = VirtualPeerInjector(
            bleConnection = ble,
            playback = newPlayback(),
            peerNodeNumbers = mapOf(antoineId to antoineNodeNumber),
            speedMultiplier = 60,
            propagation = AlwaysDelivered,
            dutPilotId = dutId,
            positionBroadcastInterval = Duration.ofSeconds(30),
            playbackStart = playbackStart,
            wallClockMillis = { fixedNowMillis },
        )

        val events = collectEvents(ble)
        injector.start(backgroundScope)
        advanceTimeBy(500)
        runCurrent()
        injector.stop()
        runCurrent()

        // One tick, one peer → exactly one PeerPositionUpdate, surfaced on
        // the same events flow the production BLE layer feeds Redux from.
        // The pilot handle rides along as the peer's long name so the HUD
        // shows a friendly callsign.
        val ppu = events.filterIsInstance<MeshEvent.PeerPositionUpdate>().single()
        assertThat(ppu.peer.nodeNumber).isEqualTo(antoineNodeNumber)
        assertThat(ppu.peer.longName).isEqualTo(antoineId.value)
        assertThat(ppu.fix.latitudeDeg).isWithin(1e-6).of(45.95)
        assertThat(ppu.fix.longitudeDeg).isWithin(1e-6).of(6.42)
        assertThat(ppu.fix.altitudeMeters).isEqualTo(1800)
        assertThat(ppu.fix.timestampSeconds).isEqualTo(fixedNowMillis / 1000L)
    }

    // -- Test: link state irrelevant (injectRawToRadio bypasses it) -----

    @Test
    fun `injection works even when the BleConnection has never seen Connected`() = runTest {
        // The injector emits straight into the events flow and deliberately
        // does NOT gate on link state — the replay bench needs peers to land
        // before the production link state machine has run.
        val transport = FakeBleTransport()
        val ble = BleConnection(
            pairedBoardId = "!fixture",
            ourNodeNumber = 0x1111_1111L,
            transport = transport,
            scope = backgroundScope,
        )
        // No start(), no transport.emit(Connected).

        val injector = VirtualPeerInjector(
            bleConnection = ble,
            playback = newPlayback(),
            peerNodeNumbers = mapOf(antoineId to antoineNodeNumber),
            speedMultiplier = 60,
            propagation = AlwaysDelivered,
            dutPilotId = dutId,
            positionBroadcastInterval = Duration.ofSeconds(30),
            playbackStart = playbackStart,
            wallClockMillis = { fixedNowMillis },
        )
        val events = collectEvents(ble)
        injector.start(backgroundScope)
        advanceTimeBy(500)
        runCurrent()
        injector.stop()
        runCurrent()

        // Single tick, single peer → one surfaced peer update.
        assertThat(events.filterIsInstance<MeshEvent.PeerPositionUpdate>()).hasSize(1)
    }

    // -- Test: injection bypasses the transport entirely -----------------

    @Test
    fun `transport write failures are irrelevant — injection bypasses the wire`() = runTest {
        // The injector no longer touches the BLE write characteristic: it
        // pushes synthetic events directly into BleConnection. A transport
        // that would reject every write therefore has no bearing — every
        // peer update still surfaces, and the driver never crashes.
        val transport = FakeBleTransport().apply { writesShouldFail = true }
        val ble = BleConnection(
            pairedBoardId = "!fixture",
            ourNodeNumber = 0x1111_1111L,
            transport = transport,
            scope = backgroundScope,
        )

        val injector = VirtualPeerInjector(
            bleConnection = ble,
            playback = newPlayback(),
            peerNodeNumbers = mapOf(antoineId to antoineNodeNumber),
            speedMultiplier = 60,
            propagation = AlwaysDelivered,
            dutPilotId = dutId,
            positionBroadcastInterval = Duration.ofSeconds(30),
            playbackStart = playbackStart,
            wallClockMillis = { fixedNowMillis },
        )

        val events = collectEvents(ble)
        injector.start(backgroundScope)
        advanceTimeBy(1_500)
        runCurrent()
        injector.stop()
        runCurrent()

        // Three ticks, one peer → three peer updates, transport untouched.
        assertThat(events.filterIsInstance<MeshEvent.PeerPositionUpdate>()).hasSize(3)
        assertThat(injector.injectedCount).isEqualTo(3L)
        assertThat(transport.writes).isEmpty()
    }

    // -- Helpers --------------------------------------------------------

    private object AlwaysDelivered : PropagationModel {
        override fun propagate(
            sender: PilotEndpoint,
            receiver: PilotEndpoint,
            txPower: TxPower,
        ): PropagationOutcome = PropagationOutcome.Delivered
    }

    private object AlwaysLost : PropagationModel {
        override fun propagate(
            sender: PilotEndpoint,
            receiver: PilotEndpoint,
            txPower: TxPower,
        ): PropagationOutcome = PropagationOutcome.Lost(LossReason.OutOfRange)
    }

    /**
     * Subscribe to [ble]'s events flow and return a live list that
     * accumulates every emission. Must be called BEFORE the injector
     * starts: the events flow is a replay-0 SharedFlow, so a late
     * subscriber would miss early peer updates. [runCurrent] lets the
     * collector subscribe before we return.
     */
    private fun TestScope.collectEvents(ble: BleConnection): List<MeshEvent> {
        val received = mutableListOf<MeshEvent>()
        backgroundScope.launch { ble.events().collect { received += it } }
        runCurrent()
        return received
    }
}
