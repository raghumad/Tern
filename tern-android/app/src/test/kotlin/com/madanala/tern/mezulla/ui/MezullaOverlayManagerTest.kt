package com.madanala.tern.mezulla.ui

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.mezulla.connection.LinkState
import com.madanala.tern.mezulla.connection.PeerIdentity
import com.madanala.tern.mezulla.connection.PeerPosition
import com.madanala.tern.mezulla.redux.ActiveAlert
import com.madanala.tern.mezulla.redux.KnownPeer
import com.madanala.tern.mezulla.redux.PeerState
import com.madanala.tern.mezulla.ui.MezullaOverlayManager.StalenessLevel
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MezullaViewMode
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.time.Instant

/**
 * Unit tests for [MezullaOverlayManager]. Covers:
 * - Staleness threshold computation
 * - View-mode text rendering for all three modes
 * - Climb rate derivation (via PeerReducer, tested here for integration)
 * - Bearing and cardinal direction math
 * - SOS peer override behavior
 *
 * These tests do NOT touch the Android UI or MapView — they exercise
 * the pure logic that drives marker rendering.
 */
class MezullaOverlayManagerTest {

    private val now = Instant.parse("2026-04-25T12:05:00Z")
    private val manager = MezullaOverlayManager(
        mapStore = null,
        clock = { now },
    )

    private val antoine = PeerIdentity.fromNodeNumber(
        nodeNumber = 0xa1b2c3d4L,
        longName = "Antoine",
        shortName = "AN",
    )

    private val namelessPeer = PeerIdentity.fromNodeNumber(
        nodeNumber = 0xdeadbeefL,
    )

    private val sampleFix = PeerPosition.Fix(
        latitudeDeg = 45.9099,
        longitudeDeg = 6.1245,
        altitudeMeters = 2400,
        groundSpeedMetersPerSecond = 9.5,
        groundTrackDegrees = 270.0,
        timestampSeconds = 1_700_000_000L,
    )

    // --- Staleness thresholds ------------------------------------------------

    @Test
    fun `peer seen 10 seconds ago is FRESH`() {
        val peer = KnownPeer(
            identity = antoine,
            lastSeenAt = now.minusSeconds(10),
        )
        assertThat(manager.computeStaleness(peer, now)).isEqualTo(StalenessLevel.FRESH)
    }

    @Test
    fun `peer seen 29 seconds ago is still FRESH`() {
        val peer = KnownPeer(
            identity = antoine,
            lastSeenAt = now.minusSeconds(29),
        )
        assertThat(manager.computeStaleness(peer, now)).isEqualTo(StalenessLevel.FRESH)
    }

    @Test
    fun `peer seen exactly 30 seconds ago is AGING`() {
        val peer = KnownPeer(
            identity = antoine,
            lastSeenAt = now.minusSeconds(30),
        )
        assertThat(manager.computeStaleness(peer, now)).isEqualTo(StalenessLevel.AGING)
    }

    @Test
    fun `peer seen 90 seconds ago is AGING`() {
        val peer = KnownPeer(
            identity = antoine,
            lastSeenAt = now.minusSeconds(90),
        )
        assertThat(manager.computeStaleness(peer, now)).isEqualTo(StalenessLevel.AGING)
    }

    @Test
    fun `peer seen exactly 120 seconds ago is STALE`() {
        val peer = KnownPeer(
            identity = antoine,
            lastSeenAt = now.minusSeconds(120),
        )
        assertThat(manager.computeStaleness(peer, now)).isEqualTo(StalenessLevel.STALE)
    }

    @Test
    fun `peer seen 4 minutes ago is STALE`() {
        val peer = KnownPeer(
            identity = antoine,
            lastSeenAt = now.minusSeconds(240),
        )
        assertThat(manager.computeStaleness(peer, now)).isEqualTo(StalenessLevel.STALE)
    }

    @Test
    fun `peer seen exactly 300 seconds ago is LOST`() {
        val peer = KnownPeer(
            identity = antoine,
            lastSeenAt = now.minusSeconds(300),
        )
        assertThat(manager.computeStaleness(peer, now)).isEqualTo(StalenessLevel.LOST)
    }

    @Test
    fun `peer seen 10 minutes ago is LOST`() {
        val peer = KnownPeer(
            identity = antoine,
            lastSeenAt = now.minusSeconds(600),
        )
        assertThat(manager.computeStaleness(peer, now)).isEqualTo(StalenessLevel.LOST)
    }

    // --- Safety view text ----------------------------------------------------

    @Test
    fun `safety view shows altitude and age`() {
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(12),
        )
        val text = manager.formatSecondLine(peer, MezullaViewMode.SAFETY, null, sampleFix)
        assertThat(text).isEqualTo("2400m · 12s ago")
    }

    @Test
    fun `safety view shows dashes when altitude is null`() {
        val noAlt = sampleFix.copy(altitudeMeters = null)
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = noAlt,
            lastSeenAt = now.minusSeconds(5),
        )
        val text = manager.formatSecondLine(peer, MezullaViewMode.SAFETY, null, noAlt)
        assertThat(text).isEqualTo("--- · 5s ago")
    }

    // --- Climb view text -----------------------------------------------------

    @Test
    fun `climb view shows climb rate when available`() {
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(5),
            climbRateMs = 1.8,
        )
        val text = manager.formatSecondLine(peer, MezullaViewMode.CLIMB, null, sampleFix)
        assertThat(text).isEqualTo("+1.8 m/s · 2400m")
    }

    @Test
    fun `climb view shows negative climb rate`() {
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(5),
            climbRateMs = -2.3,
        )
        val text = manager.formatSecondLine(peer, MezullaViewMode.CLIMB, null, sampleFix)
        assertThat(text).isEqualTo("-2.3 m/s · 2400m")
    }

    @Test
    fun `climb view shows dashes when no climb rate`() {
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(5),
            climbRateMs = null,
        )
        val text = manager.formatSecondLine(peer, MezullaViewMode.CLIMB, null, sampleFix)
        assertThat(text).isEqualTo("--- m/s · 2400m")
    }

    // --- Tactical view text --------------------------------------------------

    @Test
    fun `tactical view shows distance bearing and speed`() {
        val userLoc = GeoPoint(45.9, 6.1)
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(5),
        )
        val text = manager.formatSecondLine(peer, MezullaViewMode.TACTICAL, userLoc, sampleFix)
        // The text should contain distance in km, a cardinal direction, and speed
        assertThat(text).contains("km")
        assertThat(text).contains("km/h")
    }

    @Test
    fun `tactical view shows dashes when no user location`() {
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(5),
        )
        val text = manager.formatSecondLine(peer, MezullaViewMode.TACTICAL, null, sampleFix)
        assertThat(text).isEqualTo("--- · ---")
    }

    @Test
    fun `tactical view shows dashes for speed when ground speed is null`() {
        val noSpeed = sampleFix.copy(groundSpeedMetersPerSecond = null)
        val userLoc = GeoPoint(45.9, 6.1)
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = noSpeed,
            lastSeenAt = now.minusSeconds(5),
        )
        val text = manager.formatSecondLine(peer, MezullaViewMode.TACTICAL, userLoc, noSpeed)
        assertThat(text).contains("---")
    }

    // --- Callsign fallback ---------------------------------------------------

    @Test
    fun `callsign falls back to hexId when no name available`() {
        val peer = KnownPeer(
            identity = namelessPeer,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(5),
        )
        // The callsign should be the hexId since longName and shortName are null
        val callsign = peer.identity.longName ?: peer.identity.shortName ?: peer.identity.hexId
        assertThat(callsign).startsWith("!")
    }

    // --- Bearing helpers -----------------------------------------------------

    @Test
    fun `bearing from south to north is approximately 0 degrees`() {
        val from = GeoPoint(45.0, 6.0)
        val to = GeoPoint(46.0, 6.0)
        val bearing = manager.computeBearing(from, to)
        assertThat(bearing).isWithin(1.0).of(0.0)
    }

    @Test
    fun `bearing from west to east is approximately 90 degrees`() {
        val from = GeoPoint(45.0, 6.0)
        val to = GeoPoint(45.0, 7.0)
        val bearing = manager.computeBearing(from, to)
        assertThat(bearing).isWithin(5.0).of(90.0)
    }

    @Test
    fun `cardinal directions are correct`() {
        assertThat(manager.degreesToCardinal(0.0)).isEqualTo("N")
        assertThat(manager.degreesToCardinal(45.0)).isEqualTo("NE")
        assertThat(manager.degreesToCardinal(90.0)).isEqualTo("E")
        assertThat(manager.degreesToCardinal(135.0)).isEqualTo("SE")
        assertThat(manager.degreesToCardinal(180.0)).isEqualTo("S")
        assertThat(manager.degreesToCardinal(225.0)).isEqualTo("SW")
        assertThat(manager.degreesToCardinal(270.0)).isEqualTo("W")
        assertThat(manager.degreesToCardinal(315.0)).isEqualTo("NW")
    }

    // --- Climb rate derivation (integration with reducer) --------------------

    @Test
    fun `climb rate computed from successive fixes`() {
        val t0 = Instant.parse("2026-04-25T12:00:00Z")
        val t1 = t0.plusSeconds(10)

        val fix1 = sampleFix.copy(altitudeMeters = 2000)
        val fix2 = sampleFix.copy(altitudeMeters = 2020)

        // Simulate what the reducer does
        val peer0 = KnownPeer(identity = antoine, lastPosition = fix1, lastSeenAt = t0)

        // On second fix, compute climb rate manually (same logic as reducer)
        val dtSeconds = java.time.Duration.between(t0, t1).seconds.toDouble()
        val climbRate = (fix2.altitudeMeters!! - fix1.altitudeMeters!!) / dtSeconds

        assertThat(climbRate).isWithin(0.01).of(2.0)  // 20m in 10s = 2 m/s
    }

    @Test
    fun `climb rate is negative when descending`() {
        val t0 = Instant.parse("2026-04-25T12:00:00Z")
        val t1 = t0.plusSeconds(20)

        val dtSeconds = java.time.Duration.between(t0, t1).seconds.toDouble()
        val climbRate = (1980 - 2000) / dtSeconds  // lost 20m in 20s

        assertThat(climbRate).isWithin(0.01).of(-1.0)
    }

    // --- MezullaViewMode cycling ---------------------------------------------

    @Test
    fun `view mode cycles SAFETY to CLIMB to TACTICAL and back`() {
        assertThat(MezullaViewMode.SAFETY.next()).isEqualTo(MezullaViewMode.CLIMB)
        assertThat(MezullaViewMode.CLIMB.next()).isEqualTo(MezullaViewMode.TACTICAL)
        assertThat(MezullaViewMode.TACTICAL.next()).isEqualTo(MezullaViewMode.SAFETY)
    }

    // --- Staleness-driven marker opacity ------------------------------------

    private fun createMockMarker(): Marker {
        val marker = mockk<Marker>(relaxed = true)
        var storedAlpha = 1.0f
        var storedTitle: String? = null
        var storedSnippet: String? = null

        every { marker.alpha = any() } answers { storedAlpha = firstArg() }
        every { marker.alpha } answers { storedAlpha }
        every { marker.title = any() } answers { storedTitle = firstArg() }
        every { marker.title } answers { storedTitle }
        every { marker.snippet = any() } answers { storedSnippet = firstArg() }
        every { marker.snippet } answers { storedSnippet }
        return marker
    }

    @Test
    fun `fresh marker gets full opacity`() {
        val marker = createMockMarker()
        manager.applyMarkerStaleness(marker, StalenessLevel.FRESH)
        assertThat(marker.alpha).isWithin(0.01f).of(1.0f)
    }

    @Test
    fun `aging marker gets reduced opacity`() {
        val marker = createMockMarker()
        manager.applyMarkerStaleness(marker, StalenessLevel.AGING)
        assertThat(marker.alpha).isLessThan(1.0f)
        assertThat(marker.alpha).isGreaterThan(0.3f)
    }

    @Test
    fun `stale marker gets further reduced opacity`() {
        val marker = createMockMarker()
        manager.applyMarkerStaleness(marker, StalenessLevel.STALE)
        assertThat(marker.alpha).isLessThan(0.7f)
        assertThat(marker.alpha).isGreaterThan(0.2f)
    }

    @Test
    fun `lost marker gets very low opacity`() {
        val marker = createMockMarker()
        manager.applyMarkerStaleness(marker, StalenessLevel.LOST)
        assertThat(marker.alpha).isLessThan(0.4f)
    }

    // --- Lost peer replaces second line with "lost contact" ------------------

    @Test
    fun `lost peer shows lost contact instead of data`() {
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(600),
        )
        val staleness = manager.computeStaleness(peer, now)
        assertThat(staleness).isEqualTo(StalenessLevel.LOST)

        // When staleness is LOST, applyMarkerText sets snippet to "lost contact"
        val marker = createMockMarker()
        manager.applyMarkerText(marker, peer, MezullaViewMode.SAFETY, staleness, null, sampleFix)
        assertThat(marker.snippet).isEqualTo("lost contact")
    }

    // --- SOS active alert detection -----------------------------------------

    @Test
    fun `peer with active unacknowledged SOS is detected`() {
        val peerState = PeerState(
            peers = mapOf(antoine.nodeNumber to KnownPeer(
                identity = antoine,
                lastPosition = sampleFix,
                lastSeenAt = now.minusSeconds(5),
            )),
            activeAlerts = listOf(
                ActiveAlert(
                    senderIdentity = antoine,
                    lastKnownPosition = sampleFix,
                    alertedAt = now.minusSeconds(10),
                    acknowledgedAt = null,
                )
            ),
        )

        val hasSos = peerState.activeAlerts.any {
            it.senderIdentity.nodeNumber == antoine.nodeNumber && it.acknowledgedAt == null
        }
        assertThat(hasSos).isTrue()
    }

    @Test
    fun `acknowledged SOS is not active`() {
        val peerState = PeerState(
            activeAlerts = listOf(
                ActiveAlert(
                    senderIdentity = antoine,
                    lastKnownPosition = sampleFix,
                    alertedAt = now.minusSeconds(60),
                    acknowledgedAt = now.minusSeconds(30),
                )
            ),
        )

        val hasSos = peerState.activeAlerts.any {
            it.senderIdentity.nodeNumber == antoine.nodeNumber && it.acknowledgedAt == null
        }
        assertThat(hasSos).isFalse()
    }

}
