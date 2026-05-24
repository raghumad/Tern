package com.madanala.tern.mezulla.connection.tcp

/**
 * Encoders / decoders for the Meshtastic protobuf subset Tern needs.
 *
 * Only the fields actually read or written on the TCP path are surfaced.
 * Adding a field is mechanical: pick its number and wire type from
 * `meshtastic.proto` upstream, add a `writeXxx` / `readXxx` in the right
 * builder. The codec layer in [Protobuf] handles wire-format details.
 *
 * Field numbers are pinned by Meshtastic's published `.proto` schema —
 * they will not change without a major Meshtastic version bump. Reference:
 * https://github.com/meshtastic/protobufs
 *
 * What is intentionally not implemented:
 *  - Encrypted-payload path. Meshtastic supports `MeshPacket.encrypted`
 *    bytes alongside the plaintext `decoded` sub-message. Tern speaks to
 *    its own paired board over a local TCP socket; the board does the
 *    LoRa-side encryption. We exchange plaintext over TCP.
 *  - DeviceMetrics fields beyond `battery_level`. The interface only
 *    surfaces battery percent (see `MeshEvent.PeerTelemetry`).
 *  - The full PortNum enum. We branch on the four ports the interface
 *    cares about and ignore the rest.
 */
internal object MeshtasticProtos {

    /** PortNum values we recognise on incoming MeshPackets. */
    const val PORT_TEXT_MESSAGE = 1
    const val PORT_POSITION = 3
    const val PORT_NODEINFO = 4
    const val PORT_ALERT = 11
    const val PORT_TELEMETRY = 67

    /** Priority values relevant to outbound packets. */
    const val PRIORITY_ALERT = 110

    /**
     * Position lat/lon scale. Meshtastic stores latitude / longitude as
     * sfixed32 integers in tens of nanodegrees (i.e. degrees * 1e7).
     */
    const val POSITION_SCALE = 1e7
}

// --- Position --------------------------------------------------------------

/**
 * A subset of Meshtastic's `Position` message.
 *
 * Field numbers and wire types from meshtastic.proto:
 *  - 1 sfixed32 latitude_i  (degrees * 1e7)
 *  - 2 sfixed32 longitude_i (degrees * 1e7)
 *  - 3 int32    altitude    (meters above MSL)
 *  - 4 fixed32  time        (unix seconds)
 *  - 9 uint32   ground_speed  (m/s, integer)
 *  - 10 uint32  ground_track  (degrees, integer)
 */
internal data class ProtoPosition(
    val latitudeI: Int = 0,
    val longitudeI: Int = 0,
    val altitudeMeters: Int? = null,
    val timeSeconds: Int = 0,
    val groundSpeedMps: Int? = null,
    val groundTrackDeg: Int? = null,
) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.writeSFixed32(1, latitudeI)
        w.writeSFixed32(2, longitudeI)
        if (altitudeMeters != null) w.writeUInt32(3, altitudeMeters)
        w.writeFixed32(4, timeSeconds)
        if (groundSpeedMps != null) w.writeUInt32(9, groundSpeedMps)
        if (groundTrackDeg != null) w.writeUInt32(10, groundTrackDeg)
        return w.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): ProtoPosition {
            val r = ProtoReader(bytes)
            var latI = 0
            var lonI = 0
            var alt: Int? = null
            var time = 0
            var gs: Int? = null
            var gt: Int? = null
            while (r.hasMore()) {
                val (fieldNumber, wireType) = r.readTag()
                when (fieldNumber) {
                    1 -> latI = r.readSFixed32()
                    2 -> lonI = r.readSFixed32()
                    3 -> alt = r.readUInt32()
                    4 -> time = r.readFixed32()
                    9 -> gs = r.readUInt32()
                    10 -> gt = r.readUInt32()
                    else -> r.skipField(wireType)
                }
            }
            return ProtoPosition(latI, lonI, alt, time, gs, gt)
        }
    }
}

// --- User (NodeInfo payload) ----------------------------------------------

/**
 * A subset of Meshtastic's `User` message (what `NODEINFO_APP` carries).
 *
 *  - 1 string id          ("!aabbccdd" form, but we also derive from node_num)
 *  - 2 string long_name
 *  - 3 string short_name
 */
internal data class ProtoUser(
    val id: String = "",
    val longName: String = "",
    val shortName: String = "",
) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.writeString(1, id)
        w.writeString(2, longName)
        w.writeString(3, shortName)
        return w.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): ProtoUser {
            val r = ProtoReader(bytes)
            var id = ""
            var longName = ""
            var shortName = ""
            while (r.hasMore()) {
                val (fieldNumber, wireType) = r.readTag()
                when (fieldNumber) {
                    1 -> id = r.readString()
                    2 -> longName = r.readString()
                    3 -> shortName = r.readString()
                    else -> r.skipField(wireType)
                }
            }
            return ProtoUser(id, longName, shortName)
        }
    }
}

// --- DeviceMetrics --------------------------------------------------------

/**
 * Subset of `DeviceMetrics`. The interface only surfaces battery percent;
 * other fields are decoded only enough to be skipped without erroring.
 *
 *  - 1 uint32 battery_level
 */
internal data class ProtoDeviceMetrics(
    val batteryPercent: Int? = null,
) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
        if (batteryPercent != null) w.writeUInt32(1, batteryPercent)
        return w.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): ProtoDeviceMetrics {
            val r = ProtoReader(bytes)
            var battery: Int? = null
            while (r.hasMore()) {
                val (fieldNumber, wireType) = r.readTag()
                when (fieldNumber) {
                    1 -> battery = r.readUInt32()
                    else -> r.skipField(wireType)
                }
            }
            return ProtoDeviceMetrics(battery)
        }
    }
}

// --- Telemetry ------------------------------------------------------------

/**
 * Subset of `Telemetry`.
 *
 *  - 1 fixed32 time
 *  - 2 DeviceMetrics device_metrics
 */
internal data class ProtoTelemetry(
    val timeSeconds: Int = 0,
    val deviceMetrics: ProtoDeviceMetrics? = null,
) {
    companion object {
        fun decode(bytes: ByteArray): ProtoTelemetry {
            val r = ProtoReader(bytes)
            var time = 0
            var dm: ProtoDeviceMetrics? = null
            while (r.hasMore()) {
                val (fieldNumber, wireType) = r.readTag()
                when (fieldNumber) {
                    1 -> time = r.readFixed32()
                    2 -> dm = ProtoDeviceMetrics.decode(r.readBytes())
                    else -> r.skipField(wireType)
                }
            }
            return ProtoTelemetry(time, dm)
        }
    }
}

// --- Data (decoded MeshPacket payload) -------------------------------------

/**
 * Subset of Meshtastic's `Data` message — the decoded sub-message inside
 * a `MeshPacket`.
 *
 *  - 1 PortNum portnum
 *  - 2 bytes   payload
 */
internal data class ProtoData(
    val portnum: Int = 0,
    val payload: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.writeEnum(1, portnum)
        w.writeBytes(2, payload)
        return w.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): ProtoData {
            val r = ProtoReader(bytes)
            var portnum = 0
            var payload = ByteArray(0)
            while (r.hasMore()) {
                val (fieldNumber, wireType) = r.readTag()
                when (fieldNumber) {
                    1 -> portnum = r.readEnum()
                    2 -> payload = r.readBytes()
                    else -> r.skipField(wireType)
                }
            }
            return ProtoData(portnum, payload)
        }
    }

    // Data classes with ByteArray fields need custom equals/hashCode for
    // tests to compare payloads structurally.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtoData) return false
        return portnum == other.portnum && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int = 31 * portnum + payload.contentHashCode()
}

// --- MeshPacket -----------------------------------------------------------

/**
 * Subset of Meshtastic's `MeshPacket`.
 *
 *  - 1 fixed32 from        (sender node number)
 *  - 2 fixed32 to          (destination node number; 0xFFFFFFFF = broadcast)
 *  - 3 uint32  channel
 *  - 4 Data    decoded     (plaintext path)
 *  - 6 fixed32 id          (packet id)
 *  - 7 bool    want_ack
 *  - 9 fixed32 rx_time
 *  - 11 Priority priority
 *
 * Note: field 6 is `id`, field 7 is `want_ack`. This is intentional in the
 * upstream schema (history of additions).
 */
internal data class ProtoMeshPacket(
    val from: Int = 0,
    val to: Int = 0,
    val channel: Int = 0,
    val decoded: ProtoData? = null,
    val id: Int = 0,
    val wantAck: Boolean = false,
    val rxTime: Int = 0,
    val priority: Int = 0,
) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.writeFixed32(1, from)
        w.writeFixed32(2, to)
        w.writeUInt32(3, channel)
        if (decoded != null) w.writeMessage(4, decoded.encode())
        w.writeFixed32(6, id)
        w.writeBool(7, wantAck)
        w.writeFixed32(9, rxTime)
        w.writeEnum(11, priority)
        return w.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): ProtoMeshPacket {
            val r = ProtoReader(bytes)
            var from = 0; var to = 0; var channel = 0
            var decoded: ProtoData? = null
            var id = 0; var wantAck = false; var rxTime = 0; var priority = 0
            while (r.hasMore()) {
                val (fieldNumber, wireType) = r.readTag()
                when (fieldNumber) {
                    1 -> from = r.readFixed32()
                    2 -> to = r.readFixed32()
                    3 -> channel = r.readUInt32()
                    4 -> decoded = ProtoData.decode(r.readBytes())
                    6 -> id = r.readFixed32()
                    7 -> wantAck = r.readBool()
                    9 -> rxTime = r.readFixed32()
                    11 -> priority = r.readEnum()
                    else -> r.skipField(wireType)
                }
            }
            return ProtoMeshPacket(from, to, channel, decoded, id, wantAck, rxTime, priority)
        }
    }
}

// --- NodeInfo (FromRadio's standalone NodeInfo message) -------------------

/**
 * Subset of Meshtastic's `NodeInfo` message. Distinct from a `MeshPacket`
 * carrying a `NODEINFO_APP` packet — `FromRadio.node_info` is the board's
 * NodeDB dump on connect; `NODEINFO_APP` packets are over-the-air
 * announcements heard from peers. Both carry a `User` payload.
 *
 *  - 1 uint32 num   (node number)
 *  - 4 User   user
 *  - 6 Position position
 */
internal data class ProtoNodeInfo(
    val num: Int = 0,
    val user: ProtoUser? = null,
    val position: ProtoPosition? = null,
) {
    companion object {
        fun decode(bytes: ByteArray): ProtoNodeInfo {
            val r = ProtoReader(bytes)
            var num = 0
            var user: ProtoUser? = null
            var pos: ProtoPosition? = null
            while (r.hasMore()) {
                val (fieldNumber, wireType) = r.readTag()
                when (fieldNumber) {
                    1 -> num = r.readUInt32()
                    4 -> user = ProtoUser.decode(r.readBytes())
                    6 -> pos = ProtoPosition.decode(r.readBytes())
                    else -> r.skipField(wireType)
                }
            }
            return ProtoNodeInfo(num, user, pos)
        }
    }
}

// --- FromRadio / ToRadio ---------------------------------------------------

/**
 * Subset of `FromRadio`, the envelope the board sends to us.
 *
 * It is a oneof in the schema; only one of the variants is set per
 * message. We surface the two we react to:
 *
 *  - 2 MeshPacket packet
 *  - 4 NodeInfo   node_info
 *
 * Other variants (`my_info`, `config`, `log_record`, `queue_status`,
 * `metadata`, `mqtt_proxy_message`, …) are decoded as "unknown" so we
 * don't crash, and ignored by the connection layer.
 */
internal data class ProtoFromRadio(
    val packet: ProtoMeshPacket? = null,
    val nodeInfo: ProtoNodeInfo? = null,
) {
    companion object {
        fun decode(bytes: ByteArray): ProtoFromRadio {
            val r = ProtoReader(bytes)
            var packet: ProtoMeshPacket? = null
            var nodeInfo: ProtoNodeInfo? = null
            while (r.hasMore()) {
                val (fieldNumber, wireType) = r.readTag()
                when (fieldNumber) {
                    2 -> packet = ProtoMeshPacket.decode(r.readBytes())
                    4 -> nodeInfo = ProtoNodeInfo.decode(r.readBytes())
                    else -> r.skipField(wireType)
                }
            }
            return ProtoFromRadio(packet, nodeInfo)
        }
    }
}

/**
 * Subset of `ToRadio`, the envelope we send to the board.
 *
 *  - 1 MeshPacket packet
 *  - 3 uint32     want_config_id
 *
 * For TCP we send `want_config_id` once on connect so the board replays
 * its `NodeInfo` table (gives us names for peers we've heard before
 * without waiting for a fresh over-the-air announcement).
 */
internal object ProtoToRadio {
    fun encodePacket(packet: ProtoMeshPacket): ByteArray {
        val w = ProtoWriter()
        w.writeMessage(1, packet.encode())
        return w.toByteArray()
    }

    fun encodeWantConfigId(configId: Int): ByteArray {
        val w = ProtoWriter()
        w.writeUInt32(3, configId)
        return w.toByteArray()
    }
}
