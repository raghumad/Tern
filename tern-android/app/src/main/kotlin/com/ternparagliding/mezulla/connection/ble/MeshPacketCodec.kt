package com.ternparagliding.mezulla.connection.ble

import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition

/**
 * Translates between Meshtastic protobuf wire bytes and Tern's typed
 * [MeshEvent]s, in both directions. The subset is the absolute minimum to
 * exercise the WS4.3 round-trip:
 *
 * Inbound (`FromRadio` → [MeshEvent]):
 *  - `FromRadio.packet` (field 2) — `MeshPacket`
 *      - `MeshPacket.from` (field 1) — node number
 *      - `MeshPacket.decoded` (field 4) — `Data`
 *          - `Data.portnum` (field 1) — PortNum byte
 *          - `Data.payload` (field 2) — bytes
 *      - `MeshPacket.rx_time` (field 7) — fallback timestamp
 *  - `FromRadio.node_info` (field 4) — `NodeInfo` (contains a `User`)
 *
 * Decoded payloads we handle by PortNum:
 *  - 3 / POSITION_APP → `Position` → [MeshEvent.PeerPositionUpdate]
 *  - 4 / NODEINFO_APP → `User` → [MeshEvent.PeerIdentityKnown]
 *  - 67 / TELEMETRY_APP → `Telemetry.device_metrics` → [MeshEvent.PeerTelemetry]
 *  - 11 / ALERT_APP → bytes → [MeshEvent.PeerAlert]
 *
 * Everything else returns `null` and is silently dropped — graceful
 * degradation is a project principle; an unknown port is not an error.
 *
 * Outbound ([MeshEvent] → `ToRadio` bytes):
 *  - position fix → `ToRadio.packet` with port = POSITION_APP, payload =
 *    encoded `Position` (broadcast destination)
 *  - alert        → `ToRadio.packet` with port = ALERT_APP, payload =
 *    encoded `Position` (last-known) or empty, `want_ack = true`,
 *    `priority = ALERT (110)`
 *
 * Field numbers and PortNum IDs come from
 * https://github.com/meshtastic/protobufs at
 * `meshtastic/mesh.proto`, `meshtastic/portnums.proto`,
 * `meshtastic/telemetry.proto`. Keep this comment in sync with the
 * field numbers below; any drift is the first place to look on a
 * decode mismatch.
 */
internal object MeshPacketCodec {

    // PortNum IDs — see meshtastic/portnums.proto.
    const val PORT_POSITION_APP = 3
    const val PORT_NODEINFO_APP = 4
    const val PORT_ALERT_APP = 11
    const val PORT_TELEMETRY_APP = 67

    // MeshPacket field numbers — see meshtastic/mesh.proto.
    private const val F_MESHPACKET_FROM = 1
    private const val F_MESHPACKET_TO = 2
    private const val F_MESHPACKET_DECODED = 4
    private const val F_MESHPACKET_ID = 6
    private const val F_MESHPACKET_RX_TIME = 7
    private const val F_MESHPACKET_WANT_ACK = 10
    private const val F_MESHPACKET_PRIORITY = 11

    // Data field numbers.
    private const val F_DATA_PORTNUM = 1
    private const val F_DATA_PAYLOAD = 2

    // FromRadio variant field numbers.
    private const val F_FROMRADIO_PACKET = 2
    private const val F_FROMRADIO_NODE_INFO = 4

    // ToRadio variant field numbers.
    private const val F_TORADIO_PACKET = 1
    private const val F_TORADIO_WANT_CONFIG_ID = 3

    // NodeInfo field numbers (subset).
    private const val F_NODEINFO_NUM = 1
    private const val F_NODEINFO_USER = 2

    // User field numbers (subset).
    private const val F_USER_ID = 1
    private const val F_USER_LONG_NAME = 2
    private const val F_USER_SHORT_NAME = 3

    // Position field numbers (subset).
    private const val F_POSITION_LATITUDE_I = 1
    private const val F_POSITION_LONGITUDE_I = 2
    private const val F_POSITION_ALTITUDE = 3
    private const val F_POSITION_TIME = 4
    private const val F_POSITION_GROUND_SPEED = 15
    private const val F_POSITION_GROUND_TRACK = 16

    // Telemetry field numbers.
    private const val F_TELEMETRY_TIME = 1
    private const val F_TELEMETRY_DEVICE_METRICS = 2
    private const val F_DEVICE_METRICS_BATTERY_LEVEL = 1

    // Priority.ALERT (see MeshPacket.Priority).
    private const val PRIORITY_ALERT = 110

    /** Broadcast destination address. Meshtastic-wide convention. */
    private const val BROADCAST_NODE_NUMBER = 0xFFFFFFFFL

    // ---------- inbound ----------

    /**
     * Decode one FromRadio frame. Returns the [MeshEvent] it maps to, or
     * `null` if the frame is for a variant we don't surface (config sync,
     * log records, etc.) or for a port we don't handle.
     */
    fun decodeFromRadio(bytes: ByteArray): MeshEvent? {
        if (bytes.isEmpty()) return null
        val reader = ProtoReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                F_FROMRADIO_PACKET -> {
                    if (wire != Proto.WIRE_LENGTH_DELIMITED) {
                        reader.skipField(wire); continue
                    }
                    val packetBytes = reader.readLengthDelimited()
                    return decodeMeshPacket(packetBytes)
                }
                F_FROMRADIO_NODE_INFO -> {
                    if (wire != Proto.WIRE_LENGTH_DELIMITED) {
                        reader.skipField(wire); continue
                    }
                    val niBytes = reader.readLengthDelimited()
                    return decodeNodeInfo(niBytes)
                }
                else -> reader.skipField(wire)
            }
        }
        return null
    }

    private fun decodeMeshPacket(bytes: ByteArray): MeshEvent? {
        val reader = ProtoReader(bytes)
        var fromNodeNumber: Long? = null
        var rxTime: Long = 0
        var dataBytes: ByteArray? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                F_MESHPACKET_FROM -> {
                    if (wire == Proto.WIRE_FIXED32) fromNodeNumber = reader.readFixed32()
                    else reader.skipField(wire)
                }
                F_MESHPACKET_RX_TIME -> {
                    if (wire == Proto.WIRE_FIXED32) rxTime = reader.readFixed32()
                    else reader.skipField(wire)
                }
                F_MESHPACKET_DECODED -> {
                    if (wire == Proto.WIRE_LENGTH_DELIMITED) dataBytes = reader.readLengthDelimited()
                    else reader.skipField(wire)
                }
                else -> reader.skipField(wire)
            }
        }
        val nodeNumber = fromNodeNumber ?: return null
        val data = dataBytes ?: return null
        return decodeData(data, nodeNumber, rxTime)
    }

    private fun decodeData(bytes: ByteArray, fromNodeNumber: Long, rxTime: Long): MeshEvent? {
        val reader = ProtoReader(bytes)
        var portNum = 0
        var payload: ByteArray = ByteArray(0)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                F_DATA_PORTNUM -> if (wire == Proto.WIRE_VARINT) portNum = reader.readInt32() else reader.skipField(wire)
                F_DATA_PAYLOAD -> if (wire == Proto.WIRE_LENGTH_DELIMITED) payload = reader.readLengthDelimited() else reader.skipField(wire)
                else -> reader.skipField(wire)
            }
        }
        val peer = PeerIdentity.fromNodeNumber(fromNodeNumber)
        return when (portNum) {
            PORT_POSITION_APP -> {
                val fix = decodePosition(payload, rxTime) ?: return null
                MeshEvent.PeerPositionUpdate(peer, fix)
            }
            PORT_NODEINFO_APP -> {
                val user = decodeUser(payload)
                MeshEvent.PeerIdentityKnown(
                    PeerIdentity.fromNodeNumber(
                        nodeNumber = fromNodeNumber,
                        longName = user.longName,
                        shortName = user.shortName,
                    ),
                )
            }
            PORT_TELEMETRY_APP -> {
                val (battery, telemetryTime) = decodeDeviceMetricsFromTelemetry(payload)
                MeshEvent.PeerTelemetry(
                    peer = peer,
                    batteryPercent = battery,
                    timestampSeconds = if (telemetryTime > 0) telemetryTime else rxTime,
                )
            }
            PORT_ALERT_APP -> {
                // The wire payload for ALERT_APP is open (see
                // docs/architecture/meshtastic-connection.md open question).
                // For now we accept either an embedded Position or no body
                // and use rxTime if no other timestamp is available.
                val lastKnown = if (payload.isEmpty()) null else runCatching {
                    decodePosition(payload, rxTime)
                }.getOrNull()
                MeshEvent.PeerAlert(
                    peer = peer,
                    lastKnownPosition = lastKnown,
                    timestampSeconds = lastKnown?.timestampSeconds ?: rxTime,
                )
            }
            else -> null
        }
    }

    private fun decodePosition(bytes: ByteArray, fallbackTimeSeconds: Long): PeerPosition.Fix? {
        if (bytes.isEmpty()) return null
        val reader = ProtoReader(bytes)
        var latI: Int? = null
        var lonI: Int? = null
        var altitude: Int? = null
        var positionTime: Long = 0
        var groundSpeed: Int? = null
        var groundTrack: Int? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                F_POSITION_LATITUDE_I -> if (wire == Proto.WIRE_FIXED32) latI = reader.readSfixed32() else reader.skipField(wire)
                F_POSITION_LONGITUDE_I -> if (wire == Proto.WIRE_FIXED32) lonI = reader.readSfixed32() else reader.skipField(wire)
                F_POSITION_ALTITUDE -> if (wire == Proto.WIRE_VARINT) altitude = reader.readInt32() else reader.skipField(wire)
                F_POSITION_TIME -> if (wire == Proto.WIRE_FIXED32) positionTime = reader.readFixed32() else reader.skipField(wire)
                F_POSITION_GROUND_SPEED -> if (wire == Proto.WIRE_VARINT) groundSpeed = reader.readInt32() else reader.skipField(wire)
                F_POSITION_GROUND_TRACK -> if (wire == Proto.WIRE_VARINT) groundTrack = reader.readInt32() else reader.skipField(wire)
                else -> reader.skipField(wire)
            }
        }
        if (latI == null || lonI == null) return null
        val lat = latI.toDouble() * 1e-7
        val lon = lonI.toDouble() * 1e-7
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return PeerPosition.Fix(
            latitudeDeg = lat,
            longitudeDeg = lon,
            altitudeMeters = altitude,
            groundSpeedMetersPerSecond = groundSpeed?.toDouble(),
            groundTrackDegrees = groundTrack?.toDouble(),
            timestampSeconds = if (positionTime > 0) positionTime else fallbackTimeSeconds,
        )
    }

    private data class DecodedUser(val id: String?, val longName: String?, val shortName: String?)

    private fun decodeUser(bytes: ByteArray): DecodedUser {
        if (bytes.isEmpty()) return DecodedUser(null, null, null)
        val reader = ProtoReader(bytes)
        var id: String? = null
        var longName: String? = null
        var shortName: String? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                F_USER_ID -> if (wire == Proto.WIRE_LENGTH_DELIMITED) id = reader.readString() else reader.skipField(wire)
                F_USER_LONG_NAME -> if (wire == Proto.WIRE_LENGTH_DELIMITED) longName = reader.readString() else reader.skipField(wire)
                F_USER_SHORT_NAME -> if (wire == Proto.WIRE_LENGTH_DELIMITED) shortName = reader.readString() else reader.skipField(wire)
                else -> reader.skipField(wire)
            }
        }
        return DecodedUser(id, longName, shortName)
    }

    private fun decodeNodeInfo(bytes: ByteArray): MeshEvent.PeerIdentityKnown? {
        val reader = ProtoReader(bytes)
        var num: Long? = null
        var userBytes: ByteArray? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                F_NODEINFO_NUM -> if (wire == Proto.WIRE_VARINT) num = reader.readVarint() else reader.skipField(wire)
                F_NODEINFO_USER -> if (wire == Proto.WIRE_LENGTH_DELIMITED) userBytes = reader.readLengthDelimited() else reader.skipField(wire)
                else -> reader.skipField(wire)
            }
        }
        val nodeNumber = num ?: return null
        val user = userBytes?.let { decodeUser(it) } ?: DecodedUser(null, null, null)
        return MeshEvent.PeerIdentityKnown(
            PeerIdentity.fromNodeNumber(
                nodeNumber = nodeNumber,
                longName = user.longName,
                shortName = user.shortName,
            ),
        )
    }

    /** Returns (battery%, telemetry time seconds) — both nullable / 0 when absent. */
    private fun decodeDeviceMetricsFromTelemetry(bytes: ByteArray): Pair<Int?, Long> {
        if (bytes.isEmpty()) return null to 0L
        val reader = ProtoReader(bytes)
        var time = 0L
        var dmBytes: ByteArray? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                F_TELEMETRY_TIME -> if (wire == Proto.WIRE_FIXED32) time = reader.readFixed32() else reader.skipField(wire)
                F_TELEMETRY_DEVICE_METRICS -> if (wire == Proto.WIRE_LENGTH_DELIMITED) dmBytes = reader.readLengthDelimited() else reader.skipField(wire)
                else -> reader.skipField(wire)
            }
        }
        val battery = dmBytes?.let { decodeBatteryLevel(it) }
        return battery to time
    }

    private fun decodeBatteryLevel(bytes: ByteArray): Int? {
        val reader = ProtoReader(bytes)
        var battery: Int? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                F_DEVICE_METRICS_BATTERY_LEVEL -> if (wire == Proto.WIRE_VARINT) battery = reader.readInt32() else reader.skipField(wire)
                else -> reader.skipField(wire)
            }
        }
        return battery
    }

    // ---------- outbound ----------

    /** Encode a `Position` protobuf body (no enclosing MeshPacket). */
    fun encodePositionPayload(fix: PeerPosition.Fix): ByteArray {
        val w = ProtoWriter()
        val latI = (fix.latitudeDeg * 1e7).toInt()
        val lonI = (fix.longitudeDeg * 1e7).toInt()
        w.writeSfixed32(F_POSITION_LATITUDE_I, latI)
        w.writeSfixed32(F_POSITION_LONGITUDE_I, lonI)
        fix.altitudeMeters?.let { w.writeInt32(F_POSITION_ALTITUDE, it) }
        if (fix.timestampSeconds > 0) {
            w.writeFixed32(F_POSITION_TIME, fix.timestampSeconds and 0xFFFFFFFFL)
        }
        fix.groundSpeedMetersPerSecond?.let { w.writeInt32(F_POSITION_GROUND_SPEED, it.toInt()) }
        fix.groundTrackDegrees?.let { w.writeInt32(F_POSITION_GROUND_TRACK, it.toInt()) }
        return w.toByteArray()
    }

    /** Encode a ToRadio frame carrying a position broadcast. */
    fun encodeToRadioPosition(fromNodeNumber: Long, packetId: Int, fix: PeerPosition.Fix): ByteArray {
        val payload = encodePositionPayload(fix)
        val data = ProtoWriter().apply {
            writeInt32(F_DATA_PORTNUM, PORT_POSITION_APP)
            writeBytes(F_DATA_PAYLOAD, payload)
        }.toByteArray()
        val packet = ProtoWriter().apply {
            writeFixed32(F_MESHPACKET_FROM, fromNodeNumber)
            writeFixed32(F_MESHPACKET_TO, BROADCAST_NODE_NUMBER)
            writeMessage(F_MESHPACKET_DECODED, data)
            writeFixed32(F_MESHPACKET_ID, packetId.toLong() and 0xFFFFFFFFL)
        }.toByteArray()
        return ProtoWriter().apply {
            writeMessage(F_TORADIO_PACKET, packet)
        }.toByteArray()
    }

    /**
     * Encode a ToRadio frame carrying an SOS alert.
     *
     * Per docs/architecture/meshtastic-connection.md: ALERT_APP, want_ack,
     * Priority.ALERT. Payload is the last-known Position (or empty if the
     * pilot has no fix at the time of SOS — the alert still goes out).
     */
    fun encodeToRadioAlert(
        fromNodeNumber: Long,
        packetId: Int,
        lastKnownPosition: PeerPosition.Fix?,
    ): ByteArray {
        val payload = lastKnownPosition?.let { encodePositionPayload(it) } ?: ByteArray(0)
        val data = ProtoWriter().apply {
            writeInt32(F_DATA_PORTNUM, PORT_ALERT_APP)
            writeBytes(F_DATA_PAYLOAD, payload)
        }.toByteArray()
        val packet = ProtoWriter().apply {
            writeFixed32(F_MESHPACKET_FROM, fromNodeNumber)
            writeFixed32(F_MESHPACKET_TO, BROADCAST_NODE_NUMBER)
            writeMessage(F_MESHPACKET_DECODED, data)
            writeFixed32(F_MESHPACKET_ID, packetId.toLong() and 0xFFFFFFFFL)
            writeBool(F_MESHPACKET_WANT_ACK, true)
            writeInt32(F_MESHPACKET_PRIORITY, PRIORITY_ALERT)
        }.toByteArray()
        return ProtoWriter().apply {
            writeMessage(F_TORADIO_PACKET, packet)
        }.toByteArray()
    }

    /**
     * Encode a ToRadio frame that asks the firmware to replay its
     * NodeInfo + config + nodeDB (its "config bundle") and then start
     * streaming subsequent packets. Without this request, the
     * Meshtastic phone protocol leaves the firmware quiet — the
     * initial FromRadio drain returns empty and no FromNum
     * notifications fire even when LoRa packets arrive. Required
     * once per BLE (or TCP) connect.
     *
     * The configId is an arbitrary 32-bit identifier the phone picks
     * to mark this config request — the firmware echoes it back in a
     * `config_complete_id` packet so the phone knows the bundle is
     * fully delivered. Any non-zero value works; we use a fixed
     * sentinel.
     */
    fun encodeWantConfigId(configId: Int): ByteArray =
        ProtoWriter().apply {
            writeInt32(F_TORADIO_WANT_CONFIG_ID, configId)
        }.toByteArray()
}
