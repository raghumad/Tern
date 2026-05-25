package com.madanala.tern.overlay.mezulla

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.mezulla.connection.PeerIdentity
import com.madanala.tern.mezulla.connection.PeerPosition
import com.madanala.tern.mezulla.redux.KnownPeer
import com.madanala.tern.overlay.priority.Position
import com.madanala.tern.redux.MezullaViewMode
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.maplibre.spatialk.geojson.Feature
import java.time.Instant

/**
 * Tests for [buildPeerFeatureCollection] — the bridge between Redux
 * peer state and the GeoJSON that MapLibre renders.
 */
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
    fun `feature collection contains one feature per peer with position`() {
        val peers = mapOf(
            makePeer(1L, "CBE"),
            makePeer(2L, "JLR"),
            makePeer(3L, "NO_POS", position = null),
        )

        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.SAFETY, null, now)

        assertThat(fc.features).hasSize(2)
    }

    @Test
    fun `empty peer map produces empty feature collection`() {
        val fc = buildPeerFeatureCollection(emptyMap(), MezullaViewMode.SAFETY, null, now)
        assertThat(fc.features).isEmpty()
    }

    @Test
    fun `safety mode display text contains callsign altitude and age`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 5))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.SAFETY, null, now)
        val displayText = fc.features.single().displayText()

        assertThat(displayText).contains("CBE")
        assertThat(displayText).contains("3120m")
        assertThat(displayText).contains("5s ago")
    }

    @Test
    fun `climb mode display text contains climb rate`() {
        val peers = mapOf(makePeer(1L, "CBE", climbRateMs = 2.5))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.CLIMB, null, now)
        val displayText = fc.features.single().displayText()

        assertThat(displayText).contains("CBE")
        assertThat(displayText).contains("+2.5 m/s")
        assertThat(displayText).contains("3120m")
    }

    @Test
    fun `tactical mode with pilot position shows distance and bearing`() {
        val pilot = Position(45.9, 6.1)
        val peers = mapOf(makePeer(1L, "CBE"))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.TACTICAL, pilot, now)
        val displayText = fc.features.single().displayText()

        assertThat(displayText).contains("CBE")
        assertThat(displayText).contains("km")
        assertThat(displayText).contains("km/h")
    }

    @Test
    fun `staleness property set correctly for fresh peer`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 10))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.SAFETY, null, now)

        assertThat(fc.features.single().staleness()).isEqualTo("FRESH")
    }

    @Test
    fun `staleness property set correctly for aging peer`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 60))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.SAFETY, null, now)

        assertThat(fc.features.single().staleness()).isEqualTo("AGING")
    }

    @Test
    fun `staleness property set correctly for stale peer`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 200))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.SAFETY, null, now)

        assertThat(fc.features.single().staleness()).isEqualTo("STALE")
    }

    @Test
    fun `staleness property set correctly for lost peer`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 600))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.SAFETY, null, now)

        assertThat(fc.features.single().staleness()).isEqualTo("LOST")
    }

    @Test
    fun `lost peer display text says lost contact`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 600))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.SAFETY, null, now)
        val displayText = fc.features.single().displayText()

        assertThat(displayText).contains("CBE")
        assertThat(displayText).contains("lost contact")
    }

    @Test
    fun `stale peer display text has warning indicator`() {
        val peers = mapOf(makePeer(1L, "CBE", lastSeenSecondsAgo = 200))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.SAFETY, null, now)
        val displayText = fc.features.single().displayText()

        assertThat(displayText).endsWith("⚠")
    }

    @Test
    fun `feature point coordinates match peer position`() {
        val peers = mapOf(makePeer(1L, "CBE"))
        val fc = buildPeerFeatureCollection(peers, MezullaViewMode.SAFETY, null, now)
        val point = fc.features.single().geometry!!

        assertThat(point.latitude).isWithin(0.0001).of(fix.latitudeDeg)
        assertThat(point.longitude).isWithin(0.0001).of(fix.longitudeDeg)
    }

    // ── Helpers ──────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun Feature<*, *>.displayText(): String {
        val props = (this as Feature<*, kotlinx.serialization.json.JsonObject>).properties
            ?: return ""
        return (props["displayText"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun Feature<*, *>.staleness(): String {
        val props = (this as Feature<*, kotlinx.serialization.json.JsonObject>).properties
            ?: return ""
        return (props["staleness"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    }
}
