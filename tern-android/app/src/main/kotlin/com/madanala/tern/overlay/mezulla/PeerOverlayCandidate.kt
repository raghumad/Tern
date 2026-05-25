package com.madanala.tern.overlay.mezulla

import com.madanala.tern.overlay.priority.OverlayCandidate
import com.madanala.tern.overlay.priority.OverlayKind
import com.madanala.tern.overlay.priority.Position

/**
 * An [OverlayCandidate] for a Mezulla peer marker. Uses the default
 * scoring formula: `PEER.safetyWeight * distanceDecay(distance)`.
 *
 * No custom scoring override — peers don't have urgency gradients the
 * way gust fronts do. A peer 500m away is more important than one 5km
 * away, and that's exactly what `distanceDecay` gives us.
 *
 * [nodeNumber] is carried so the prioritizer output can be traced back
 * to the [KnownPeer] that produced it.
 */
data class PeerOverlayCandidate(
    override val position: Position,
    val nodeNumber: Long,
) : OverlayCandidate {
    override val kind: OverlayKind = OverlayKind.PEER
}
