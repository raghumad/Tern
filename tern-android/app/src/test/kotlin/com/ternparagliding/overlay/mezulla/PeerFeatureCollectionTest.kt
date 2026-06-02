package com.ternparagliding.overlay.mezulla

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition
import com.ternparagliding.mezulla.redux.KnownPeer
import com.ternparagliding.redux.MezullaViewMode
import org.junit.jupiter.api.Test
import org.osmdroid.util.GeoPoint
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

    // My own position: same longitude, ~0.0099° south of the peer
    // (≈1.1 km), 2000 m altitude (the peer is 1120 m above me).
    private val ownLocation = GeoPoint(45.9000, 6.1245, 2000.0)

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

        val bundle = buildPeerBundle(peers, MezullaViewMode.SAFETY, now, ownLocation)

        assertThat(bundle.specs).hasSize(2)
    }

    @Test
    fun `empty peer map produces empty bundle`() {
        val bundle = buildPeerBundle(emptyMap(), MezullaViewMode.SAFETY, now, ownLocation)
        assertThat(bundle.specs).isEmpty()
        assertThat(bundle.geoJson).contains("\"features\":[]")
    }

    @Test
    fun `callsign and short tag are derived from the long name`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), MezullaViewMode.SAFETY, now, ownLocation)
            .specs.single()
        assertThat(spec.callsign).isEqualTo("CBE")
        assertThat(spec.shortTag).isEqualTo("CBE")
    }

    @Test
    fun `track arrow uses the peer ground track`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), MezullaViewMode.SAFETY, now, ownLocation)
            .specs.single()
        assertThat(spec.trackDegrees).isEqualTo(45.0f)
    }

    @Test
    fun `relative altitude is peer altitude minus my altitude`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), MezullaViewMode.SAFETY, now, ownLocation)
            .specs.single()
        // peer 3120 m − my 2000 m = +1120 m, above me → up colour.
        assertThat(spec.deltaAltText).isEqualTo("+1120m")
        assertThat(spec.deltaAltColor).isEqualTo(0xFFB4FFB4.toInt())
    }

    @Test
    fun `relative altitude is negative and red when peer is below me`() {
        val low = fix.copy(altitudeMeters = 1500)
        val spec = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", position = low)), MezullaViewMode.SAFETY, now, ownLocation
        ).specs.single()
        assertThat(spec.deltaAltText).isEqualTo("-500m")
        assertThat(spec.deltaAltColor).isEqualTo(0xFFFFB4B4.toInt())
    }

    @Test
    fun `distance to peer is populated from my position`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), MezullaViewMode.SAFETY, now, ownLocation)
            .specs.single()
        // ~1.1 km north of me.
        assertThat(spec.distanceText).isEqualTo("1.1km")
    }

    @Test
    fun `distance and relative altitude are blank without my position`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), MezullaViewMode.SAFETY, now, ownLocation = null)
            .specs.single()
        assertThat(spec.distanceText).isEmpty()
        assertThat(spec.deltaAltText).isEmpty()
    }

    @Test
    fun `safety mode bottom line shows last-seen age`() {
        val spec = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 5)), MezullaViewMode.SAFETY, now, ownLocation
        ).specs.single()
        assertThat(spec.bottomText).isEqualTo("5s ago")
    }

    @Test
    fun `climb mode bottom line shows climb rate`() {
        val spec = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", climbRateMs = 2.5)), MezullaViewMode.CLIMB, now, ownLocation
        ).specs.single()
        assertThat(spec.bottomText).isEqualTo("+2.5 m/s")
    }

    @Test
    fun `tactical mode bottom line shows ground speed`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), MezullaViewMode.TACTICAL, now, ownLocation)
            .specs.single()
        assertThat(spec.bottomText).isEqualTo("36 km/h")
    }

    @Test
    fun `staleness FRESH puck is green`() {
        val bundle = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 10)), MezullaViewMode.SAFETY, now, ownLocation
        )
        assertThat(bundle.geoJson).contains("\"staleness\":\"FRESH\"")
        assertThat(bundle.specs.single().puckColor).isEqualTo(0xFF4CAF50.toInt())
    }

    @Test
    fun `staleness AGING puck is yellow`() {
        val bundle = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 60)), MezullaViewMode.SAFETY, now, ownLocation
        )
        assertThat(bundle.geoJson).contains("\"staleness\":\"AGING\"")
        assertThat(bundle.specs.single().puckColor).isEqualTo(0xFFFFD600.toInt())
    }

    @Test
    fun `STALE peer bottom line is the status warning`() {
        val spec = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 200)), MezullaViewMode.SAFETY, now, ownLocation
        ).specs.single()
        assertThat(spec.bottomText).isEqualTo("⚠ STALE")
    }

    @Test
    fun `LOST peer is degraded - status only, no arrow, no metrics`() {
        val spec = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 600)), MezullaViewMode.SAFETY, now, ownLocation
        ).specs.single()
        assertThat(spec.bottomText).isEqualTo("⚠ LOST")
        assertThat(spec.trackDegrees).isNull()
        assertThat(spec.deltaAltText).isEmpty()
        assertThat(spec.distanceText).isEmpty()
    }

    @Test
    fun `geojson coordinates match peer position`() {
        val bundle = buildPeerBundle(mapOf(makePeer(1L, "CBE")), MezullaViewMode.SAFETY, now, ownLocation)
        assertThat(bundle.geoJson).contains("\"coordinates\":[6.1245,45.9099]")
    }
}
