package com.ternparagliding.mezulla.connection.tcp

/**
 * Meshtastic TCP frame codec.
 *
 * Meshtastic's TCP API wraps each protobuf message (`FromRadio` from board
 * to client, `ToRadio` from client to board) in a 4-byte framing header:
 *
 *     0x94 0xC3  <length_msb> <length_lsb>  <protobuf_bytes...>
 *
 * `length` is the byte count of the protobuf payload (big-endian). The
 * magic bytes 0x94 0xC3 mark the start of a frame, so a corrupted or
 * desynced stream can be re-aligned by scanning forward for them.
 *
 * Wire format reference: Meshtastic firmware's `StreamAPI` implementation,
 * which is shared between USB-serial and TCP transports (both speak the
 * same framing; only the underlying byte channel differs).
 *
 * Two halves:
 *  - [encodeFrame] is stateless: given a protobuf payload, return the
 *    framed bytes ready to write to a socket.
 *  - [FrameDecoder] is stateful: feed it raw bytes (which may include
 *    partial frames or multiple frames per read), call [drainFrames] to
 *    pull out complete payloads.
 */
internal object MeshtasticFraming {

    const val MAGIC_0 = 0x94.toByte()
    const val MAGIC_1 = 0xC3.toByte()
    const val HEADER_SIZE = 4

    /**
     * Practical upper bound on a single Meshtastic frame's payload. The
     * firmware's `StreamAPI` uses 512 bytes as the buffer ceiling; we set
     * ours higher than that to leave room for future growth without
     * rejecting legitimate frames, but low enough that a length byte
     * corruption can't trick us into allocating gigabytes.
     */
    const val MAX_PAYLOAD = 4096

    fun encodeFrame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
            "frame payload ${payload.size} exceeds MAX_PAYLOAD $MAX_PAYLOAD"
        }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }
}

/**
 * Decodes Meshtastic frames from a stream of bytes that may arrive
 * arbitrarily fragmented.
 *
 * Real TCP reads do not respect message boundaries — one `read()` can
 * return half of a frame, or two frames, or a byte at a time. This decoder
 * is the only piece allowed to assume nothing about read sizes.
 *
 * State machine:
 *  1. Scan for `0x94 0xC3`. Bytes before the magic are skipped. This is
 *     the resync path — if framing gets corrupted, scanning forward to
 *     the next magic recovers without crashing.
 *  2. After the magic, read 2 length bytes (big-endian).
 *  3. Read that many payload bytes.
 *  4. Emit the payload; loop.
 *
 * Not thread-safe. Intended to be owned by a single coroutine that owns
 * the socket read loop.
 */
internal class FrameDecoder {

    private val buffer = ArrayDeque<Byte>()

    /** Append raw bytes received from the socket. */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) buffer.addLast(bytes[i])
    }

    /**
     * Pull out every complete frame currently in the buffer. Returns an
     * empty list if no complete frame is available yet (more bytes
     * needed).
     */
    fun drainFrames(): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        while (true) {
            val frame = tryReadOneFrame() ?: break
            out.add(frame)
        }
        return out
    }

    private fun tryReadOneFrame(): ByteArray? {
        // 1. Scan for magic.
        while (buffer.size >= 2) {
            if (buffer.first() == MeshtasticFraming.MAGIC_0 &&
                buffer.elementAt(1) == MeshtasticFraming.MAGIC_1
            ) {
                break
            }
            // Resync: drop one byte at a time until the magic appears or
            // we run out. Real-world cause is rare (line noise, a partial
            // frame from a previous session) — but if it happens, dropping
            // bytes is the only correct response.
            buffer.removeFirst()
        }

        if (buffer.size < MeshtasticFraming.HEADER_SIZE) return null

        // 2. Peek length without committing — we may not have the payload yet.
        val lenHi = buffer.elementAt(2).toInt() and 0xFF
        val lenLo = buffer.elementAt(3).toInt() and 0xFF
        val payloadLen = (lenHi shl 8) or lenLo

        if (payloadLen > MeshtasticFraming.MAX_PAYLOAD) {
            // Length is implausible — almost certainly a desync that
            // happened to land on a stray 0x94 0xC3 inside payload bytes.
            // Drop the magic and rescan from the next byte.
            buffer.removeFirst()
            return tryReadOneFrame()
        }

        if (buffer.size < MeshtasticFraming.HEADER_SIZE + payloadLen) return null

        // 3. Commit. Drop the 4 header bytes, then take the payload.
        repeat(MeshtasticFraming.HEADER_SIZE) { buffer.removeFirst() }
        val payload = ByteArray(payloadLen)
        for (i in 0 until payloadLen) payload[i] = buffer.removeFirst()
        return payload
    }
}
