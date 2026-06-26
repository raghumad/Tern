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
    fun `NodeInfo decodes User hw_model onto the peer identity`() {
        // User { id; long_name; short_name; hw_model = PRIVATE_HW(255) }
        val userBody = ProtoWriter().apply {
            writeString(1, "!a1b2c3d4")
            writeString(2, "Antoine")
            writeString(3, "AN")
            writeVarintField(fieldNumber = 5, value = MeshPacketCodec.HW_MODEL_PRIVATE.toLong()) // User.hw_model
        }.toByteArray()
        val nodeInfoBody = ProtoWriter().apply {
            writeVarintField(fieldNumber = 1, value = antoineNodeNumber)
            writeMessage(2, userBody)
        }.toByteArray()
        val frame = ProtoWriter().apply { writeMessage(4, nodeInfoBody) }.toByteArray()

        val event = MeshPacketCodec.decodeFromRadio(frame)
        assertThat(event).isInstanceOf(MeshEvent.PeerIdentityKnown::class.java)
        assertThat((event as MeshEvent.PeerIdentityKnown).peer.hwModel).isEqualTo(MeshPacketCodec.HW_MODEL_PRIVATE)
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

    // ---------- LoRa region (set_config) tests ----------

    @Test
    fun `decodeLoraRegion reads region from a FromRadio config frame`() {
        // FromRadio { config = Config { lora = LoRaConfig { region = US(1) } } }
        val lora = ProtoWriter().apply { writeInt32(7, 1) }.toByteArray()
        val config = ProtoWriter().apply { writeMessage(6, lora) }.toByteArray()
        val frame = ProtoWriter().apply { writeMessage(5, config) }.toByteArray()

        assertThat(MeshPacketCodec.decodeLoraRegion(frame)).isEqualTo(1)
    }

    @Test
    fun `decodeLoraRegion returns 0 UNSET when lora present but region omitted`() {
        // A fresh board: proto3 omits region=0, so the lora sub-message carries
        // only e.g. use_preset. This must read as UNSET(0), not "unknown".
        val lora = ProtoWriter().apply { writeBool(1, true) }.toByteArray()
        val config = ProtoWriter().apply { writeMessage(6, lora) }.toByteArray()
        val frame = ProtoWriter().apply { writeMessage(5, config) }.toByteArray()

        assertThat(MeshPacketCodec.decodeLoraRegion(frame)).isEqualTo(0)
    }

    @Test
    fun `decodeLoraRegion returns null for a non-lora config frame`() {
        // FromRadio.config carrying a device sub-config (field 1), not lora.
        val device = ProtoWriter().apply { writeInt32(1, 1) }.toByteArray()
        val config = ProtoWriter().apply { writeMessage(1, device) }.toByteArray()
        val frame = ProtoWriter().apply { writeMessage(5, config) }.toByteArray()

        assertThat(MeshPacketCodec.decodeLoraRegion(frame)).isNull()
    }

    @Test
    fun `decodeLoraRegion returns null for an ordinary packet frame`() {
        val frame = buildFromRadioWithMeshPacket(
            fromNodeNumber = antoineNodeNumber,
            rxTime = rxTime,
            portNum = MeshPacketCodec.PORT_POSITION_APP,
            payload = MeshPacketCodec.encodePositionPayload(pos),
        )

        assertThat(MeshPacketCodec.decodeLoraRegion(frame)).isNull()
    }

    @Test
    fun `setLoraConfig sends an admin set_config to the board with region and tx enabled`() {
        val boardNode = 0x02ed8530L
        val toRadio = MeshPacketCodec.encodeToRadioSetLoraConfig(
            boardNodeNumber = boardNode,
            packetId = 7,
            region = 3, // EU_868
        )

        val meshPacket = unwrapToRadioPacket(toRadio)
        // Addressed to the board, on ADMIN_APP.
        assertThat(meshPacketTo(meshPacket)).isEqualTo(boardNode)
        val admin = meshPacketDataPayload(meshPacket, expectedPortNum = MeshPacketCodec.PORT_ADMIN_APP)
        val configBytes = extractSetConfigBytes(admin)

        // Region round-trips: reshape the Config as a FromRadio.config and re-read it.
        val asFromRadioConfig = ProtoWriter().apply { writeMessage(5, configBytes) }.toByteArray()
        assertThat(MeshPacketCodec.decodeLoraRegion(asFromRadioConfig)).isEqualTo(3)

        // tx_enabled MUST be present and true — a region *change* (US→EU) does
        // not get firmware's first-set TX auto-enable, so we assert it ourselves.
        assertThat(loraBoolField(configBytes, fieldNumber = 9)).isTrue()
        // use_preset true so the board derives bandwidth/SF/CR from the preset.
        assertThat(loraBoolField(configBytes, fieldNumber = 1)).isTrue()
    }

    // ---------- MyNodeInfo (admin addressing) tests ----------

    @Test
    fun `decodeMyNodeNum reads the board node number from a FromRadio my_info frame`() {
        // FromRadio { my_info = MyNodeInfo { my_node_num = 0x0fb88838 } }
        // (proto3 uint32 → varint).
        val myInfo = ProtoWriter().apply { writeVarintField(fieldNumber = 1, value = 0x0fb88838L) }.toByteArray()
        val frame = ProtoWriter().apply { writeMessage(3, myInfo) }.toByteArray()

        assertThat(MeshPacketCodec.decodeMyNodeNum(frame)).isEqualTo(0x0fb88838L)
    }

    @Test
    fun `decodeMyNodeNum tolerates a fixed32-encoded my_node_num`() {
        // Defensive: survive proto drift if a firmware encodes my_node_num as fixed32.
        val myInfo = ProtoWriter().apply { writeFixed32(1, 0x0fb88838L) }.toByteArray()
        val frame = ProtoWriter().apply { writeMessage(3, myInfo) }.toByteArray()

        assertThat(MeshPacketCodec.decodeMyNodeNum(frame)).isEqualTo(0x0fb88838L)
    }

    @Test
    fun `decodeMyNodeNum returns null for a non-my_info frame`() {
        // An ordinary position packet says nothing about the board's own node.
        val frame = buildFromRadioWithMeshPacket(
            fromNodeNumber = antoineNodeNumber,
            rxTime = rxTime,
            portNum = MeshPacketCodec.PORT_POSITION_APP,
            payload = MeshPacketCodec.encodePositionPayload(pos),
        )

        assertThat(MeshPacketCodec.decodeMyNodeNum(frame)).isNull()
    }

    @Test
    fun `decodeMyNodeNum returns null for an empty frame`() {
        assertThat(MeshPacketCodec.decodeMyNodeNum(ByteArray(0))).isNull()
    }

    // ---------- set_channel position precision ----------

    @Test
    fun `setChannel carries a non-zero position precision so the team shares location`() {
        // Regression: a team channel with no module_settings defaults position_precision to 0,
        // and the firmware then STRIPS every position — buddies never see each other.
        val boardNode = 0x0fb88838L
        val psk = ByteArray(16) { it.toByte() }
        val toRadio = MeshPacketCodec.encodeToRadioSetChannel(boardNode, packetId = 5, name = "TernTest2", psk = psk)

        val meshPacket = unwrapToRadioPacket(toRadio)
        val admin = meshPacketDataPayload(meshPacket, expectedPortNum = MeshPacketCodec.PORT_ADMIN_APP)
        val channel = extractSetChannelBytes(admin)
        val settings = channelSettingsBytes(channel)

        // module_settings (field 7) → position_precision (field 1) must be present and > 0.
        val precision = positionPrecision(settings)
        assertThat(precision).isGreaterThan(0)
    }

    // ---------- set_owner (rename the board) ----------

    @Test
    fun `setOwner sends an admin set_owner to the board with the new long and short name`() {
        val boardNode = 0x02ed8530L
        val toRadio = MeshPacketCodec.encodeToRadioSetOwner(
            boardNodeNumber = boardNode,
            packetId = 11,
            longName = "Raghu's board",
            shortName = "RGHU",
        )

        val meshPacket = unwrapToRadioPacket(toRadio)
        // Addressed to the board, on ADMIN_APP.
        assertThat(meshPacketTo(meshPacket)).isEqualTo(boardNode)
        val admin = meshPacketDataPayload(meshPacket, expectedPortNum = MeshPacketCodec.PORT_ADMIN_APP)
        val user = extractSetOwnerUserBytes(admin)

        // long_name (field 2) + short_name (field 3) round-trip.
        assertThat(userStringField(user, fieldNumber = 2)).isEqualTo("Raghu's board")
        assertThat(userStringField(user, fieldNumber = 3)).isEqualTo("RGHU")
        // hw_model (field 5) is deliberately NOT sent — the firmware keeps PRIVATE_HW.
        assertThat(userHasField(user, fieldNumber = 5)).isFalse()
    }

    /** Pull the User bytes out of an AdminMessage.set_owner (field 8). */
    private fun extractSetOwnerUserBytes(adminBytes: ByteArray): ByteArray {
        val reader = ProtoReader(adminBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 8 && wire == Proto.WIRE_LENGTH_DELIMITED) return reader.readLengthDelimited()
            reader.skipField(wire)
        }
        error("AdminMessage.set_owner not found")
    }

    /** Read a length-delimited string field from a User body. */
    private fun userStringField(userBytes: ByteArray, fieldNumber: Int): String? {
        val reader = ProtoReader(userBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == fieldNumber && wire == Proto.WIRE_LENGTH_DELIMITED) return reader.readString()
            reader.skipField(wire)
        }
        return null
    }

    /** True iff a User body carries [fieldNumber] at all. */
    private fun userHasField(userBytes: ByteArray, fieldNumber: Int): Boolean {
        val reader = ProtoReader(userBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == fieldNumber) return true
            reader.skipField(wire)
        }
        return false
    }

    /** Pull the Channel bytes out of an AdminMessage.set_channel (field 33). */
    private fun extractSetChannelBytes(adminBytes: ByteArray): ByteArray {
        val reader = ProtoReader(adminBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 33 && wire == Proto.WIRE_LENGTH_DELIMITED) return reader.readLengthDelimited()
            reader.skipField(wire)
        }
        error("AdminMessage.set_channel not found")
    }

    /** Pull ChannelSettings (field 2) out of a Channel body. */
    private fun channelSettingsBytes(channelBytes: ByteArray): ByteArray {
        val reader = ProtoReader(channelBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 2 && wire == Proto.WIRE_LENGTH_DELIMITED) return reader.readLengthDelimited()
            reader.skipField(wire)
        }
        error("Channel.settings not found")
    }

    /** Read module_settings (field 7) → position_precision (field 1) from a ChannelSettings body. */
    private fun positionPrecision(settingsBytes: ByteArray): Int {
        val sr = ProtoReader(settingsBytes)
        var moduleBytes: ByteArray? = null
        while (sr.hasMore()) {
            val tag = sr.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 7 && wire == Proto.WIRE_LENGTH_DELIMITED) moduleBytes = sr.readLengthDelimited()
            else sr.skipField(wire)
        }
        val module = moduleBytes ?: error("ChannelSettings.module_settings not found")
        val mr = ProtoReader(module)
        var precision = 0
        while (mr.hasMore()) {
            val tag = mr.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 1 && wire == Proto.WIRE_VARINT) precision = mr.readVarint().toInt()
            else mr.skipField(wire)
        }
        return precision
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

    /** Read MeshPacket.to (field 2, fixed32). */
    private fun meshPacketTo(meshPacketBytes: ByteArray): Long {
        val reader = ProtoReader(meshPacketBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 2 && wire == Proto.WIRE_FIXED32) return reader.readFixed32()
            reader.skipField(wire)
        }
        error("MeshPacket.to not found")
    }

    /** Read MeshPacket.decoded (field 4) → Data, assert its portnum, return Data.payload. */
    private fun meshPacketDataPayload(meshPacketBytes: ByteArray, expectedPortNum: Int): ByteArray {
        val reader = ProtoReader(meshPacketBytes)
        var dataBytes: ByteArray? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 4 && wire == Proto.WIRE_LENGTH_DELIMITED) {
                dataBytes = reader.readLengthDelimited()
            } else {
                reader.skipField(wire)
            }
        }
        val data = dataBytes ?: error("MeshPacket.decoded not found")
        val dr = ProtoReader(data)
        var portNum = -1
        var payload = ByteArray(0)
        while (dr.hasMore()) {
            val tag = dr.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when {
                field == 1 && wire == Proto.WIRE_VARINT -> portNum = dr.readVarint().toInt()
                field == 2 && wire == Proto.WIRE_LENGTH_DELIMITED -> payload = dr.readLengthDelimited()
                else -> dr.skipField(wire)
            }
        }
        assertThat(portNum).isEqualTo(expectedPortNum)
        return payload
    }

    /** Pull the Config bytes out of an AdminMessage.set_config (field 34). */
    private fun extractSetConfigBytes(adminBytes: ByteArray): ByteArray {
        val reader = ProtoReader(adminBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 34 && wire == Proto.WIRE_LENGTH_DELIMITED) return reader.readLengthDelimited()
            reader.skipField(wire)
        }
        error("AdminMessage.set_config not found")
    }

    /** Read a bool field [fieldNumber] from the LoRaConfig inside a Config body. */
    private fun loraBoolField(configBytes: ByteArray, fieldNumber: Int): Boolean {
        val cr = ProtoReader(configBytes)
        var loraBytes: ByteArray? = null
        while (cr.hasMore()) {
            val tag = cr.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 6 && wire == Proto.WIRE_LENGTH_DELIMITED) {
                loraBytes = cr.readLengthDelimited()
            } else {
                cr.skipField(wire)
            }
        }
        val lora = loraBytes ?: error("Config.lora not found")
        val lr = ProtoReader(lora)
        var value = false
        while (lr.hasMore()) {
            val tag = lr.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == fieldNumber && wire == Proto.WIRE_VARINT) {
                value = lr.readVarint() != 0L
            } else {
                lr.skipField(wire)
            }
        }
        return value
    }
}
