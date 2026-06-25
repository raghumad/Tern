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
    const val PORT_ADMIN_APP = 6

    // AdminMessage field numbers — see meshtastic/admin.proto.
    private const val F_ADMIN_REBOOT_SECONDS = 97
    private const val F_ADMIN_SET_CHANNEL = 33
    private const val F_ADMIN_SET_CONFIG = 34

    // Config / LoRaConfig field numbers — see meshtastic/config.proto.
    private const val F_CONFIG_LORA = 6
    private const val F_LORA_USE_PRESET = 1
    private const val F_LORA_REGION = 7
    private const val F_LORA_HOP_LIMIT = 8
    private const val F_LORA_TX_ENABLED = 9

    /** Default mesh hop limit when we (re)write a full LoRaConfig. Matches Meshtastic's default. */
    private const val DEFAULT_HOP_LIMIT = 3

    // Channel field numbers — see meshtastic/channel.proto.
    private const val F_CHANNEL_INDEX = 1
    private const val F_CHANNEL_SETTINGS = 2
    private const val F_CHANNEL_ROLE = 3
    private const val CHANNEL_ROLE_PRIMARY = 1 // Channel.Role.PRIMARY

    // ChannelSettings field numbers — see meshtastic/channel.proto.
    private const val F_CHANNELSETTINGS_PSK = 2
    private const val F_CHANNELSETTINGS_NAME = 3

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
    private const val F_FROMRADIO_CONFIG = 5

    // ToRadio variant field numbers.
    private const val F_TORADIO_PACKET = 1
    private const val F_TORADIO_WANT_CONFIG_ID = 3
    private const val F_TORADIO_HEARTBEAT = 7

    // FromRadio variant field numbers used during the multi-stage
    // Meshtastic handshake. config_complete_id is the firmware's "I'm
    // done streaming this config bundle" marker — see
    // BleConnection's runHandshake.
    private const val F_FROMRADIO_CONFIG_COMPLETE_ID = 7

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
                F_FROMRADIO_CONFIG_COMPLETE_ID -> {
                    if (wire != Proto.WIRE_VARINT) {
                        reader.skipField(wire); continue
                    }
                    val configId = reader.readVarint().toInt()
                    return MeshEvent.ConfigComplete(configId)
                }
                else -> reader.skipField(wire)
            }
        }
        return null
    }

    /**
     * Read the board's current LoRa region from a `FromRadio.config` frame
     * (streamed as part of the handshake config bundle). Kept separate from
     * [decodeFromRadio] because region isn't a [MeshEvent] we surface — the
     * connection caches it to reconcile region against the pilot's GPS fix.
     *
     * Returns:
     *  - the region code, when this is the LoRa-config frame and the region
     *    field is present;
     *  - 0 (UNSET), when this IS the LoRa-config frame but the region field is
     *    absent — proto3 omits zero-valued scalars, so a fresh/unset board
     *    sends a `lora` sub-message with no `region` field;
     *  - null, when this frame is not a LoRa-config frame (a different config
     *    variant, a packet, nodeinfo, …) — the caller ignores it.
     *
     * The 0-vs-null distinction is load-bearing: 0 means "board reports UNSET,
     * reconcile it", null means "this frame says nothing about region".
     */
    fun decodeLoraRegion(fromRadioBytes: ByteArray): Int? {
        if (fromRadioBytes.isEmpty()) return null
        val reader = ProtoReader(fromRadioBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == F_FROMRADIO_CONFIG && wire == Proto.WIRE_LENGTH_DELIMITED) {
                return decodeLoraRegionFromConfig(reader.readLengthDelimited())
            }
            reader.skipField(wire)
        }
        return null
    }

    /** Pull `lora.region` out of a `Config` body, or null if this Config isn't the LoRa variant. */
    private fun decodeLoraRegionFromConfig(configBytes: ByteArray): Int? {
        val reader = ProtoReader(configBytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == F_CONFIG_LORA && wire == Proto.WIRE_LENGTH_DELIMITED) {
                return decodeRegionFromLora(reader.readLengthDelimited())
            }
            reader.skipField(wire)
        }
        return null
    }

    /** Read `region` from a `LoRaConfig` body; 0 (UNSET) when the field is omitted. */
    private fun decodeRegionFromLora(loraBytes: ByteArray): Int {
        val reader = ProtoReader(loraBytes)
        var region = 0
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == F_LORA_REGION && wire == Proto.WIRE_VARINT) {
                region = reader.readVarint().toInt()
            } else {
                reader.skipField(wire)
            }
        }
        return region
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
     * Encode a ToRadio frame that commands the board to reboot in
     * [rebootSeconds] seconds (an `AdminMessage.reboot_seconds` on
     * ADMIN_APP, addressed to the board itself).
     *
     * No admin session passkey is needed: the firmware's AdminModule skips
     * the passkey check when `MeshPacket.from == 0` (a locally-connected
     * phone over BLE is implicitly trusted). So we send `from = 0`.
     *
     * Used by the BLE reliability suite (T2/T3) to faithfully simulate a
     * "board rebooted mid-flight" drop — a real link loss + re-advertise +
     * reconnect — rather than a graceful local GATT disconnect.
     */
    fun encodeToRadioReboot(boardNodeNumber: Long, packetId: Int, rebootSeconds: Int): ByteArray {
        val admin = ProtoWriter().apply {
            writeInt32(F_ADMIN_REBOOT_SECONDS, rebootSeconds)
        }.toByteArray()
        val data = ProtoWriter().apply {
            writeInt32(F_DATA_PORTNUM, PORT_ADMIN_APP)
            writeBytes(F_DATA_PAYLOAD, admin)
        }.toByteArray()
        val packet = ProtoWriter().apply {
            writeFixed32(F_MESHPACKET_FROM, 0L) // from=0 → trusted local phone, no passkey
            writeFixed32(F_MESHPACKET_TO, boardNodeNumber)
            writeMessage(F_MESHPACKET_DECODED, data)
            writeFixed32(F_MESHPACKET_ID, packetId.toLong() and 0xFFFFFFFFL)
            writeBool(F_MESHPACKET_WANT_ACK, true)
        }.toByteArray()
        return ProtoWriter().apply {
            writeMessage(F_TORADIO_PACKET, packet)
        }.toByteArray()
    }

    /**
     * Encode a `ChannelSettings` body — just the two fields a Tern "team" needs: a shared [psk]
     * (the team secret) and a human [name]. This is what a team-share link carries and what
     * [encodeToRadioSetChannel] writes to the board. An empty [psk] means an unencrypted channel.
     */
    fun encodeChannelSettings(name: String, psk: ByteArray): ByteArray =
        ProtoWriter().apply {
            if (psk.isNotEmpty()) writeBytes(F_CHANNELSETTINGS_PSK, psk)
            writeString(F_CHANNELSETTINGS_NAME, name)
        }.toByteArray()

    /** Decode a `ChannelSettings` body to (name, psk). Null when no name field is present. */
    fun decodeChannelSettings(bytes: ByteArray): Pair<String, ByteArray>? {
        val reader = ProtoReader(bytes)
        var name: String? = null
        var psk = ByteArray(0)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                F_CHANNELSETTINGS_PSK ->
                    if (wire == Proto.WIRE_LENGTH_DELIMITED) psk = reader.readLengthDelimited() else reader.skipField(wire)
                F_CHANNELSETTINGS_NAME ->
                    if (wire == Proto.WIRE_LENGTH_DELIMITED) name = reader.readString() else reader.skipField(wire)
                else -> reader.skipField(wire)
            }
        }
        return name?.let { it to psk }
    }

    /**
     * Encode a ToRadio frame that sets the board's PRIMARY channel — Tern's **set_team**. An
     * `AdminMessage.set_channel` carrying `Channel{index=0, role=PRIMARY, settings={name, psk}}`
     * on ADMIN_APP, addressed to the board. `from = 0` → trusted local phone, no passkey (same
     * trust path as [encodeToRadioReboot]). After the board applies it, only boards sharing this
     * name+psk hear each other — i.e. they're now on the same team.
     */
    fun encodeToRadioSetChannel(boardNodeNumber: Long, packetId: Int, name: String, psk: ByteArray): ByteArray {
        val channel = ProtoWriter().apply {
            writeInt32(F_CHANNEL_INDEX, 0)
            writeMessage(F_CHANNEL_SETTINGS, encodeChannelSettings(name, psk))
            writeInt32(F_CHANNEL_ROLE, CHANNEL_ROLE_PRIMARY)
        }.toByteArray()
        val admin = ProtoWriter().apply {
            writeMessage(F_ADMIN_SET_CHANNEL, channel)
        }.toByteArray()
        val data = ProtoWriter().apply {
            writeInt32(F_DATA_PORTNUM, PORT_ADMIN_APP)
            writeBytes(F_DATA_PAYLOAD, admin)
        }.toByteArray()
        val packet = ProtoWriter().apply {
            writeFixed32(F_MESHPACKET_FROM, 0L) // from=0 → trusted local phone, no passkey
            writeFixed32(F_MESHPACKET_TO, boardNodeNumber)
            writeMessage(F_MESHPACKET_DECODED, data)
            writeFixed32(F_MESHPACKET_ID, packetId.toLong() and 0xFFFFFFFFL)
            writeBool(F_MESHPACKET_WANT_ACK, true)
        }.toByteArray()
        return ProtoWriter().apply {
            writeMessage(F_TORADIO_PACKET, packet)
        }.toByteArray()
    }

    /**
     * Encode a ToRadio frame that sets the board's LoRa **region** — the wire
     * half of Tern's automatic region-follows-location (US in the USA, EU in
     * Europe). An `AdminMessage.set_config` carrying a full `Config.lora` on
     * ADMIN_APP, addressed to the board. `from = 0` → trusted local phone, no
     * passkey (same trust path as [encodeToRadioReboot]).
     *
     * Why a *full* LoRaConfig and not just the region: the firmware's
     * `handleSetConfig` REPLACES the entire LoRaConfig struct with what we
     * send, so a region-only message would zero `use_preset` / `modem_preset`
     * / `hop_limit` / `tx_enabled`. We therefore send a complete, sane config:
     * `use_preset = true` (board derives bandwidth/SF/CR from the default
     * preset), the target [region], a default [hopLimit], and—critically—
     * `tx_enabled = true`. TX is asserted explicitly because the firmware only
     * auto-enables TX on the *first* set from UNSET; on a region *change*
     * (US→EU when the pilot travels) it leaves TX as sent, so omitting it
     * would silence the radio. `modem_preset` is left at its proto3 default
     * (0 = LONG_FAST), which matches a fresh board.
     */
    fun encodeToRadioSetLoraConfig(
        boardNodeNumber: Long,
        packetId: Int,
        region: Int,
        hopLimit: Int = DEFAULT_HOP_LIMIT,
    ): ByteArray {
        val lora = ProtoWriter().apply {
            writeBool(F_LORA_USE_PRESET, true)
            writeInt32(F_LORA_REGION, region)
            writeInt32(F_LORA_HOP_LIMIT, hopLimit)
            writeBool(F_LORA_TX_ENABLED, true)
        }.toByteArray()
        val config = ProtoWriter().apply {
            writeMessage(F_CONFIG_LORA, lora)
        }.toByteArray()
        val admin = ProtoWriter().apply {
            writeMessage(F_ADMIN_SET_CONFIG, config)
        }.toByteArray()
        val data = ProtoWriter().apply {
            writeInt32(F_DATA_PORTNUM, PORT_ADMIN_APP)
            writeBytes(F_DATA_PAYLOAD, admin)
        }.toByteArray()
        val packet = ProtoWriter().apply {
            writeFixed32(F_MESHPACKET_FROM, 0L) // from=0 → trusted local phone, no passkey
            writeFixed32(F_MESHPACKET_TO, boardNodeNumber)
            writeMessage(F_MESHPACKET_DECODED, data)
            writeFixed32(F_MESHPACKET_ID, packetId.toLong() and 0xFFFFFFFFL)
            writeBool(F_MESHPACKET_WANT_ACK, true)
        }.toByteArray()
        return ProtoWriter().apply {
            writeMessage(F_TORADIO_PACKET, packet)
        }.toByteArray()
    }

    /**
     * Encode a ToRadio frame that asks the firmware to replay its
     * config bundle and then start streaming subsequent packets.
     * Required at least once per BLE (or TCP) connect — the
     * Meshtastic phone protocol leaves the firmware quiet otherwise.
     *
     * The configId is an opaque 32-bit identifier; the firmware
     * echoes it back in a [MeshEvent.ConfigComplete] packet so the
     * handshake driver can match each stage's completion. We follow
     * the official Meshtastic-Android client and use two stages:
     *   - Stage 1: configId = [HANDSHAKE_CONFIG_NONCE]    → device + module config + channels
     *   - Stage 2: configId = [HANDSHAKE_NODE_INFO_NONCE] → full nodeDB
     */
    fun encodeWantConfigId(configId: Int): ByteArray =
        ProtoWriter().apply {
            writeInt32(F_TORADIO_WANT_CONFIG_ID, configId)
        }.toByteArray()

    /**
     * Encode an empty Heartbeat ToRadio frame. Sent immediately after
     * BLE connect to wake the firmware's phone-protocol state machine
     * before the [encodeWantConfigId] requests. Without this, the
     * official Meshtastic-Android client has observed the firmware
     * dropping the first want_config_id silently on some boards.
     * Body is empty (zero-length Heartbeat message).
     */
    fun encodeHeartbeat(): ByteArray =
        ProtoWriter().apply {
            writeMessage(F_TORADIO_HEARTBEAT, ByteArray(0))
        }.toByteArray()

    /**
     * Stage-1 nonce — matches `HandshakeConstants.CONFIG_NONCE` in the
     * official Meshtastic-Android client. Value is opaque/echoed, but
     * we use the same number so any debug-tooling that recognises it
     * (e.g. firmware logs) reads the same way.
     */
    const val HANDSHAKE_CONFIG_NONCE = 69420

    /** Stage-2 nonce — matches `HandshakeConstants.NODE_INFO_NONCE`. */
    const val HANDSHAKE_NODE_INFO_NONCE = 69421
}
