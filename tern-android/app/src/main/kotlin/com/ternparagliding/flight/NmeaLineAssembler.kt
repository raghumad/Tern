package com.ternparagliding.flight

/**
 * Reassembles BLE notification chunks into whole NMEA lines.
 *
 * The XC Tracer streams its `$XCTRC` sentences over the FFE0/FFE1 BLE-serial characteristic
 * in ~20-byte ATT notifications, with no alignment to sentence boundaries — a single sentence
 * is split across several notifications, and the split lands *mid-field* (a real capture had
 * longitude `-104.953582` arrive as `…,-10` then `4.953582`). So the transport can't parse a
 * notification on its own; it must buffer bytes and cut on the `\r\n` line terminators.
 *
 * Stateful and not thread-safe by design — feed it from a single BLE callback thread. Pure
 * (no Android deps) so the chunking logic is unit-testable against real captured fragments.
 */
class NmeaLineAssembler(private val maxLineLen: Int = 256) {

    private val buf = StringBuilder()

    /** Feed a raw notification; returns any complete lines it completed (terminators stripped). */
    fun append(chunk: ByteArray): List<String> = append(String(chunk, Charsets.US_ASCII))

    fun append(text: String): List<String> {
        val out = ArrayList<String>()
        for (ch in text) {
            if (ch == '\n' || ch == '\r') {
                if (buf.isNotEmpty()) {
                    out.add(buf.toString())
                    buf.setLength(0)
                }
            } else {
                buf.append(ch)
                if (buf.length > maxLineLen) buf.setLength(0) // runaway/garbage guard: drop the partial
            }
        }
        return out
    }

    /** Drop any buffered partial line (e.g. on disconnect), so a reconnect starts clean. */
    fun reset() = buf.setLength(0)
}
