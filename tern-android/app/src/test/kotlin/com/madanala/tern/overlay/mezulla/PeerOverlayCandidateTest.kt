package com.madanala.tern.overlay.mezulla

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.mezulla.connection.PeerIdentity
import com.madanala.tern.mezulla.connection.PeerPosition
import com.madanala.tern.mezulla.redux.KnownPeer
import com.madanala.tern.overlay.priority.OverlayKind
import com.madanala.tern.overlay.priority.Position
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [PeerOverlayCandidate] scoring and [buildPeerOverlayCandidates].
 */
class PeerOverlayCandidateTest {

    private val pilot = Position(46.6863, 7.8632) // Interlaken

    private val fix = PeerPosition.Fix(
        latitudeDeg = 46.69,
        longitudeDeg = 7.87,
        altitudeMeters = 1800,
        groundSpeedMetersPerSecond = 8.0,
        groundTrackDegrees = 180.0,
        timestampSeconds = 1_700_000_000L,
    )

    private val farFix = PeerPosition.Fix(
        latitudeDeg = 47.69,
        longitudeDeg = 8.87,
        altitudeMeters = 1200,
        groundSpeedMetersPerSecond = 5.0,
        groundTrackDegrees = 90.0,
        timestampSeconds = 1_700_000_000L,
    )

    private val now = Instant.parse("2026-04-25T12:05:00Z")

    @Test
    fun `PeerOverlayCandidate has kind PEER`() {
        val candidate = PeerOverlayCandidate(
            position = Position(fix.latitudeDeg, fix.longitudeDeg),
            nodeNumber = 0x12345678L,
        )
        assertThat(candidate.kind).isEqualTo(OverlayKind.PEER)
    }

    @Test
    fun `nearby peer scores higher than distant peer`() {
        val near = PeerOverlayCandidate(
            position = Position(fix.latitudeDeg, fix.longitudeDeg),
            nodeNumber = 1L,
        )
        val far = PeerOverlayCandidate(
            position = Position(farFix.latitudeDeg, farFix.longitudeDeg),
            nodeNumber = 2L,
        )
        assertThat(near.score(pilot)).isGreaterThan(far.score(pilot))
    }

    @Test
    fun `peer score uses PEER safety weight`() {
        val candidate = PeerOverlayCandidate(
            position = pilot, // at pilot position
            nodeNumber = 1L,
        )
        // At zero distance, distanceDecay = 1.0, so score = safetyWeight
        assertThat(candidate.score(pilot))
            .isWithin(0.01)
            .of(OverlayKind.PEER.safetyWeight.toDouble())
    }

    // ── buildPeerOverlayCandidates ────────────────────────────────────

    @Test
    fun `candidates built from peers with positions`() {
        val peers = mapOf(
            1L to KnownPeer(
                identity = PeerIdentity.fromNodeNumber(1L, longName = "Alpha"),
                lastPosition = fix,
                lastSeenAt = now,
            ),
            2L to KnownPeer(
                identity = PeerIdentity.fromNodeNumber(2L, longName = "Bravo"),
                lastPosition = farFix,
                lastSeenAt = now,
            ),
        )

        val candidates = buildPeerOverlayCandidates(peers)

        assertThat(candidates).hasSize(2)
        assertThat(candidates.map { it.nodeNumber }.toSet()).containsExactly(1L, 2L)
    }

    @Test
    fun `peers without position are excluded from candidates`() {
        val peers = mapOf(
            1L to KnownPeer(
                identity = PeerIdentity.fromNodeNumber(1L),
                lastPosition = fix,
                lastSeenAt = now,
            ),
            2L to KnownPeer(
                identity = PeerIdentity.fromNodeNumber(2L),
                lastPosition = null, // no position
                lastSeenAt = now,
            ),
        )

        val candidates = buildPeerOverlayCandidates(peers)

        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().nodeNumber).isEqualTo(1L)
    }

    @Test
    fun `empty peer map produces empty candidates`() {
        assertThat(buildPeerOverlayCandidates(emptyMap())).isEmpty()
    }
}
