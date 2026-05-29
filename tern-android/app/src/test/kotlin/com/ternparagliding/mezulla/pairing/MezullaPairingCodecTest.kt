package com.ternparagliding.mezulla.pairing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MezullaPairingCodecTest {

    @Test
    fun `claim packet starts with command byte and token length`() {
        val packet = MezullaPairingCodec.encodeClaimPacket("abcd1234", "phone-uuid-1")
        assertThat(packet[0]).isEqualTo(MezullaPairingCodec.CMD_CLAIM)
        assertThat(packet[1]).isEqualTo(8) // "abcd1234" is 8 bytes
    }

    @Test
    fun `claim packet contains token then owner`() {
        val packet = MezullaPairingCodec.encodeClaimPacket("AABB", "me")
        // [0x01, 0x04, 'A', 'A', 'B', 'B', 'm', 'e']
        assertThat(packet.size).isEqualTo(8)
        val token = String(packet, 2, 4, Charsets.UTF_8)
        val owner = String(packet, 6, 2, Charsets.UTF_8)
        assertThat(token).isEqualTo("AABB")
        assertThat(owner).isEqualTo("me")
    }

    @Test
    fun `query packet is single byte`() {
        val packet = MezullaPairingCodec.encodeQueryPacket()
        assertThat(packet).isEqualTo(byteArrayOf(MezullaPairingCodec.CMD_QUERY))
    }

    @Test
    fun `decode OK response with owner`() {
        val bytes = byteArrayOf(0x00) + "phone-123".toByteArray()
        val response = MezullaPairingCodec.decodeResponse(bytes)
        assertThat(response.status).isEqualTo(PairingStatus.OK)
        assertThat(response.ownerId).isEqualTo("phone-123")
    }

    @Test
    fun `decode token mismatch response`() {
        val bytes = byteArrayOf(0x01)
        val response = MezullaPairingCodec.decodeResponse(bytes)
        assertThat(response.status).isEqualTo(PairingStatus.TOKEN_MISMATCH)
        assertThat(response.ownerId).isEmpty()
    }

    @Test
    fun `decode already claimed response with owner`() {
        val bytes = byteArrayOf(0x02) + "other-phone".toByteArray()
        val response = MezullaPairingCodec.decodeResponse(bytes)
        assertThat(response.status).isEqualTo(PairingStatus.ALREADY_CLAIMED)
        assertThat(response.ownerId).isEqualTo("other-phone")
    }

    @Test
    fun `decode empty response returns UNKNOWN`() {
        val response = MezullaPairingCodec.decodeResponse(ByteArray(0))
        assertThat(response.status).isEqualTo(PairingStatus.UNKNOWN)
    }

    @Test
    fun `decode unknown status byte returns UNKNOWN`() {
        val response = MezullaPairingCodec.decodeResponse(byteArrayOf(0x7F))
        assertThat(response.status).isEqualTo(PairingStatus.UNKNOWN)
    }

    @Test
    fun `token longer than 255 bytes is rejected`() {
        val longToken = "a".repeat(256)
        assertThrows<IllegalArgumentException> {
            MezullaPairingCodec.encodeClaimPacket(longToken, "owner")
        }
    }

    @Test
    fun `claim round-trip preserves token and owner`() {
        val token = "e7f3a1b2c4d5e6f78901a2b3c4d5e6f7"
        val owner = "tern-install-uuid-v4-here"
        val packet = MezullaPairingCodec.encodeClaimPacket(token, owner)

        // Verify we can parse it back (simulating what the board does)
        assertThat(packet[0]).isEqualTo(0x01.toByte())
        val tokenLen = packet[1].toInt() and 0xFF
        val parsedToken = String(packet, 2, tokenLen, Charsets.UTF_8)
        val parsedOwner = String(packet, 2 + tokenLen, packet.size - 2 - tokenLen, Charsets.UTF_8)
        assertThat(parsedToken).isEqualTo(token)
        assertThat(parsedOwner).isEqualTo(owner)
    }

    @Test
    fun `ToRadio private app packet is valid protobuf structure`() {
        val payload = MezullaPairingCodec.encodeClaimPacket("abc", "me")
        val frame = MezullaPairingCodec.encodeToRadioPrivateApp(
            fromNodeNumber = 0x12345678L,
            toNodeNumber = 0x4a312aaaL,
            packetId = 42,
            payload = payload,
        )
        assertThat(frame).isNotEmpty()
        // The frame should start with a varint tag for ToRadio.packet (field 1, wire type 2)
        assertThat(frame[0].toInt() and 0xFF).isEqualTo(0x0A) // field 1, length-delimited
    }
}
