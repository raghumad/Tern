package com.ternparagliding.claims

import com.ternparagliding.flight.SensorFix
import com.ternparagliding.mezulla.PositionBroadcastPolicy
import com.ternparagliding.mezulla.peerPositionFix
import com.ternparagliding.mezulla.toPeerPositionFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claim-driven tests for **what we broadcast over Mezulla** — the Bir Billing scrub outcome.
 *
 * Pilot-visible promise: a buddy sees us. The decision (when to send) and the mapping (what a
 * position frame carries) are the two pure pieces behind that; the BLE write itself is the codec's,
 * already tested. Cadence is vario-synced with a floor so the shared LoRa channel isn't flooded.
 */
class OwnPositionBroadcastClaimsTest {

    // -- PositionBroadcastPolicy: when do we send? --------------------------

    @Test
    fun `a fix with no position is never broadcast`() {
        // The board can't relay a fix that has no lat/lon — nothing to send.
        assertFalse(PositionBroadcastPolicy.shouldBroadcast(hasPosition = false, lastSentMs = null, nowMs = 1_000))
        assertFalse(PositionBroadcastPolicy.shouldBroadcast(hasPosition = false, lastSentMs = 0, nowMs = 10_000))
    }

    @Test
    fun `the first positioned fix is broadcast immediately`() {
        assertTrue(PositionBroadcastPolicy.shouldBroadcast(hasPosition = true, lastSentMs = null, nowMs = 12_345))
    }

    @Test
    fun `fixes inside the floor are suppressed, then released`() {
        val last = 100_000L
        // Below the floor → hold (protects shared LoRa airtime against a fast/jittery fix stream).
        assertFalse(PositionBroadcastPolicy.shouldBroadcast(true, last, last + PositionBroadcastPolicy.MIN_INTERVAL_MS - 1))
        // At/after the floor → send.
        assertTrue(PositionBroadcastPolicy.shouldBroadcast(true, last, last + PositionBroadcastPolicy.MIN_INTERVAL_MS))
        assertTrue(PositionBroadcastPolicy.shouldBroadcast(true, last, last + 5_000))
    }

    @Test
    fun `a one-hertz vario stream passes essentially every fix`() {
        // Floor is below 1 s, so a steady ~1 Hz vario (with a little jitter) is never throttled.
        var lastSent: Long? = null
        var sent = 0
        for (i in 0 until 10) {
            val now = i * 1000L + (if (i % 2 == 0) 0L else 40L) // 1 Hz, ±40 ms jitter
            if (PositionBroadcastPolicy.shouldBroadcast(true, lastSent, now)) { sent++; lastSent = now }
        }
        assertEquals("a 1 Hz vario should broadcast every fix", 10, sent)
    }

    // -- toPeerPositionFix: what a frame carries ---------------------------

    @Test
    fun `adapter maps the fields all three view modes read`() {
        val fix = SensorFix(
            timeMs = 1_700_000_123_456L,
            lat = 32.04, lon = 76.66,        // Bir
            gpsAltitudeM = 2412.7,           // SAFETY (alt) + CLIMB (alt-derived)
            groundSpeedMs = 11.3,            // TACTICAL (ground speed)
            courseDeg = 215.0,               // puck track arrow
        )
        val pf = fix.toPeerPositionFix()!!
        assertEquals(32.04, pf.latitudeDeg, 1e-9)
        assertEquals(76.66, pf.longitudeDeg, 1e-9)
        assertEquals(2413, pf.altitudeMeters)            // rounded to whole metres
        assertEquals(11.3, pf.groundSpeedMetersPerSecond!!, 1e-9)
        assertEquals(215.0, pf.groundTrackDegrees!!, 1e-9)
        assertEquals(1_700_000_123L, pf.timestampSeconds) // epoch ms → seconds
    }

    @Test
    fun `adapter returns null for a fix without position`() {
        val varioOnly = SensorFix(timeMs = 1_000, climbMs = 2.1, pressureHpa = 900.0) // no lat/lon yet
        assertNull(varioOnly.toPeerPositionFix())
    }

    @Test
    fun `adapter tolerates missing optional channels`() {
        val sparse = SensorFix(timeMs = 2_000, lat = 1.0, lon = 2.0) // no alt/speed/track
        val pf = sparse.toPeerPositionFix()!!
        assertNull(pf.altitudeMeters)
        assertNull(pf.groundSpeedMetersPerSecond)
        assertNull(pf.groundTrackDegrees)
    }

    @Test
    fun `shared builder serves the phone-GPS path with rounding and null-safety`() {
        // A basic phone fix: altitude present, no speed/bearing reported.
        val pf = peerPositionFix(
            latitudeDeg = 32.04, longitudeDeg = 76.66,
            altitudeM = 2412.7, groundSpeedMs = null, trackDeg = null,
            timeMs = 1_700_000_999_000L,
        )
        assertEquals(2413, pf.altitudeMeters)
        assertNull(pf.groundSpeedMetersPerSecond)
        assertNull(pf.groundTrackDegrees)
        assertEquals(1_700_000_999L, pf.timestampSeconds)
    }
}
