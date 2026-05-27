package com.ternparagliding.mezulla.pairing

import com.ternparagliding.mezulla.connection.ble.MeshPacketCodec
import com.ternparagliding.mezulla.connection.ble.ProtoWriter

/**
 * Encodes and decodes the Mezulla ownership protocol that rides on
 * Meshtastic's PRIVATE_APP port (256).
 *
 * Wire format (defined in docs/backlog/ws-qr-pairing-app-handoff.md):
 *
 *   Claim request:
 *     [0x01]                       command (CLAIM)
 *     [token_length: 1 byte]       length of the pairing token
 *     [token: token_length bytes]  the token from the QR code
 *     [owner_id: remaining bytes]  stable phone-side identifier
 *
 *   Query request:
 *     [0x02]                       command (QUERY)
 *
 *   Response (from board):
 *     [status: 1 byte]             0x00 = OK, 0x01 = token mismatch, 0x02 = already claimed
 *     [owner_id: remaining bytes]  the stored owner (or empty)
 */
object MezullaPairingCodec {

    const val PORT_PRIVATE_APP = 256

    const val CMD_CLAIM: Byte = 0x01
    const val CMD_QUERY: Byte = 0x02

    const val STATUS_OK: Byte = 0x00
    const val STATUS_TOKEN_MISMATCH: Byte = 0x01
    const val STATUS_ALREADY_CLAIMED: Byte = 0x02

    fun encodeClaimPacket(pairingToken: String, ownerId: String): ByteArray {
        val tokenBytes = pairingToken.toByteArray(Charsets.UTF_8)
        val ownerBytes = ownerId.toByteArray(Charsets.UTF_8)
        require(tokenBytes.size <= 255) { "Pairing token too long (${tokenBytes.size} > 255)" }
        return byteArrayOf(CMD_CLAIM, tokenBytes.size.toByte()) + tokenBytes + ownerBytes
    }

    fun encodeQueryPacket(): ByteArray = byteArrayOf(CMD_QUERY)

    fun decodeResponse(bytes: ByteArray): PairingResponse {
        if (bytes.isEmpty()) return PairingResponse(PairingStatus.UNKNOWN, "")
        val status = when (bytes[0]) {
            STATUS_OK -> PairingStatus.OK
            STATUS_TOKEN_MISMATCH -> PairingStatus.TOKEN_MISMATCH
            STATUS_ALREADY_CLAIMED -> PairingStatus.ALREADY_CLAIMED
            else -> PairingStatus.UNKNOWN
        }
        val ownerId = if (bytes.size > 1) {
            String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
        } else ""
        return PairingResponse(status, ownerId)
    }

    /**
     * Wrap a pairing payload in a ToRadio MeshPacket targeting a specific
     * node on PRIVATE_APP. Unlike broadcast position/alert packets, claim
     * packets are directed to the board we're pairing with.
     */
    fun encodeToRadioPrivateApp(
        fromNodeNumber: Long,
        toNodeNumber: Long,
        packetId: Int,
        payload: ByteArray,
    ): ByteArray {
        val data = ProtoWriter().apply {
            writeInt32(1, PORT_PRIVATE_APP)  // Data.portnum
            writeBytes(2, payload)            // Data.payload
            writeBool(5, true)               // Data.want_response
        }.toByteArray()
        val packet = ProtoWriter().apply {
            writeFixed32(1, fromNodeNumber)   // MeshPacket.from
            writeFixed32(2, toNodeNumber)     // MeshPacket.to
            writeMessage(4, data)             // MeshPacket.decoded
            writeFixed32(6, packetId.toLong() and 0xFFFFFFFFL) // MeshPacket.id
            writeBool(10, true)               // MeshPacket.want_ack
        }.toByteArray()
        return ProtoWriter().apply {
            writeMessage(1, packet)           // ToRadio.packet
        }.toByteArray()
    }
}

enum class PairingStatus {
    OK,
    TOKEN_MISMATCH,
    ALREADY_CLAIMED,
    UNKNOWN,
}

data class PairingResponse(
    val status: PairingStatus,
    val ownerId: String,
)
