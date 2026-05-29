package com.ternparagliding.mezulla.connection.tcp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the hand-rolled protobuf wire-format codec.
 *
 * These tests assert wire-format compatibility against known bytes from
 * the protobuf encoding spec, not just self-consistency of writer →
 * reader. If we round-trip but the bytes don't match what other protobuf
 * implementations would produce, we'll silently produce non-interop
 * frames at runtime. The known-bytes anchors catch that.
 *
 * Reference: https://protobuf.dev/programming-guides/encoding/
 */
class ProtobufTest {

    @Test
    fun `varint encoding of small uint matches spec`() {
        // Field 1, uint32, value 150.
        // Spec example: 0x08 0x96 0x01
        val w = ProtoWriter()
        w.writeUInt32(1, 150)
        assertThat(w.toByteArray()).isEqualTo(byteArrayOf(0x08, 0x96.toByte(), 0x01))
    }

    @Test
    fun `proto3 default uint is omitted from the wire`() {
        val w = ProtoWriter()
        w.writeUInt32(1, 0)
        assertThat(w.toByteArray()).isEqualTo(ByteArray(0))
    }

    @Test
    fun `fixed32 encoding is little-endian`() {
        // Field 1, fixed32, value 0x12345678 → 0x0D 0x78 0x56 0x34 0x12
        val w = ProtoWriter()
        w.writeFixed32(1, 0x12345678)
        assertThat(w.toByteArray()).isEqualTo(
            byteArrayOf(
                0x0D,                  // (1 shl 3) or 5 = 0x0D
                0x78, 0x56, 0x34, 0x12,
            ),
        )
    }

    @Test
    fun `sfixed32 of a negative number round-trips through reader`() {
        val w = ProtoWriter()
        w.writeSFixed32(1, -1)
        val r = ProtoReader(w.toByteArray())
        val (field, wire) = r.readTag()
        assertThat(field).isEqualTo(1)
        assertThat(wire).isEqualTo(Protobuf.WIRE_FIXED32)
        assertThat(r.readSFixed32()).isEqualTo(-1)
    }

    @Test
    fun `string encoding includes length prefix`() {
        // Field 2, string, value "hi" → 0x12 0x02 'h' 'i'
        val w = ProtoWriter()
        w.writeString(2, "hi")
        assertThat(w.toByteArray()).isEqualTo(
            byteArrayOf(0x12, 0x02, 'h'.code.toByte(), 'i'.code.toByte()),
        )
    }

    @Test
    fun `unknown fields are skipped without throwing`() {
        // Build a message with one field the reader knows and one it
        // doesn't. The reader should skip the unknown and surface the
        // known field.
        val w = ProtoWriter()
        w.writeString(99, "ignored-by-reader") // unknown high field number
        w.writeUInt32(1, 42)

        val bytes = w.toByteArray()
        val r = ProtoReader(bytes)
        var found = 0
        while (r.hasMore()) {
            val (field, wire) = r.readTag()
            when (field) {
                1 -> found = r.readUInt32()
                else -> r.skipField(wire)
            }
        }
        assertThat(found).isEqualTo(42)
    }

    @Test
    fun `Position round-trips lat lon altitude and time`() {
        val original = ProtoPosition(
            latitudeI = 459_099_000,    // ~45.9099°
            longitudeI = 61_245_000,    // ~6.1245°
            altitudeMeters = 2400,
            timeSeconds = 1_700_000_000,
            groundSpeedMps = 9,
            groundTrackDeg = 270,
        )

        val decoded = ProtoPosition.decode(original.encode())

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `Position handles southern and western hemisphere via negative sfixed32`() {
        // ~ -33.4125° lat, -70.6483° lon — Santiago, Chile.
        val original = ProtoPosition(
            latitudeI = -334_125_000,
            longitudeI = -706_483_000,
            altitudeMeters = 1234,
            timeSeconds = 1_700_000_000,
        )

        val decoded = ProtoPosition.decode(original.encode())

        assertThat(decoded.latitudeI).isEqualTo(-334_125_000)
        assertThat(decoded.longitudeI).isEqualTo(-706_483_000)
    }

    @Test
    fun `User round-trips id long_name and short_name`() {
        val original = ProtoUser(
            id = "!a1b2c3d4",
            longName = "Antoine",
            shortName = "AN",
        )

        val decoded = ProtoUser.decode(original.encode())

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `MeshPacket containing a Position payload round-trips`() {
        val pos = ProtoPosition(latitudeI = 100_000_000, longitudeI = 50_000_000, timeSeconds = 5)
        val original = ProtoMeshPacket(
            from = 0xa1b2c3d4.toInt(),
            to = -1, // broadcast
            decoded = ProtoData(
                portnum = MeshtasticProtos.PORT_POSITION,
                payload = pos.encode(),
            ),
            id = 42,
        )

        val decoded = ProtoMeshPacket.decode(original.encode())

        assertThat(decoded.from).isEqualTo(0xa1b2c3d4.toInt())
        assertThat(decoded.to).isEqualTo(-1)
        assertThat(decoded.id).isEqualTo(42)
        assertThat(decoded.decoded?.portnum).isEqualTo(MeshtasticProtos.PORT_POSITION)

        val payloadPos = ProtoPosition.decode(decoded.decoded!!.payload)
        assertThat(payloadPos).isEqualTo(pos)
    }

    @Test
    fun `FromRadio with a packet field decodes the MeshPacket`() {
        val pos = ProtoPosition(latitudeI = 10, longitudeI = 20, timeSeconds = 1)
        val packet = ProtoMeshPacket(
            from = 0x100,
            decoded = ProtoData(MeshtasticProtos.PORT_POSITION, pos.encode()),
        )

        // FromRadio.packet = field 2, length-delimited.
        val w = ProtoWriter()
        w.writeMessage(2, packet.encode())
        val bytes = w.toByteArray()

        val fr = ProtoFromRadio.decode(bytes)
        assertThat(fr.packet).isNotNull()
        assertThat(fr.packet?.from).isEqualTo(0x100)
        val payloadPos = ProtoPosition.decode(fr.packet!!.decoded!!.payload)
        assertThat(payloadPos.latitudeI).isEqualTo(10)
    }

    @Test
    fun `ToRadio encodePacket wraps the packet under field 1`() {
        val packet = ProtoMeshPacket(from = 0x123, id = 7)
        val bytes = ProtoToRadio.encodePacket(packet)

        // Field 1, length-delimited = tag byte 0x0A.
        assertThat(bytes[0]).isEqualTo(0x0A.toByte())
        // Skip tag and length, decode the inner.
        val inner = ProtoMeshPacket.decode(bytes.copyOfRange(2, bytes.size))
        assertThat(inner.from).isEqualTo(0x123)
        assertThat(inner.id).isEqualTo(7)
    }
}
