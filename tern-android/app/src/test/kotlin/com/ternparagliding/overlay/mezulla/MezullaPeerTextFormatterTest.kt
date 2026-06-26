package com.ternparagliding.overlay.mezulla

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition
import com.ternparagliding.mezulla.redux.KnownPeer
import com.ternparagliding.overlay.mezulla.MezullaPeerTextFormatter.StalenessLevel
import com.ternparagliding.overlay.priority.Position
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [MezullaPeerTextFormatter]. Covers staleness thresholds and the
 * single always-on detail line (no view modes), plus edge cases (missing
 * altitude, missing speed, lost contact, nameless peer).
 *
 * Pure logic tests — no Android, no MapLibre.
 */
class MezullaPeerTextFormatterTest {

    private val now = Instant.parse("2026-04-25T12:05:00Z")

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

    // ── Staleness thresholds ──────────────────────────────────────────

    @Test
    fun `peer seen 10 seconds ago is FRESH`() {
        val peer = KnownPeer(identity = antoine, lastSeenAt = now.minusSeconds(10))
        assertThat(MezullaPeerTextFormatter.computeStaleness(peer, now))
            .isEqualTo(StalenessLevel.FRESH)
    }

    @Test
    fun `peer seen 29 seconds ago is still FRESH`() {
        val peer = KnownPeer(identity = antoine, lastSeenAt = now.minusSeconds(29))
        assertThat(MezullaPeerTextFormatter.computeStaleness(peer, now))
            .isEqualTo(StalenessLevel.FRESH)
    }

    @Test
    fun `peer seen exactly 30 seconds ago is AGING`() {
        val peer = KnownPeer(identity = antoine, lastSeenAt = now.minusSeconds(30))
        assertThat(MezullaPeerTextFormatter.computeStaleness(peer, now))
            .isEqualTo(StalenessLevel.AGING)
    }

    @Test
    fun `peer seen 90 seconds ago is AGING`() {
        val peer = KnownPeer(identity = antoine, lastSeenAt = now.minusSeconds(90))
        assertThat(MezullaPeerTextFormatter.computeStaleness(peer, now))
            .isEqualTo(StalenessLevel.AGING)
    }

    @Test
    fun `peer seen exactly 120 seconds ago is STALE`() {
        val peer = KnownPeer(identity = antoine, lastSeenAt = now.minusSeconds(120))
        assertThat(MezullaPeerTextFormatter.computeStaleness(peer, now))
            .isEqualTo(StalenessLevel.STALE)
    }

    @Test
    fun `peer seen 4 minutes ago is STALE`() {
        val peer = KnownPeer(identity = antoine, lastSeenAt = now.minusSeconds(240))
        assertThat(MezullaPeerTextFormatter.computeStaleness(peer, now))
            .isEqualTo(StalenessLevel.STALE)
    }

    @Test
    fun `peer seen exactly 300 seconds ago is LOST`() {
        val peer = KnownPeer(identity = antoine, lastSeenAt = now.minusSeconds(300))
        assertThat(MezullaPeerTextFormatter.computeStaleness(peer, now))
            .isEqualTo(StalenessLevel.LOST)
    }

    @Test
    fun `peer seen 10 minutes ago is LOST`() {
        val peer = KnownPeer(identity = antoine, lastSeenAt = now.minusSeconds(600))
        assertThat(MezullaPeerTextFormatter.computeStaleness(peer, now))
            .isEqualTo(StalenessLevel.LOST)
    }

    // ── Staleness visual properties ──────────────────────────────────

    @Test
    fun `fresh opacity is full`() {
        assertThat(MezullaPeerTextFormatter.opacityForStaleness(StalenessLevel.FRESH))
            .isEqualTo(1.0f)
    }

    @Test
    fun `aging opacity is reduced`() {
        val opacity = MezullaPeerTextFormatter.opacityForStaleness(StalenessLevel.AGING)
        assertThat(opacity).isLessThan(1.0f)
        assertThat(opacity).isGreaterThan(0.3f)
    }

    @Test
    fun `stale opacity is further reduced`() {
        val opacity = MezullaPeerTextFormatter.opacityForStaleness(StalenessLevel.STALE)
        assertThat(opacity).isLessThan(0.7f)
        assertThat(opacity).isGreaterThan(0.2f)
    }

    @Test
    fun `lost opacity is very low`() {
        assertThat(MezullaPeerTextFormatter.opacityForStaleness(StalenessLevel.LOST))
            .isLessThan(0.4f)
    }

    @Test
    fun `staleness color hex values match specification`() {
        assertThat(MezullaPeerTextFormatter.colorHexForStaleness(StalenessLevel.FRESH))
            .isEqualTo("#FFFFFF")
        assertThat(MezullaPeerTextFormatter.colorHexForStaleness(StalenessLevel.AGING))
            .isEqualTo("#FFD600")
        assertThat(MezullaPeerTextFormatter.colorHexForStaleness(StalenessLevel.STALE))
            .isEqualTo("#FF8F00")
        assertThat(MezullaPeerTextFormatter.colorHexForStaleness(StalenessLevel.LOST))
            .isEqualTo("#9E9E9E")
    }

    // ── Always-on detail line (no view modes) ────────────────────────

    @Test
    fun `detail line packs altitude climb distance and speed`() {
        val pilotPos = Position(45.9, 6.1)
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(5),
            climbRateMs = 1.8,
        )
        val text = MezullaPeerTextFormatter.formatSecondLine(peer, sampleFix, pilotPos, now)
        assertThat(text).contains("2400m")
        assertThat(text).contains("▲1.8 m/s")
        assertThat(text).contains("km ")   // distance + bearing
        assertThat(text).contains("km/h")  // ground speed
    }

    @Test
    fun `detail line shows negative climb with a down arrow`() {
        val peer = KnownPeer(
            identity = antoine,
            lastPosition = sampleFix,
            lastSeenAt = now.minusSeconds(5),
            climbRateMs = -2.3,
        )
        val text = MezullaPeerTextFormatter.formatSecondLine(peer, sampleFix, null, now)
        assertThat(text).contains("▼2.3 m/s")
    }

    @Test
    fun `detail line omits distance when pilot position is unknown`() {
        val peer = KnownPeer(identity = antoine, lastPosition = sampleFix, lastSeenAt = now.minusSeconds(5))
        // No climb, no pilot pos → altitude + ground speed only.
        val text = MezullaPeerTextFormatter.formatSecondLine(peer, sampleFix, null, now)
        assertThat(text).isEqualTo("2400m · 34 km/h")
    }

    @Test
    fun `detail line omits altitude when unknown`() {
        val noAlt = sampleFix.copy(altitudeMeters = null)
        val peer = KnownPeer(identity = antoine, lastPosition = noAlt, lastSeenAt = now.minusSeconds(5))
        val text = MezullaPeerTextFormatter.formatSecondLine(peer, noAlt, null, now)
        assertThat(text).isEqualTo("34 km/h")
    }

    @Test
    fun `detail line omits speed when ground speed is unknown`() {
        val noSpeed = sampleFix.copy(groundSpeedMetersPerSecond = null)
        val peer = KnownPeer(identity = antoine, lastPosition = noSpeed, lastSeenAt = now.minusSeconds(5))
        val text = MezullaPeerTextFormatter.formatSecondLine(peer, noSpeed, null, now)
        assertThat(text).isEqualTo("2400m")
    }

    @Test
    fun `display text includes callsign on first line`() {
        val peer = KnownPeer(identity = antoine, lastPosition = sampleFix, lastSeenAt = now.minusSeconds(12))
        val text = MezullaPeerTextFormatter.displayText(peer, sampleFix, StalenessLevel.FRESH, null, now)
        assertThat(text).startsWith("Antoine\n")
        assertThat(text).contains("2400m")
    }

    // ── Lost contact override ────────────────────────────────────────

    @Test
    fun `lost peer display text shows lost contact`() {
        val peer = KnownPeer(identity = antoine, lastPosition = sampleFix, lastSeenAt = now.minusSeconds(600))
        val text = MezullaPeerTextFormatter.displayText(peer, sampleFix, StalenessLevel.LOST, null, now)
        assertThat(text).isEqualTo("Antoine\nlost contact")
    }

    // ── Stale peer gets warning marker ───────────────────────────────

    @Test
    fun `stale peer display text has warning indicator`() {
        val peer = KnownPeer(identity = antoine, lastPosition = sampleFix, lastSeenAt = now.minusSeconds(200))
        val text = MezullaPeerTextFormatter.displayText(peer, sampleFix, StalenessLevel.STALE, null, now)
        assertThat(text).endsWith("⚠")
    }

    @Test
    fun `fresh peer display text has no warning indicator`() {
        val peer = KnownPeer(identity = antoine, lastPosition = sampleFix, lastSeenAt = now.minusSeconds(5))
        val text = MezullaPeerTextFormatter.displayText(peer, sampleFix, StalenessLevel.FRESH, null, now)
        assertThat(text).doesNotContain("⚠")
    }

    // ── Callsign fallback ────────────────────────────────────────────

    @Test
    fun `callsign uses longName when available`() {
        val peer = KnownPeer(identity = antoine, lastSeenAt = now)
        assertThat(MezullaPeerTextFormatter.callsign(peer)).isEqualTo("Antoine")
    }

    @Test
    fun `callsign falls back to shortName`() {
        val identity = PeerIdentity.fromNodeNumber(
            nodeNumber = 0x11223344L,
            shortName = "CB",
        )
        val peer = KnownPeer(identity = identity, lastSeenAt = now)
        assertThat(MezullaPeerTextFormatter.callsign(peer)).isEqualTo("CB")
    }

    @Test
    fun `callsign falls back to hexId when no names`() {
        val peer = KnownPeer(identity = namelessPeer, lastSeenAt = now)
        assertThat(MezullaPeerTextFormatter.callsign(peer)).startsWith("!")
    }

    // ── Bearing and cardinal helpers ─────────────────────────────────

    @Test
    fun `bearing from south to north is approximately 0 degrees`() {
        val from = Position(45.0, 6.0)
        val to = Position(46.0, 6.0)
        assertThat(MezullaPeerTextFormatter.computeBearing(from, to))
            .isWithin(1.0).of(0.0)
    }

    @Test
    fun `bearing from west to east is approximately 90 degrees`() {
        val from = Position(45.0, 6.0)
        val to = Position(45.0, 7.0)
        assertThat(MezullaPeerTextFormatter.computeBearing(from, to))
            .isWithin(5.0).of(90.0)
    }

    @Test
    fun `cardinal directions are correct`() {
        assertThat(MezullaPeerTextFormatter.degreesToCardinal(0.0)).isEqualTo("N")
        assertThat(MezullaPeerTextFormatter.degreesToCardinal(45.0)).isEqualTo("NE")
        assertThat(MezullaPeerTextFormatter.degreesToCardinal(90.0)).isEqualTo("E")
        assertThat(MezullaPeerTextFormatter.degreesToCardinal(135.0)).isEqualTo("SE")
        assertThat(MezullaPeerTextFormatter.degreesToCardinal(180.0)).isEqualTo("S")
        assertThat(MezullaPeerTextFormatter.degreesToCardinal(225.0)).isEqualTo("SW")
        assertThat(MezullaPeerTextFormatter.degreesToCardinal(270.0)).isEqualTo("W")
        assertThat(MezullaPeerTextFormatter.degreesToCardinal(315.0)).isEqualTo("NW")
    }
}
