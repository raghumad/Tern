package com.ternparagliding.mezulla.connection.ble

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.PeerPosition
import org.junit.jupiter.api.Test

/**
 * Decoder + encoder tests for [MeshPacketCodec].
 *
 * These exercise the wire format hand-rolled against the Meshtastic proto
 * schema at https://github.com/meshtastic/protobufs. They are pure JVM:
 * no Robolectric, no Android types, no Gradle codegen — if a future
 * Meshtastic firmware bump changes a field number or PortNum value, one
 * of these tests fails first.
 */
class MeshPacketCodecTest {

    private val antoineNodeNumber = 0xa1b2c3d4L
    private val rxTime = 1_700_000_000L
    private val pos = PeerPosition.Fix(
        latitudeDeg = 32.2319,
        longitudeDeg = 76.6334,
        altitudeMeters = 2400,
        groundSpeedMetersPerSecond = 9.0,
        groundTrackDegrees = 270.0,
        timestampSeconds = rxTime,
    )

    // ---------- decoder tests ----------

    @Test
    fun `FromRadio with a Position payload decodes to PeerPositionUpdate`() {
        val frame = buildFromRadioWithMeshPacket(
            fromNodeNumber = antoineNodeNumber,
            rxTime = rxTime,
            portNum = MeshPacketCodec.PORT_POSITION_APP,
            payload = MeshPacketCodec.encodePositionPayload(pos),
        )

        val event = MeshPacketCodec.decodeFromRadio(frame)

        assertThat(event).isInstanceOf(MeshEvent.PeerPositionUpdate::class.java)
        val ppu = event as MeshEvent.PeerPositionUpdate
        assertThat(ppu.peer.nodeNumber).isEqualTo(antoineNodeNumber)
        assertThat(ppu.peer.hexId).isEqualTo("!a1b2c3d4")
        assertThat(ppu.fix.latitudeDeg).isWithin(1e-6).of(pos.latitudeDeg)
        assertThat(ppu.fix.longitudeDeg).isWithin(1e-6).of(pos.longitudeDeg)
        assertThat(ppu.fix.altitudeMeters).isEqualTo(pos.altitudeMeters)
        assertThat(ppu.fix.timestampSeconds).isEqualTo(rxTime)
    }

    @Test
    fun `FromRadio with NodeInfo decodes to PeerIdentityKnown with names`() {
        // NodeInfo { num=0xa1b2c3d4; user { id="!a1b2c3d4"; long_name="Antoine"; short_name="AN" } }
        val userBody = ProtoWriter().apply {
            writeString(1, "!a1b2c3d4")        // User.id
            writeString(2, "Antoine")           // User.long_name
            writeString(3, "AN")                // User.short_name
        }.toByteArray()
        val nodeInfoBody = ProtoWriter().apply {
            writeVarintField(fieldNumber = 1, value = antoineNodeNumber) // NodeInfo.num
            writeMessage(2, userBody)                                    // NodeInfo.user
        }.toByteArray()
        val frame = ProtoWriter().apply {
            writeMessage(4, nodeInfoBody)  // FromRadio.node_info
        }.toByteArray()

        val event = MeshPacketCodec.decodeFromRadio(frame)

        assertThat(event).isInstanceOf(MeshEvent.PeerIdentityKnown::class.java)
        val pik = event as MeshEvent.PeerIdentityKnown
        assertThat(pik.peer.nodeNumber).isEqualTo(antoineNodeNumber)
        assertThat(pik.peer.longName).isEqualTo("Antoine")
        assertThat(pik.peer.shortName).isEqualTo("AN")
    }

    @Test
    fun `FromRadio with ALERT_APP data decodes to PeerAlert`() {
        val frame = buildFromRadioWithMeshPacket(
            fromNodeNumber = antoineNodeNumber,
            rxTime = rxTime,
            portNum = MeshPacketCodec.PORT_ALERT_APP,
            payload = MeshPacketCodec.encodePositionPayload(pos),
        )

        val event = MeshPacketCodec.decodeFromRadio(frame)

        assertThat(event).isInstanceOf(MeshEvent.PeerAlert::class.java)
        val alert = event as MeshEvent.PeerAlert
        assertThat(alert.peer.nodeNumber).isEqualTo(antoineNodeNumber)
        assertThat(alert.lastKnownPosition).isNotNull()
        assertThat(alert.lastKnownPosition!!.latitudeDeg).isWithin(1e-6).of(pos.latitudeDeg)
        // Timestamp comes from the embedded Position.time when present.
        assertThat(alert.timestampSeconds).isEqualTo(rxTime)
    }

    @Test
    fun `FromRadio with TELEMETRY_APP DeviceMetrics decodes to PeerTelemetry`() {
        // DeviceMetrics { battery_level = 87 }
        val dmBody = ProtoWriter().apply {
            writeInt32(1, 87)   // DeviceMetrics.battery_level
        }.toByteArray()
        // Telemetry { time = rxTime; device_metrics = ... }
        val telemetryBody = ProtoWriter().apply {
            writeFixed32(1, rxTime and 0xFFFFFFFFL) // Telemetry.time
            writeMessage(2, dmBody)                 // Telemetry.device_metrics
        }.toByteArray()
        val frame = buildFromRadioWithMeshPacket(
            fromNodeNumber = antoineNodeNumber,
            rxTime = rxTime,
            portNum = MeshPacketCodec.PORT_TELEMETRY_APP,
            payload = telemetryBody,
        )

        val event = MeshPacketCodec.decodeFromRadio(frame)

        assertThat(event).isInstanceOf(MeshEvent.PeerTelemetry::class.java)
        val tel = event as MeshEvent.PeerTelemetry
        assertThat(tel.peer.nodeNumber).isEqualTo(antoineNodeNumber)
        assertThat(tel.batteryPercent).isEqualTo(87)
        assertThat(tel.timestampSeconds).isEqualTo(rxTime)
    }

    @Test
    fun `FromRadio with unknown port number returns null (graceful drop)`() {
        val frame = buildFromRadioWithMeshPacket(
            fromNodeNumber = antoineNodeNumber,
            rxTime = rxTime,
            portNum = 999,  // not a port we surface
            payload = ByteArray(0),
        )

        val event = MeshPacketCodec.decodeFromRadio(frame)

        assertThat(event).isNull()
    }

    @Test
    fun `empty FromRadio frame returns null (no crash on heartbeat reads)`() {
        assertThat(MeshPacketCodec.decodeFromRadio(ByteArray(0))).isNull()
    }

    // ---------- encoder / round-trip tests ----------

    @Test
    fun `sendOwnPosition encoding round-trips back to the same Position`() {
        val toRadio = MeshPacketCodec.encodeToRadioPosition(
            fromNodeNumber = antoineNodeNumber,
            packetId = 42,
            fix = pos,
        )

        // Strip the outer ToRadio { packet = ... } wrapper so we can feed
        // the inner MeshPacket back through decodeFromRadio by reshaping
        // as a FromRadio { packet = ... } frame (FromRadio.packet has the
        // same MeshPacket field on field number 2, vs ToRadio.packet on
        // field 1).
        val meshPacketBytes = unwrapToRadioPacket(toRadio)
        val asFromRadio = ProtoWriter().apply {
            writeMessage(2, meshPacketBytes)  // FromRadio.packet
        }.toByteArray()

        val event = MeshPacketCodec.decodeFromRadio(asFromRadio)
        assertThat(event).isInstanceOf(MeshEvent.PeerPositionUpdate::class.java)
        val ppu = event as MeshEvent.PeerPositionUpdate
        assertThat(ppu.peer.nodeNumber).isEqualTo(antoineNodeNumber)
        assertThat(ppu.fix.latitudeDeg).isWithin(1e-6).of(pos.latitudeDeg)
        assertThat(ppu.fix.longitudeDeg).isWithin(1e-6).of(pos.longitudeDeg)
        assertThat(ppu.fix.altitudeMeters).isEqualTo(pos.altitudeMeters)
        assertThat(ppu.fix.timestampSeconds).isEqualTo(rxTime)
    }

    @Test
    fun `sendAlert encoding round-trips through decoder as PeerAlert with last known position`() {
        val toRadio = MeshPacketCodec.encodeToRadioAlert(
            fromNodeNumber = antoineNodeNumber,
            packetId = 99,
            lastKnownPosition = pos,
        )

        val meshPacketBytes = unwrapToRadioPacket(toRadio)
        val asFromRadio = ProtoWriter().apply {
            writeMessage(2, meshPacketBytes)
        }.toByteArray()

        val event = MeshPacketCodec.decodeFromRadio(asFromRadio)
        assertThat(event).isInstanceOf(MeshEvent.PeerAlert::class.java)
        val alert = event as MeshEvent.PeerAlert
        assertThat(alert.peer.nodeNumber).isEqualTo(antoineNodeNumber)
        assertThat(alert.lastKnownPosition).isNotNull()
        assertThat(alert.lastKnownPosition!!.latitudeDeg).isWithin(1e-6).of(pos.latitudeDeg)
    }

    @Test
    fun `sendAlert with no last known position still produces a valid frame`() {
        val toRadio = MeshPacketCodec.encodeToRadioAlert(
            fromNodeNumber = antoineNodeNumber,
            packetId = 1,
            lastKnownPosition = null,
        )

        // Must be a non-empty ToRadio frame.
        assertThat(toRadio).isNotEmpty()
        val meshPacketBytes = unwrapToRadioPacket(toRadio)
        val asFromRadio = ProtoWriter().apply {
            writeMessage(2, meshPacketBytes)
        }.toByteArray()
        val event = MeshPacketCodec.decodeFromRadio(asFromRadio)
        assertThat(event).isInstanceOf(MeshEvent.PeerAlert::class.java)
        val alert = event as MeshEvent.PeerAlert
        // Position embedded was empty bytes, so lastKnownPosition is null.
        assertThat(alert.lastKnownPosition).isNull()
    }

    // ---------- helpers ----------

    /**
     * Build a `FromRadio { packet = MeshPacket { from, rx_time, decoded =
     * Data { portnum, payload } } }` frame, the canonical inbound shape.
     */
    private fun buildFromRadioWithMeshPacket(
        fromNodeNumber: Long,
        rxTime: Long,
        portNum: Int,
        payload: ByteArray,
    ): ByteArray {
        val dataBody = ProtoWriter().apply {
            writeInt32(1, portNum)        // Data.portnum
            writeBytes(2, payload)        // Data.payload
        }.toByteArray()
        val meshPacketBody = ProtoWriter().apply {
            writeFixed32(1, fromNodeNumber and 0xFFFFFFFFL)  // MeshPacket.from
            writeFixed32(7, rxTime and 0xFFFFFFFFL)          // MeshPacket.rx_time
            writeMessage(4, dataBody)                        // MeshPacket.decoded
        }.toByteArray()
        return ProtoWriter().apply {
            writeMessage(2, meshPacketBody)  // FromRadio.packet
        }.toByteArray()
    }

    /**
     * Extract the inner MeshPacket bytes from a `ToRadio { packet = ... }`
     * frame. ToRadio.packet is field 1; FromRadio.packet is field 2.
     */
    private fun unwrapToRadioPacket(toRadioBytes: ByteArray): ByteArray {
        val reader = ProtoReader(toRadioBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 1 && wire == Proto.WIRE_LENGTH_DELIMITED) {
                return reader.readLengthDelimited()
            }
            reader.skipField(wire)
        }
        error("ToRadio.packet not found")
    }

    /** Convenience for writing a varint field with an explicit value (Long). */
    private fun ProtoWriter.writeVarintField(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, Proto.WIRE_VARINT)
        writeVarint(value)
    }
}
