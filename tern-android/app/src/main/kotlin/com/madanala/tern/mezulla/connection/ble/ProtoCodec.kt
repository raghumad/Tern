package com.madanala.tern.mezulla.connection.ble

/**
 * Minimal hand-rolled protobuf wire-format codec.
 *
 * We deliberately do NOT pull the official Meshtastic protobuf schema +
 * codegen into the build for the WS4.3 skeleton. The dependency footprint
 * (protobuf-javalite + the `com.google.protobuf` Gradle plugin + generated
 * Kotlin classes for the full Meshtastic schema) is large compared to the
 * tiny subset of fields we actually consume (Position.latitude_i / .longitude_i
 * / .altitude / .time / .ground_speed / .ground_track; User.id / .long_name /
 * .short_name; DeviceMetrics.battery_level; PortNum byte; FromRadio.packet
 * variant; ToRadio.packet variant; MeshPacket.from / .id / .decoded /
 * .want_ack / .priority).
 *
 * Protobuf wire format is small and stable: three relevant wire types
 * (varint = 0, fixed32 = 5, length-delimited = 2). This file encodes only
 * what we need; if a future story needs more fields, extend here rather
 * than swapping to the official library, until the surface area justifies
 * the build complexity.
 *
 * If/when the surface gets large enough that hand-rolling becomes a bug
 * farm, the migration path is "delete this file, add the codegen plugin,
 * regenerate types" — the public API of [MeshPacketCodec] stays the same.
 */
internal object Proto {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_FIXED32 = 5

    /** Pack a (field_number, wire_type) into the tag byte sequence as a varint. */
    fun tag(fieldNumber: Int, wireType: Int): Int = (fieldNumber shl 3) or wireType
}

/**
 * Reads protobuf-encoded bytes field-by-field. Caller is responsible for
 * knowing the schema: there is no descriptor-driven decoding.
 *
 * Usage:
 * ```
 * val reader = ProtoReader(bytes)
 * while (reader.hasMore()) {
 *     val tag = reader.readTag()
 *     val fieldNumber = tag ushr 3
 *     val wireType = tag and 0x7
 *     when (fieldNumber) {
 *         1 -> readSomething(reader, wireType)
 *         else -> reader.skipField(wireType)
 *     }
 * }
 * ```
 */
internal class ProtoReader(private val bytes: ByteArray, start: Int = 0, endExclusive: Int = bytes.size) {

    private var pos: Int = start
    private val end: Int = endExclusive

    fun hasMore(): Boolean = pos < end

    fun readTag(): Int = readVarint().toInt()

    /** Read a varint as a Long (handles up to 64 bits). */
    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < end) { "varint past end of buffer" }
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint overflows 64 bits" }
        }
    }

    fun readInt32(): Int = readVarint().toInt()

    /**
     * Read sint32 (ZigZag-decoded). Not currently used by our subset but
     * included for future-proofing — Meshtastic uses sint for some fields.
     */
    fun readSint32(): Int {
        val n = readVarint().toInt()
        return (n ushr 1) xor -(n and 1)
    }

    fun readBool(): Boolean = readVarint() != 0L

    /** Read little-endian 32-bit unsigned, as Long to fit the unsigned range. */
    fun readFixed32(): Long {
        require(pos + 4 <= end) { "fixed32 past end of buffer" }
        val v = (
            (bytes[pos].toLong() and 0xFF)
                or ((bytes[pos + 1].toLong() and 0xFF) shl 8)
                or ((bytes[pos + 2].toLong() and 0xFF) shl 16)
                or ((bytes[pos + 3].toLong() and 0xFF) shl 24)
            )
        pos += 4
        return v
    }

    /** Read little-endian 32-bit signed. */
    fun readSfixed32(): Int = readFixed32().toInt()

    /** Read a length-delimited bytes payload. */
    fun readLengthDelimited(): ByteArray {
        val len = readVarint().toInt()
        require(len in 0..(end - pos)) { "length-delimited past end of buffer (len=$len)" }
        val out = ByteArray(len)
        System.arraycopy(bytes, pos, out, 0, len)
        pos += len
        return out
    }

    fun readString(): String = String(readLengthDelimited(), Charsets.UTF_8)

    /** Skip an unrecognised field by wire type. */
    fun skipField(wireType: Int) {
        when (wireType) {
            Proto.WIRE_VARINT -> readVarint()
            Proto.WIRE_FIXED32 -> pos += 4
            Proto.WIRE_FIXED64 -> pos += 8
            Proto.WIRE_LENGTH_DELIMITED -> {
                val len = readVarint().toInt()
                pos += len
            }
            else -> error("unsupported wire type $wireType")
        }
        require(pos <= end) { "skip ran past end of buffer" }
    }

    /**
     * Make a sub-reader over a length-delimited sub-message. Cheaper than
     * copying when we just want to iterate the bytes.
     */
    fun readMessage(): ProtoReader {
        val len = readVarint().toInt()
        require(len in 0..(end - pos)) { "sub-message length out of range (len=$len)" }
        val sub = ProtoReader(bytes, pos, pos + len)
        pos += len
        return sub
    }
}

/**
 * Writes protobuf-encoded bytes field-by-field. As with [ProtoReader] the
 * caller owns the schema knowledge.
 */
internal class ProtoWriter {

    private val out = java.io.ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint(Proto.tag(fieldNumber, wireType).toLong())
    }

    fun writeVarint(value: Long) {
        var v = value
        while (true) {
            val low7 = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(low7)
                return
            } else {
                out.write(low7 or 0x80)
            }
        }
    }

    fun writeInt32(fieldNumber: Int, value: Int) {
        writeTag(fieldNumber, Proto.WIRE_VARINT)
        writeVarint(value.toLong())
    }

    fun writeBool(fieldNumber: Int, value: Boolean) {
        writeTag(fieldNumber, Proto.WIRE_VARINT)
        writeVarint(if (value) 1L else 0L)
    }

    fun writeFixed32(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, Proto.WIRE_FIXED32)
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }

    fun writeSfixed32(fieldNumber: Int, value: Int) {
        writeFixed32(fieldNumber, value.toLong() and 0xFFFFFFFFL)
    }

    fun writeString(fieldNumber: Int, value: String) {
        writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(fieldNumber: Int, value: ByteArray) {
        writeTag(fieldNumber, Proto.WIRE_LENGTH_DELIMITED)
        writeVarint(value.size.toLong())
        out.write(value, 0, value.size)
    }

    /** Embed a nested message (already serialised) as a length-delimited field. */
    fun writeMessage(fieldNumber: Int, body: ByteArray) {
        writeBytes(fieldNumber, body)
    }
}
