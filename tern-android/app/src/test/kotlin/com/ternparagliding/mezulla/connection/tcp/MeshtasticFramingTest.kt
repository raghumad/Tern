package com.ternparagliding.mezulla.connection.tcp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the Meshtastic TCP frame codec.
 *
 * Three things this codec is responsible for, each tested below:
 *  1. Round-trip an encoded frame back through the decoder.
 *  2. Handle partial reads — bytes arrive a few at a time, the decoder
 *     accumulates until a complete frame is present, then emits it.
 *  3. Recover from desynced / corrupt bytes by scanning forward to the
 *     next magic sequence rather than crashing.
 */
class MeshtasticFramingTest {

    @Test
    fun `encode then decode round-trips the payload`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val framed = MeshtasticFraming.encodeFrame(payload)

        val decoder = FrameDecoder()
        decoder.feed(framed)
        val frames = decoder.drainFrames()

        assertThat(frames).hasSize(1)
        assertThat(frames[0]).isEqualTo(payload)
    }

    @Test
    fun `encoded frame starts with the Meshtastic magic bytes and big-endian length`() {
        val payload = ByteArray(257) { it.toByte() }
        val framed = MeshtasticFraming.encodeFrame(payload)

        assertThat(framed[0]).isEqualTo(MeshtasticFraming.MAGIC_0)
        assertThat(framed[1]).isEqualTo(MeshtasticFraming.MAGIC_1)
        assertThat(framed[2]).isEqualTo(0x01.toByte()) // 257 >> 8
        assertThat(framed[3]).isEqualTo(0x01.toByte()) // 257 & 0xFF
        assertThat(framed.size).isEqualTo(MeshtasticFraming.HEADER_SIZE + payload.size)
    }

    @Test
    fun `decoder emits two back-to-back frames in order from a single feed`() {
        val a = byteArrayOf(0xA, 0xA, 0xA)
        val b = byteArrayOf(0xB, 0xB)
        val combined = MeshtasticFraming.encodeFrame(a) + MeshtasticFraming.encodeFrame(b)

        val decoder = FrameDecoder()
        decoder.feed(combined)
        val frames = decoder.drainFrames()

        assertThat(frames).hasSize(2)
        assertThat(frames[0]).isEqualTo(a)
        assertThat(frames[1]).isEqualTo(b)
    }

    @Test
    fun `decoder yields nothing until a complete frame has been fed`() {
        val payload = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val framed = MeshtasticFraming.encodeFrame(payload)
        val decoder = FrameDecoder()

        // Feed one byte at a time; before the last byte, drainFrames is empty.
        for (i in 0 until framed.size - 1) {
            decoder.feed(byteArrayOf(framed[i]))
            assertThat(decoder.drainFrames()).isEmpty()
        }
        decoder.feed(byteArrayOf(framed[framed.size - 1]))
        val frames = decoder.drainFrames()

        assertThat(frames).hasSize(1)
        assertThat(frames[0]).isEqualTo(payload)
    }

    @Test
    fun `decoder reassembles a frame split across many feeds`() {
        // Two frames, each fed in three chunks.
        val a = ByteArray(50) { (it + 1).toByte() }
        val b = ByteArray(20) { (100 - it).toByte() }
        val all = MeshtasticFraming.encodeFrame(a) + MeshtasticFraming.encodeFrame(b)

        val decoder = FrameDecoder()
        val collected = mutableListOf<ByteArray>()

        var offset = 0
        // Feed 7 bytes at a time — a value with no special relationship to
        // either frame's length, to make sure the decoder is genuinely
        // state-machine-driven rather than read-aligned.
        while (offset < all.size) {
            val end = minOf(offset + 7, all.size)
            decoder.feed(all.copyOfRange(offset, end))
            collected.addAll(decoder.drainFrames())
            offset = end
        }

        assertThat(collected).hasSize(2)
        assertThat(collected[0]).isEqualTo(a)
        assertThat(collected[1]).isEqualTo(b)
    }

    @Test
    fun `decoder recovers from bad magic bytes by scanning to the next frame`() {
        val good = byteArrayOf(0x42, 0x42, 0x42)
        // Garbage followed by a real frame.
        val garbage = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val stream = garbage + MeshtasticFraming.encodeFrame(good)

        val decoder = FrameDecoder()
        decoder.feed(stream)
        val frames = decoder.drainFrames()

        assertThat(frames).hasSize(1)
        assertThat(frames[0]).isEqualTo(good)
    }

    @Test
    fun `decoder recovers when magic is preceded by a stray byte that is not 0x94`() {
        val good = byteArrayOf(0x77, 0x88.toByte())
        // A lone 0xC3 (the second magic byte without the first) — the
        // scanner must not mistake it for the start of a frame.
        val stream = byteArrayOf(0xC3.toByte()) + MeshtasticFraming.encodeFrame(good)

        val decoder = FrameDecoder()
        decoder.feed(stream)
        val frames = decoder.drainFrames()

        assertThat(frames).hasSize(1)
        assertThat(frames[0]).isEqualTo(good)
    }

    @Test
    fun `decoder rejects an implausibly large frame length and rescans`() {
        // A fake frame header with a payload length > MAX_PAYLOAD, then
        // a real frame after it. The decoder must drop the bogus header
        // and emit only the real frame.
        val realPayload = byteArrayOf(0x55, 0x66)
        val real = MeshtasticFraming.encodeFrame(realPayload)
        // Bogus: magic + length = MAX_PAYLOAD + 1.
        val bogusLen = MeshtasticFraming.MAX_PAYLOAD + 1
        val bogus = byteArrayOf(
            MeshtasticFraming.MAGIC_0,
            MeshtasticFraming.MAGIC_1,
            ((bogusLen ushr 8) and 0xFF).toByte(),
            (bogusLen and 0xFF).toByte(),
        )

        val decoder = FrameDecoder()
        decoder.feed(bogus + real)
        val frames = decoder.drainFrames()

        assertThat(frames).hasSize(1)
        assertThat(frames[0]).isEqualTo(realPayload)
    }

    @Test
    fun `decoder handles a zero-length payload`() {
        val framed = MeshtasticFraming.encodeFrame(ByteArray(0))

        val decoder = FrameDecoder()
        decoder.feed(framed)
        val frames = decoder.drainFrames()

        assertThat(frames).hasSize(1)
        assertThat(frames[0]).isEqualTo(ByteArray(0))
    }
}
