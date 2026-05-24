package com.madanala.tern.mezulla.connection

/**
 * How Tern identifies a peer on the mesh.
 *
 * Meshtastic identifies nodes by an unsigned 32-bit `node number`. The
 * wire form most users see is the `User.id` string `!aabbccdd` (the node
 * number in lowercase hex with a `!` prefix). NodeInfo (`User` proto)
 * carries `long_name` and `short_name` chosen by the peer's pilot — those
 * are what shows up next to the marker on the map.
 *
 * We carry all three on every event so:
 *  - the redux key is stable ([nodeNumber] — never changes for a node);
 *  - the UI has a glanceable label ([shortName] or [longName]) without a
 *    second lookup;
 *  - debug logs and bug reports can show the canonical [hexId] without
 *    extra formatting.
 *
 * When NodeInfo has not yet arrived for a node, [longName] and
 * [shortName] are null. The UI falls back to [hexId] in that case.
 */
data class PeerIdentity(
    val nodeNumber: Long,
    val hexId: String,
    val longName: String? = null,
    val shortName: String? = null,
) {
    companion object {
        /** Build from the raw node number; formats [hexId] as `!aabbccdd`. */
        fun fromNodeNumber(
            nodeNumber: Long,
            longName: String? = null,
            shortName: String? = null,
        ): PeerIdentity {
            require(nodeNumber in 0..0xFFFFFFFFL) {
                "Meshtastic node number is unsigned 32-bit; got $nodeNumber"
            }
            val hex = "!%08x".format(nodeNumber)
            return PeerIdentity(nodeNumber, hex, longName, shortName)
        }
    }
}
