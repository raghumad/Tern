package com.ternparagliding.sim.injector

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.PeerPosition
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.connection.ble.BleTransportEvent
import com.ternparagliding.mezulla.connection.ble.FakeBleTransport
import com.ternparagliding.mezulla.connection.ble.MeshPacketCodec
import com.ternparagliding.mezulla.connection.ble.ProtoWriter
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
 * The injector is the BLE/protocol arm of the Aravis replay test bench
 * (loopback peer trick — see [VirtualPeerInjector] KDoc). Tests exercise
 * three properties:
 *
 *  1. **Cadence + correctness:** with two virtual peers, `delay(N)` ticks
 *     advance virtual time and produce one `writeToRadio` per peer per
 *     tick, with `from = peer node number` and lat/lon matching the IGC
 *     fix.
 *  2. **Propagation gating:** with an always-Lost model, no writes happen
 *     — the simulator stays silent about packets that did not reach us.
 *  3. **Round-trip:** the bytes the injector emits on `ToRadio` can be
 *     reflected back as a `FromRadio` and decoded by the same
 *     [MeshPacketCodec] the production BLE layer uses, producing a
 *     [MeshEvent.PeerPositionUpdate] for the right peer.
 *
 * Coroutine plumbing: `runTest` virtualises [kotlinx.coroutines.delay], so
 * the injector's wall-clock cadence is driven by [advanceTimeBy] without
 * real sleeps.
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
    fun `each tick writes one ToRadio per peer with the right from node number`() = runTest {
        val transport = FakeBleTransport()
        val ble = BleConnection(
            pairedBoardId = "!fixture",
            ourNodeNumber = 0x1111_1111L,
            transport = transport,
            scope = backgroundScope,
        )
        // Get the link UP so... wait — the injector uses
        // injectRawToRadio() which bypasses the link gate on purpose, so
        // we do NOT need link UP. But the production BleConnection
        // collectorJob is wired through start(); skipping start() here
        // keeps the test minimal.

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

        injector.start(backgroundScope)

        // Three ticks of 500 ms each.
        advanceTimeBy(1_500)
        runCurrent()

        injector.stop()

        // 2 peers x 3 ticks = 6 writes
        assertThat(transport.writes).hasSize(6)

        // Decode every write and split per peer. We do this by reading
        // the inner MeshPacket out of the ToRadio frame and grabbing the
        // `from` field.
        val perPeer = transport.writes.groupBy { fromNodeNumberOf(it) }
        assertThat(perPeer.keys).containsExactly(antoineNodeNumber, benNodeNumber)
        assertThat(perPeer.getValue(antoineNodeNumber)).hasSize(3)
        assertThat(perPeer.getValue(benNodeNumber)).hasSize(3)

        // Each peer's first write must carry the right lat/lon/alt
        // (the IGC fixtures are flat, so any sample suffices).
        val antoineFix = decodePositionFix(perPeer.getValue(antoineNodeNumber).first())
        assertThat(antoineFix.latitudeDeg).isWithin(1e-6).of(45.95)
        assertThat(antoineFix.longitudeDeg).isWithin(1e-6).of(6.42)
        assertThat(antoineFix.altitudeMeters).isEqualTo(1800)
        assertThat(antoineFix.timestampSeconds)
            .isEqualTo(fixedNowMillis / 1000L)

        val benFix = decodePositionFix(perPeer.getValue(benNodeNumber).first())
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

        injector.start(backgroundScope)

        // Five ticks — plenty of opportunities for any leak to show.
        advanceTimeBy(2_500)
        runCurrent()
        injector.stop()

        assertWithMessage("AlwaysLost peers must not produce any radio writes")
            .that(transport.writes).isEmpty()
        assertThat(injector.injectedCount).isEqualTo(0L)
    }

    // -- Test: round-trip via the same codec the BLE layer uses ---------

    @Test
    fun `round-trip - encoded ToRadio matches what decodeFromRadio produces for the same peer`() = runTest {
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

        injector.start(backgroundScope)
        advanceTimeBy(500)
        runCurrent()
        injector.stop()

        assertThat(transport.writes).hasSize(1)
        val toRadioBytes = transport.writes.single()

        // Simulate the board's loopback: re-wrap the inner MeshPacket as
        // a FromRadio (field 2) and feed it back through the same codec
        // the production BleConnection uses on inbound bytes. This is
        // exactly the round-trip the test firmware achieves at runtime.
        val fromRadioBytes = reflectAsFromRadio(toRadioBytes)
        val event = MeshPacketCodec.decodeFromRadio(fromRadioBytes)

        assertThat(event).isInstanceOf(MeshEvent.PeerPositionUpdate::class.java)
        val ppu = event as MeshEvent.PeerPositionUpdate
        assertThat(ppu.peer.nodeNumber).isEqualTo(antoineNodeNumber)
        assertThat(ppu.fix.latitudeDeg).isWithin(1e-6).of(45.95)
        assertThat(ppu.fix.longitudeDeg).isWithin(1e-6).of(6.42)
        assertThat(ppu.fix.altitudeMeters).isEqualTo(1800)
        assertThat(ppu.fix.timestampSeconds).isEqualTo(fixedNowMillis / 1000L)
    }

    // -- Test: link state irrelevant (injectRawToRadio bypasses it) -----

    @Test
    fun `injection works even when the BleConnection has never seen Connected`() = runTest {
        // The injector calls injectRawToRadio which deliberately does NOT
        // gate on link state — the loopback bench needs to push bytes
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
        injector.start(backgroundScope)
        advanceTimeBy(500)
        runCurrent()
        injector.stop()

        // Single tick, single peer → one write.
        assertThat(transport.writes).hasSize(1)
    }

    // -- Test: writes can fail silently (transport returns false) --------

    @Test
    fun `transport write failures do not crash the driver`() = runTest {
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

        injector.start(backgroundScope)
        advanceTimeBy(1_500)
        runCurrent()
        injector.stop()

        // Transport recorded no writes (it rejected them), and the
        // injector counted zero successful injections.
        assertThat(transport.writes).isEmpty()
        assertThat(injector.injectedCount).isEqualTo(0L)
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
     * Pluck the `from` node number (MeshPacket field 1, fixed32) out of a
     * ToRadio frame. Hand-rolled — the public codec only decodes
     * FromRadio frames, and we want to assert directly on the wire bytes
     * the injector produced.
     */
    private fun fromNodeNumberOf(toRadioBytes: ByteArray): Long {
        val meshPacketBytes = extractInnerMeshPacket(toRadioBytes)
        // MeshPacket.from is field 1, wire type 5 (fixed32). Find tag
        // 0x0D (1 << 3 | 5 = 0x0D) and read the next 4 bytes LE.
        var i = 0
        while (i < meshPacketBytes.size) {
            val tag = meshPacketBytes[i].toInt() and 0xFF
            if (tag == 0x0D) {
                val b0 = meshPacketBytes[i + 1].toInt() and 0xFF
                val b1 = meshPacketBytes[i + 2].toInt() and 0xFF
                val b2 = meshPacketBytes[i + 3].toInt() and 0xFF
                val b3 = meshPacketBytes[i + 4].toInt() and 0xFF
                return (b0.toLong() or
                    (b1.toLong() shl 8) or
                    (b2.toLong() shl 16) or
                    (b3.toLong() shl 24)) and 0xFFFFFFFFL
            }
            // Skip this field. For the simple message shape the encoder
            // produces (fixed32 + length-delimited + fixed32), we walk
            // tag-by-tag manually rather than re-implement a full proto
            // reader: the helper just needs to find `from` and bail.
            i = skipField(meshPacketBytes, i)
        }
        error("from field not found in MeshPacket bytes")
    }

    /**
     * Pull the inner MeshPacket bytes out of a ToRadio frame. ToRadio
     * wraps `MeshPacket` in field 1 as length-delimited.
     */
    private fun extractInnerMeshPacket(toRadioBytes: ByteArray): ByteArray {
        var i = 0
        // Tag for field 1, length-delimited (wire 2) = (1 << 3) | 2 = 0x0A.
        require(toRadioBytes[i].toInt() and 0xFF == 0x0A) {
            "ToRadio frame did not begin with field-1 length-delimited tag"
        }
        i++
        val (len, after) = readVarint(toRadioBytes, i)
        return toRadioBytes.copyOfRange(after, after + len.toInt())
    }

    /**
     * Re-encode the inner MeshPacket bytes as a FromRadio frame (which
     * also wraps a MeshPacket, but in field 2). This is what the board
     * effectively does: it receives our ToRadio, peels the MeshPacket
     * out, decides it's an inbound packet from the `from` node, and
     * re-emits it as the field-2 variant of FromRadio.
     */
    private fun reflectAsFromRadio(toRadioBytes: ByteArray): ByteArray {
        val mesh = extractInnerMeshPacket(toRadioBytes)
        return ProtoWriter().apply {
            writeMessage(2, mesh)  // FromRadio.packet (field 2)
        }.toByteArray()
    }

    /**
     * Decode the Position payload out of a ToRadio. Reuses
     * [extractInnerMeshPacket] for the outer unwrap then walks the
     * MeshPacket → Data → Position chain manually (the codec's
     * `decodePosition` is private; we only need a tiny subset for the
     * lat/lon/alt assertions).
     */
    private fun decodePositionFix(toRadioBytes: ByteArray): PeerPosition.Fix {
        val mesh = extractInnerMeshPacket(toRadioBytes)
        val dataBytes = extractLengthDelimitedField(mesh, fieldNumber = 4)
            ?: error("MeshPacket.decoded (field 4) not found")
        val payload = extractLengthDelimitedField(dataBytes, fieldNumber = 2)
            ?: error("Data.payload (field 2) not found")

        // Position: lat (sfixed32 field 1), lon (sfixed32 field 2),
        // alt (varint field 3), time (fixed32 field 4).
        var latI = 0
        var lonI = 0
        var alt = 0
        var time = 0L
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF
            when (tag) {
                // Field 1, wire 5 (sfixed32) = (1<<3)|5 = 0x0D
                0x0D -> {
                    latI = readLittleEndianInt(payload, i + 1)
                    i += 5
                }
                // Field 2, wire 5 = 0x15
                0x15 -> {
                    lonI = readLittleEndianInt(payload, i + 1)
                    i += 5
                }
                // Field 3, wire 0 (varint) = 0x18
                0x18 -> {
                    val (v, after) = readVarint(payload, i + 1)
                    alt = v.toInt()
                    i = after
                }
                // Field 4, wire 5 (fixed32) = 0x25
                0x25 -> {
                    time = (readLittleEndianInt(payload, i + 1).toLong()
                        and 0xFFFFFFFFL)
                    i += 5
                }
                else -> {
                    i = skipField(payload, i)
                }
            }
        }
        return PeerPosition.Fix(
            latitudeDeg = latI.toDouble() * 1e-7,
            longitudeDeg = lonI.toDouble() * 1e-7,
            altitudeMeters = alt,
            groundSpeedMetersPerSecond = null,
            groundTrackDegrees = null,
            timestampSeconds = time,
        )
    }

    private fun extractLengthDelimitedField(
        bytes: ByteArray,
        fieldNumber: Int,
    ): ByteArray? {
        val targetTag = ((fieldNumber shl 3) or 2) and 0xFF
        var i = 0
        while (i < bytes.size) {
            val tag = bytes[i].toInt() and 0xFF
            if (tag == targetTag) {
                val (len, after) = readVarint(bytes, i + 1)
                return bytes.copyOfRange(after, after + len.toInt())
            }
            i = skipField(bytes, i)
        }
        return null
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to i
    }

    private fun readLittleEndianInt(bytes: ByteArray, start: Int): Int {
        val b0 = bytes[start].toInt() and 0xFF
        val b1 = bytes[start + 1].toInt() and 0xFF
        val b2 = bytes[start + 2].toInt() and 0xFF
        val b3 = bytes[start + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /**
     * Advance past one protobuf field starting at [start]. Returns the
     * index of the next field's tag.
     */
    private fun skipField(bytes: ByteArray, start: Int): Int {
        val tag = bytes[start].toInt() and 0xFF
        val wire = tag and 0x7
        var i = start + 1
        when (wire) {
            0 -> { // varint
                while ((bytes[i].toInt() and 0x80) != 0) i++
                i++
            }
            1 -> i += 8  // fixed64
            2 -> {       // length-delimited
                val (len, after) = readVarint(bytes, i)
                i = after + len.toInt()
            }
            5 -> i += 4  // fixed32
            else -> error("unsupported wire type $wire at byte $start")
        }
        return i
    }
}
