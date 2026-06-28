package com.ternparagliding.mezulla.pairing

/**
 * Parses the `tern://p?n=<nodeId>&t=<token>` deep link that the
 * pilot's phone camera produces when scanning the Mezulla board's QR.
 *
 * No Android dependency — uses string parsing so it's testable in
 * pure JUnit without Robolectric.
 */
data class TernPairLink(
    val nodeIdHex: String,
    val pairingToken: String,
    /**
     * The board's BLE advertising address (e.g. "3C:0F:02:ED:85:31"), parsed
     * from the `m=` QR param. When present, pairing connects to THIS exact
     * address instead of scanning and grabbing the first Meshtastic board —
     * the deterministic fix for pairing-to-the-wrong-board. Null for legacy
     * QRs that predate `m=` (pairing then falls back to scanning).
     */
    val bleMac: String? = null,
) {
    val nodeNumber: Long
        get() = nodeIdHex.toLong(16)

    companion object {
        private val HEX = Regex("^[0-9a-fA-F]+$")
        private val MAC12 = Regex("^[0-9a-fA-F]{12}$")

        /** "3c0f02ed8531" -> "3C:0F:02:ED:85:31" (the form BluetoothAdapter.getRemoteDevice wants). */
        fun formatMac(hex12: String): String? {
            if (!MAC12.matches(hex12)) return null
            return hex12.uppercase().chunked(2).joinToString(":")
        }

        fun parse(uriString: String): TernPairLink? {
            // Expected: tern://p?n=<hex>&t=<hex>[&m=<12 hex>]
            if (!uriString.startsWith("tern://p?")) return null
            val query = uriString.substringAfter("tern://p?", "")
            if (query.isEmpty()) return null

            val params = query.split("&").associate { part ->
                val (k, v) = part.split("=", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else it[0] to ""
                }
                k to v
            }

            val n = params["n"] ?: return null
            val t = params["t"] ?: return null
            if (n.isBlank() || t.isBlank()) return null
            if (!HEX.matches(n) || !HEX.matches(t)) return null

            // m= is optional. A malformed m= is ignored (falls back to scan)
            // rather than failing the whole link.
            val mac = params["m"]?.let { formatMac(it) }

            return TernPairLink(nodeIdHex = n.lowercase(), pairingToken = t.lowercase(), bleMac = mac)
        }
    }
}
