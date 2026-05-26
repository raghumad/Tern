package com.ternparagliding.overlay.mezulla

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition
import com.ternparagliding.mezulla.redux.KnownPeer
import com.ternparagliding.redux.MezullaViewMode
import org.junit.jupiter.api.Test
import java.time.Instant

class PeerFeatureCollectionTest {

    private val now = Instant.parse("2026-04-25T12:05:00Z")

    private val fix = PeerPosition.Fix(
        latitudeDeg = 45.9099,
        longitudeDeg = 6.1245,
        altitudeMeters = 3120,
        groundSpeedMetersPerSecond = 10.0,
        groundTrackDegrees = 45.0,
        timestampSeconds = 1_700_000_000L,
    )

    private fun makePeer(
        nodeNumber: Long,
        longName: String,
        lastSeenSecondsAgo: Long = 5,
        position: PeerPosition.Fix? = fix,
        climbRateMs: Double? = null,
    ): Pair<Long, KnownPeer> = nodeNumber to KnownPeer(
        identity = PeerIdentity.fromNodeNumber(nodeNumber, longName = longName),
        lastPosition = position,
        lastSeenAt = now.minusSeconds(lastSeenSecondsAgo),
        climbRateMs = climbRateMs,
    )

    @Test
    fun `bundle contains one spec per peer with position`() {
        val peers = mapOf(
            makePeer(1L, "CBE"),
            makePeer(2L, "JLR"),
            makePeer(3L, "NO_POS", position = null),
        )

        val bundle = buildPeerBundle(peers, MezullaViewMode.SAFETY, now)

        assertThat(bundle.specs).hasSize(2)
    }

    @Test
    fun `empty peer map produces empty bundle`() {
        val bundle = buildPeerBundle(emptyMap(), MezullaViewMode.SAFETY, now)
        assertThat(bundle.specs).isEmpty()
        assertThat(bundle.geoJson).contains("\"features\":[]")
    }

    @Test
    fun `safety mode shows callsign altitude and age`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 5))
        val bundle = buildPeerBundle(peers, MezullaViewMode.SAFETY, now)
        val spec = bundle.specs.single()

        assertThat(spec.callsign).isEqualTo("CBE")
        assertThat(spec.rightValue).isEqualTo("3120")
        assertThat(spec.rightUnit).isEqualTo("m")
        assertThat(spec.leftValue).isEqualTo("5")
        assertThat(spec.leftUnit).isEqualTo("s")
    }

    @Test
    fun `climb mode shows climb rate`() {
        val peers = mapOf(makePeer(1L, "CBE", climbRateMs = 2.5))
        val bundle = buildPeerBundle(peers, MezullaViewMode.CLIMB, now)
        val spec = bundle.specs.single()

        assertThat(spec.callsign).isEqualTo("CBE")
        assertThat(spec.leftValue).isEqualTo("+2.5")
        assertThat(spec.leftUnit).isEqualTo("m/s")
        assertThat(spec.rightValue).isEqualTo("3120")
    }

    @Test
    fun `tactical mode shows speed`() {
        val peers = mapOf(makePeer(1L, "CBE"))
        val bundle = buildPeerBundle(peers, MezullaViewMode.TACTICAL, now)
        val spec = bundle.specs.single()

        assertThat(spec.callsign).isEqualTo("CBE")
        assertThat(spec.leftValue).isEqualTo("36")
        assertThat(spec.leftUnit).isEqualTo("km/h")
    }

    @Test
    fun `staleness FRESH for recent peer`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 10))
        val bundle = buildPeerBundle(peers, MezullaViewMode.SAFETY, now)

        assertThat(bundle.geoJson).contains("\"staleness\":\"FRESH\"")
        assertThat(bundle.specs.single().glyphColor).isEqualTo(0xFF4CAF50.toInt())
    }

    @Test
    fun `staleness AGING for 60s old peer`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 60))
        val bundle = buildPeerBundle(peers, MezullaViewMode.SAFETY, now)

        assertThat(bundle.geoJson).contains("\"staleness\":\"AGING\"")
        assertThat(bundle.specs.single().glyphColor).isEqualTo(0xFFFFD600.toInt())
    }

    @Test
    fun `staleness STALE for 200s old peer`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 200))
        val bundle = buildPeerBundle(peers, MezullaViewMode.SAFETY, now)

        assertThat(bundle.geoJson).contains("\"staleness\":\"STALE\"")
        assertThat(bundle.specs.single().bottomText).isEqualTo("⚠ STALE")
    }

    @Test
    fun `staleness LOST for 600s old peer`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 600))
        val bundle = buildPeerBundle(peers, MezullaViewMode.SAFETY, now)

        assertThat(bundle.geoJson).contains("\"staleness\":\"LOST\"")
        assertThat(bundle.specs.single().bottomText).isEqualTo("⚠ LOST")
    }

    @Test
    fun `lost peer shows lost in left pill`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 600))
        val bundle = buildPeerBundle(peers, MezullaViewMode.SAFETY, now)
        val spec = bundle.specs.single()

        assertThat(spec.leftValue).isEqualTo("lost")
        assertThat(spec.rightValue).isEmpty()
    }

    @Test
    fun `geojson coordinates match peer position`() {
        val peers = mapOf(makePeer(1L, "CBE"))
        val bundle = buildPeerBundle(peers, MezullaViewMode.SAFETY, now)

        assertThat(bundle.geoJson).contains("\"coordinates\":[6.1245,45.9099]")
    }
}
