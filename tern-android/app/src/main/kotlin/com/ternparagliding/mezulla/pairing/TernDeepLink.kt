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
) {
    val nodeNumber: Long
        get() = nodeIdHex.toLong(16)

    companion object {
        private val HEX = Regex("^[0-9a-fA-F]+$")

        fun parse(uriString: String): TernPairLink? {
            // Expected: tern://p?n=<hex>&t=<hex>
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

            return TernPairLink(nodeIdHex = n.lowercase(), pairingToken = t.lowercase())
        }
    }
}
