package com.ternparagliding.mezulla.pairing

import com.ternparagliding.mezulla.connection.ble.ProtoWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two halves of the deterministic pairing fix:
 *  - the QR carries the board's BLE MAC (`m=`) so we connect to the RIGHT board, and
 *  - the board's claim reply is read so we know it ACTUALLY accepted us (and which node replied).
 *
 * Both were absent before — pairing connected to the first board it saw and
 * reported success without ever hearing back. Real values from the bench
 * Heltec (node 0x160435c6, advertised address 3C:0F:02:ED:85:31).
 */
class PairingIdentityTest {

    // -- QR m= (BLE MAC) parsing --------------------------------------------

    @Test
    fun `link parses the BLE MAC into getRemoteDevice form`() {
        val link = TernPairLink.parse("tern://p?n=160435c6&t=ad454cdc&m=3c0f02ed8531")!!
        assertEquals("160435c6", link.nodeIdHex)
        assertEquals("ad454cdc", link.pairingToken)
        assertEquals("3C:0F:02:ED:85:31", link.bleMac)
    }

    @Test
    fun `a legacy link without m= still parses, with null MAC`() {
        val link = TernPairLink.parse("tern://p?n=160435c6&t=ad454cdc")!!
        assertNull(link.bleMac)
    }

    @Test
    fun `a malformed m= is ignored, not fatal — falls back to scanning`() {
        // Too short / non-hex MAC shouldn't reject the whole link.
        assertNull(TernPairLink.parse("tern://p?n=160435c6&t=ad454cdc&m=zzzz")!!.bleMac)
        assertNull(TernPairLink.parse("tern://p?n=160435c6&t=ad454cdc&m=3c0f02ed85")!!.bleMac)
    }

    @Test
    fun `formatMac inserts colons and uppercases, rejects bad input`() {
        assertEquals("3C:0F:02:ED:85:31", TernPairLink.formatMac("3c0f02ed8531"))
        assertNull(TernPairLink.formatMac("3c0f02ed85"))   // 10 hex
        assertNull(TernPairLink.formatMac("3c0f02ed85zz")) // non-hex
    }

    // -- claim reply decoding (from a raw FromRadio frame) ------------------

    /** Build a FromRadio frame the way the firmware/framework would for a reply. */
    private fun fromRadioReply(fromNode: Long, status: Byte, ownerId: String): ByteArray {
        val payload = byteArrayOf(status) + ownerId.toByteArray(Charsets.UTF_8)
        val data = ProtoWriter().apply {
            writeInt32(1, MezullaPairingCodec.PORT_PRIVATE_APP) // Data.portnum
            writeBytes(2, payload)                              // Data.payload
        }.toByteArray()
        val packet = ProtoWriter().apply {
            writeFixed32(1, fromNode)                           // MeshPacket.from
            writeMessage(4, data)                               // MeshPacket.decoded
        }.toByteArray()
        return ProtoWriter().apply {
            writeMessage(2, packet)                             // FromRadio.packet
        }.toByteArray()
    }

    @Test
    fun `decodes an OK reply with the board's node and owner`() {
        val frame = fromRadioReply(0x160435c6L, MezullaPairingCodec.STATUS_OK, "owner-uuid-123")
        val reply = MezullaPairingCodec.decodePairingReplyFromRadio(frame)!!
        assertEquals(0x160435c6L, reply.fromNode)
        assertEquals(PairingStatus.OK, reply.status)
        assertEquals("owner-uuid-123", reply.ownerId)
    }

    @Test
    fun `decodes a rejection status`() {
        val frame = fromRadioReply(0x160435c6L, MezullaPairingCodec.STATUS_ALREADY_CLAIMED, "someone-else")
        val reply = MezullaPairingCodec.decodePairingReplyFromRadio(frame)!!
        assertEquals(PairingStatus.ALREADY_CLAIMED, reply.status)
    }

    @Test
    fun `ignores a non-PRIVATE_APP frame (e g a handshake config frame)`() {
        // A FromRadio packet on a different portnum must not be mistaken for a reply.
        val data = ProtoWriter().apply {
            writeInt32(1, 1)                 // portnum = TEXT_MESSAGE_APP (not PRIVATE_APP)
            writeBytes(2, byteArrayOf(0, 1, 2))
        }.toByteArray()
        val packet = ProtoWriter().apply {
            writeFixed32(1, 0x160435c6L)
            writeMessage(4, data)
        }.toByteArray()
        val frame = ProtoWriter().apply { writeMessage(2, packet) }.toByteArray()
        assertNull(MezullaPairingCodec.decodePairingReplyFromRadio(frame))
    }

    @Test
    fun `round-trips the status byte through the response decoder`() {
        // The reply payload format must match encodeClaimPacket's counterpart.
        val payload = byteArrayOf(MezullaPairingCodec.STATUS_OK) + "abc".toByteArray()
        val resp = MezullaPairingCodec.decodeResponse(payload)
        assertEquals(PairingStatus.OK, resp.status)
        assertEquals("abc", resp.ownerId)
        assertArrayEquals(byteArrayOf(MezullaPairingCodec.STATUS_OK), byteArrayOf(0x00))
    }
}
