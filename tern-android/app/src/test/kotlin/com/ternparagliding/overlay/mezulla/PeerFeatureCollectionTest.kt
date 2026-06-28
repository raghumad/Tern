package com.ternparagliding.overlay.mezulla

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition
import com.ternparagliding.mezulla.redux.KnownPeer
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

        val bundle = buildPeerBundle(peers, now, ownLocation)

        assertThat(bundle.specs).hasSize(2)
    }

    @Test
    fun `empty peer map produces empty bundle`() {
        val bundle = buildPeerBundle(emptyMap(), now, ownLocation)
        assertThat(bundle.specs).isEmpty()
        assertThat(bundle.geoJson).contains("\"features\":[]")
    }

    @Test
    fun `callsign and short tag are derived from the long name`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), now, ownLocation)
            .specs.single()
        assertThat(spec.callsign).isEqualTo("CBE")
        assertThat(spec.shortTag).isEqualTo("CBE")
    }

    @Test
    fun `track arrow uses the peer ground track`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), now, ownLocation)
            .specs.single()
        assertThat(spec.trackDegrees).isEqualTo(45.0f)
    }

    @Test
    fun `relative altitude is peer altitude minus my altitude`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), now, ownLocation)
            .specs.single()
        // peer 3120 m − my 2000 m = +1120 m, above me → up colour.
        assertThat(spec.deltaAltText).isEqualTo("+1120m")
        assertThat(spec.deltaAltColor).isEqualTo(0xFFB4FFB4.toInt())
    }

    @Test
    fun `relative altitude is negative and red when peer is below me`() {
        val low = fix.copy(altitudeMeters = 1500)
        val spec = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", position = low)), now, ownLocation
        ).specs.single()
        assertThat(spec.deltaAltText).isEqualTo("-500m")
        assertThat(spec.deltaAltColor).isEqualTo(0xFFFFB4B4.toInt())
    }

    @Test
    fun `distance to peer is populated from my position`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), now, ownLocation)
            .specs.single()
        // ~1.1 km north of me.
        assertThat(spec.distanceText).isEqualTo("1.1km")
    }

    @Test
    fun `distance and relative altitude are blank without my position`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), now, ownLocation = null)
            .specs.single()
        assertThat(spec.distanceText).isEmpty()
        assertThat(spec.deltaAltText).isEmpty()
    }

    @Test
    fun `bottom line combines climb and ground speed when uncluttered`() {
        val spec = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", climbRateMs = 2.5)), now, ownLocation
        ).specs.single()
        // FULL (one buddy): climb ▲2.5 + ground speed 36 km/h.
        assertThat(spec.level).isEqualTo(DeclutterLevel.FULL)
        assertThat(spec.bottomText).isEqualTo("▲2.5  36km/h")
    }

    @Test
    fun `bottom line shows ground speed when climb is unknown`() {
        val spec = buildPeerBundle(mapOf(makePeer(1L, "CBE")), now, ownLocation)
            .specs.single()
        assertThat(spec.bottomText).isEqualTo("36km/h")
    }

    @Test
    fun `declutter keeps nearest buddies full and sheds speed then distance for far ones`() {
        // Six buddies due north at increasing distance → proximity ranks 0..5.
        val peers = (0 until 6).associate { k ->
            val node = (k + 1).toLong()
            node to KnownPeer(
                identity = PeerIdentity.fromNodeNumber(node, longName = "P$k"),
                lastPosition = fix.copy(latitudeDeg = 45.901 + k * 0.005),
                lastSeenAt = now.minusSeconds(5),
                climbRateMs = 1.0,
            )
        }
        val specs = buildPeerBundle(peers, now, ownLocation).specs.associateBy { it.imageName }

        // Nearest two stay FULL; the next band drops to MEDIUM; the farthest to REDUCED.
        assertThat(specs.getValue("peer-1").level).isEqualTo(DeclutterLevel.FULL)
        assertThat(specs.getValue("peer-2").level).isEqualTo(DeclutterLevel.FULL)
        assertThat(specs.getValue("peer-3").level).isEqualTo(DeclutterLevel.MEDIUM)
        assertThat(specs.getValue("peer-5").level).isEqualTo(DeclutterLevel.MEDIUM)
        assertThat(specs.getValue("peer-6").level).isEqualTo(DeclutterLevel.REDUCED)

        // Speed shows only at FULL; distance survives into MEDIUM but is dropped at REDUCED.
        assertThat(specs.getValue("peer-1").bottomText).contains("km/h")
        assertThat(specs.getValue("peer-3").bottomText).doesNotContain("km/h")
        assertThat(specs.getValue("peer-3").distanceText).isNotEmpty()
        assertThat(specs.getValue("peer-6").distanceText).isEmpty()
        // Climb + relative altitude are kept the whole way down (collision/gaggle reads).
        assertThat(specs.getValue("peer-6").bottomText).isEqualTo("▲1.0")
        assertThat(specs.getValue("peer-6").deltaAltText).isNotEmpty()
    }

    @Test
    fun `staleness FRESH puck is green`() {
        val bundle = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 10)), now, ownLocation
        )
        assertThat(bundle.geoJson).contains("\"staleness\":\"FRESH\"")
        assertThat(bundle.specs.single().puckColor).isEqualTo(0xFF4CAF50.toInt())
    }

    @Test
    fun `staleness AGING puck is yellow`() {
        val bundle = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 60)), now, ownLocation
        )
        assertThat(bundle.geoJson).contains("\"staleness\":\"AGING\"")
        assertThat(bundle.specs.single().puckColor).isEqualTo(0xFFFFD600.toInt())
    }

    @Test
    fun `STALE peer bottom line is the status warning`() {
        val spec = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 200)), now, ownLocation
        ).specs.single()
        assertThat(spec.bottomText).isEqualTo("⚠ STALE")
    }

    @Test
    fun `LOST peer is degraded - status only, no arrow, no metrics`() {
        val spec = buildPeerBundle(
            mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 600)), now, ownLocation
        ).specs.single()
        assertThat(spec.bottomText).isEqualTo("⚠ LOST")
        assertThat(spec.trackDegrees).isNull()
        assertThat(spec.deltaAltText).isEmpty()
        assertThat(spec.distanceText).isEmpty()
    }

    @Test
    fun `geojson coordinates match peer position`() {
        val bundle = buildPeerBundle(mapOf(makePeer(1L, "CBE")), now, ownLocation)
        assertThat(bundle.geoJson).contains("\"coordinates\":[6.1245,45.9099]")
    }
}
