package com.ternparagliding.mezulla.connection.tcp

/**
 * Hand-rolled minimal Protobuf 3 wire-format codec.
 *
 * We use this instead of pulling in `protobuf-javalite` + a codegen plugin
 * for two reasons:
 *
 *  1. The build.gradle.kts coupling. Adding the protobuf plugin and the
 *     `.proto` files would touch shared build config that this workstream
 *     (WS4.5) is not supposed to modify.
 *  2. We need a tiny subset — five message types, ~15 fields total — and
 *     the wire format is small enough that hand-rolling is comparable in
 *     effort to writing the proto files and wiring codegen.
 *
 * Implements just the wire types we use:
 *  - varint (uint32, uint64, int32, bool, enum)
 *  - length-delimited (string, bytes, sub-message)
 *  - fixed32 (uint32, fixed32 timestamps)
 *  - sfixed32 (Position's latitude_i / longitude_i)
 *
 * Wire format reference: https://protobuf.dev/programming-guides/encoding/
 *
 * Not thread-safe. Each [ProtoWriter] / [ProtoReader] is single-use.
 */
internal object Protobuf {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_FIXED32 = 5
}

/**
 * Writes protobuf-encoded fields to an internal byte buffer.
 *
 * Field tags are encoded as `(fieldNumber shl 3) or wireType`. Each `write*`
 * helper takes the field number and the value, and appends the tag + value
 * bytes.
 */
internal class ProtoWriter {
    private val buf = java.io.ByteArrayOutputStream()

    fun toByteArray(): ByteArray = buf.toByteArray()

    fun writeUInt32(fieldNumber: Int, value: Int) {
        if (value == 0) return // proto3 default — skip
        writeTag(fieldNumber, Protobuf.WIRE_VARINT)
        writeVarint(value.toLong() and 0xFFFFFFFFL)
    }

    fun writeUInt64(fieldNumber: Int, value: Long) {
        if (value == 0L) return
        writeTag(fieldNumber, Protobuf.WIRE_VARINT)
        writeVarint(value)
    }

    fun writeBool(fieldNumber: Int, value: Boolean) {
        if (!value) return
        writeTag(fieldNumber, Protobuf.WIRE_VARINT)
        writeVarint(1)
    }

    fun writeEnum(fieldNumber: Int, value: Int) {
        if (value == 0) return
        writeTag(fieldNumber, Protobuf.WIRE_VARINT)
        writeVarint(value.toLong())
    }

    fun writeFixed32(fieldNumber: Int, value: Int) {
        if (value == 0) return
        writeTag(fieldNumber, Protobuf.WIRE_FIXED32)
        writeRawFixed32(value)
    }

    fun writeSFixed32(fieldNumber: Int, value: Int) {
        if (value == 0) return
        writeTag(fieldNumber, Protobuf.WIRE_FIXED32)
        writeRawFixed32(value)
    }

    fun writeString(fieldNumber: Int, value: String?) {
        if (value.isNullOrEmpty()) return
        writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(fieldNumber: Int, value: ByteArray) {
        if (value.isEmpty()) return
        writeTag(fieldNumber, Protobuf.WIRE_LENGTH_DELIMITED)
        writeVarint(value.size.toLong())
        buf.write(value)
    }

    /** Encodes the sub-message and writes it as a length-delimited field. */
    fun writeMessage(fieldNumber: Int, body: ByteArray) {
        // Empty sub-messages are still valid to send (proto3); we always emit
        // them when explicitly requested by the caller. Callers skip writing
        // null sub-messages themselves.
        writeTag(fieldNumber, Protobuf.WIRE_LENGTH_DELIMITED)
        writeVarint(body.size.toLong())
        buf.write(body)
    }

    private fun writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint(((fieldNumber.toLong()) shl 3) or wireType.toLong())
    }

    private fun writeVarint(v: Long) {
        var value = v
        while (true) {
            if ((value and 0x7FL.inv()) == 0L) {
                buf.write(value.toInt() and 0xFF)
                return
            }
            buf.write(((value and 0x7FL) or 0x80L).toInt())
            value = value ushr 7
        }
    }

    private fun writeRawFixed32(value: Int) {
        buf.write(value and 0xFF)
        buf.write((value ushr 8) and 0xFF)
        buf.write((value ushr 16) and 0xFF)
        buf.write((value ushr 24) and 0xFF)
    }
}

/**
 * Reads protobuf-encoded fields from a byte array.
 *
 * Usage: in a loop, call [hasMore]; if true, call [readTag] to get the next
 * `(fieldNumber, wireType)`, then call the matching `read*` for the wire
 * type, or [skipField] to ignore an unknown field. Unknown fields must be
 * skipped, not errored — proto3 forward-compat depends on it.
 */
internal class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    fun hasMore(): Boolean = pos < data.size

    /** Returns Pair(fieldNumber, wireType). */
    fun readTag(): Pair<Int, Int> {
        val raw = readVarint()
        val fieldNumber = (raw ushr 3).toInt()
        val wireType = (raw and 0x7L).toInt()
        return fieldNumber to wireType
    }

    fun readUInt32(): Int = readVarint().toInt()

    fun readUInt64(): Long = readVarint()

    fun readBool(): Boolean = readVarint() != 0L

    fun readEnum(): Int = readVarint().toInt()

    fun readFixed32(): Int = readRawFixed32()

    fun readSFixed32(): Int = readRawFixed32()

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        require(len >= 0) { "Negative length-delimited size: $len" }
        require(pos + len <= data.size) { "Truncated length-delimited field" }
        val out = data.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    /**
     * Skip a field whose value we don't care about. Required for forward
     * compatibility — Meshtastic can add new fields without breaking us.
     */
    fun skipField(wireType: Int) {
        when (wireType) {
            Protobuf.WIRE_VARINT -> readVarint()
            Protobuf.WIRE_FIXED64 -> { pos += 8 }
            Protobuf.WIRE_LENGTH_DELIMITED -> {
                val len = readVarint().toInt()
                pos += len
            }
            Protobuf.WIRE_FIXED32 -> { pos += 4 }
            else -> throw IllegalArgumentException("Unknown wire type: $wireType")
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < data.size) { "Truncated varint" }
            val b = data[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            require(shift < 64) { "Varint too long" }
        }
    }

    private fun readRawFixed32(): Int {
        require(pos + 4 <= data.size) { "Truncated fixed32" }
        val v = (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }
}
