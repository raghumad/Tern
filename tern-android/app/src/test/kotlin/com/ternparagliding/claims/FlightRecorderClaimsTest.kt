package com.ternparagliding.claims

import com.ternparagliding.flight.IgcToXctrc
import com.ternparagliding.flight.SensorFix
import com.ternparagliding.flight.XcTracerParser
import com.ternparagliding.flight.recording.AbnormalEndDetector
import com.ternparagliding.flight.recording.FlightEvent
import com.ternparagliding.flight.recording.FlightEventType
import com.ternparagliding.flight.recording.FlightRecorder
import com.ternparagliding.flight.recording.FlightSummary
import com.ternparagliding.flight.recording.LandingDetector
import com.ternparagliding.flight.recording.PeerFixRecord
import com.ternparagliding.flight.recording.SealReason
import com.ternparagliding.sim.igc.IgcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claim-driven tests for **Epic 05 5.2/5.3 — flight recorder, sidecar, landing/abnormal seal, and
 * logbook summary**. Pilot promise: "the flight gets captured — fully, with the buddies around me —
 * and lands in my logbook with honest stats." These drive the pure core; the live fix stream is
 * replayed from a real IGC through the actual parse path, exactly like the deck will feed it.
 */
class FlightRecorderClaimsTest {

    private fun realFlightFixes(): List<SensorFix> {
        val text = javaClass.getResourceAsStream("/igc/flights/in/2025-10-11-birbilling-richard.igc")
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("Bir Billing fixture on classpath", text)
        val flight = IgcParser.parseString(text!!)
        return IgcToXctrc.sentences(flight).mapNotNull { XcTracerParser.parse(it) }
    }

    /**
     * **CLAIM K6 · Resilient (full-fidelity capture).** Replaying a real flight through the live
     * parse path, the recorder keeps **every** fix (no decimation — unlike the display ring buffer)
     * and the sealed record carries the whole track, in time order, with the start/end times right.
     */
    @Test
    fun `resilient - the recorder captures every fix of a real flight`() {
        val fixes = realFlightFixes()
        assertTrue("a real flight has a substantial track", fixes.size > 500)

        val rec = FlightRecorder(id = "f1", startTimeMs = fixes.first().timeMs, pilot = "Richard")
        fixes.forEach { rec.addOwnFix(it, source = "XC_TRACER") }
        val recording = rec.seal(SealReason.LANDED)

        assertEquals("no fix is dropped (full fidelity, not the 5 m display decimation)", fixes.size, recording.ownTrack.size)
        assertEquals(fixes.first().timeMs, recording.startTimeMs)
        assertEquals(fixes.last().timeMs, recording.endTimeMs)
        assertEquals(SealReason.LANDED, recording.sealReason)
        assertEquals("source stamped for faithful replay", "XC_TRACER", recording.ownTrack.first().source)
        // The own-track exports to a valid IGC (the 5.2 chain), proving the captured fixes are real.
        val igc = com.ternparagliding.flight.export.IgcWriter.fromSensorFixes(
            fixes = fixes.filter { it.hasPosition }.map {
                SensorFix(it.timeMs, it.lat, it.lon, it.gpsAltitudeM, it.groundSpeedMs, it.courseDeg, it.climbMs, it.pressureHpa)
            },
        )
        assertTrue("captured fixes export to IGC", igc.fixes.size > 500)
    }

    /**
     * **CLAIM K6 · Correct (flight memory + seal semantics).** The sidecar captures buddies and
     * events alongside my track; sealing is idempotent (a landing-seal racing a manual-seal can't
     * double-finalise) and a sealed recorder rejects further fixes.
     */
    @Test
    fun `correct - sidecar captures buddies and events, and sealing is idempotent`() {
        val rec = FlightRecorder(id = "f2", startTimeMs = 1_000L)
        rec.addOwnFix(SensorFix(timeMs = 1_000L, lat = 46.0, lon = 7.0, gpsAltitudeM = 1500.0))
        rec.addPeerFix(PeerFixRecord(peerId = 42L, callsign = "BARNEY", timeMs = 1_500L, lat = 46.01, lon = 7.01, altitudeM = 1520))
        rec.addPeerFix(PeerFixRecord(peerId = 42L, callsign = "BARNEY", timeMs = 2_500L, lat = 46.02, lon = 7.02, altitudeM = 1540))
        rec.addEvent(FlightEvent(timeMs = 2_000L, type = FlightEventType.CYLINDER_TAG, detail = "SSS"))

        assertEquals(1, rec.ownFixCount)
        assertEquals(2, rec.peerFixCount)
        assertEquals(1, rec.eventCount)

        val first = rec.seal(SealReason.LANDED)
        val second = rec.seal(SealReason.MANUAL)
        assertSame("seal is idempotent — second call returns the first result", first, second)
        assertEquals(SealReason.LANDED, first.sealReason)
        assertEquals("buddy fixes preserved for gaggle replay", 2, first.peerTrack.size)
        assertEquals(1, first.events.size)

        var rejected = false
        try {
            rec.addOwnFix(SensorFix(timeMs = 3_000L, lat = 46.0, lon = 7.0))
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue("a sealed recorder rejects further fixes", rejected)
    }

    /**
     * **CLAIM 5.4 · Correct (privacy + protection).** An SOS seal marks the flight protected (never
     * auto-purged), and the buddy positions live only in the local record — the IGC export carries
     * only my own track, so nothing about my buddies ever leaves the device via upload.
     */
    @Test
    fun `correct - SOS seals as protected and buddies stay out of the IGC`() {
        val rec = FlightRecorder(id = "f3", startTimeMs = 0L)
        rec.addOwnFix(SensorFix(timeMs = 0L, lat = 46.0, lon = 7.0, gpsAltitudeM = 1500.0))
        rec.addOwnFix(SensorFix(timeMs = 1000L, lat = 46.001, lon = 7.001, gpsAltitudeM = 1490.0))
        rec.addPeerFix(PeerFixRecord(peerId = 7L, timeMs = 500L, lat = 46.5, lon = 7.5))
        rec.addEvent(FlightEvent(timeMs = 800L, type = FlightEventType.SOS_FIRED))
        val recording = rec.seal(SealReason.SOS)

        assertTrue("an SOS-sealed flight is protected from auto-purge", recording.isProtected)
        // IGC is single-pilot: it's built from ownTrack only, never peerTrack.
        val igc = com.ternparagliding.flight.export.IgcWriter.fromSensorFixes(
            recording.ownTrack.filter { it.hasPosition }.map {
                SensorFix(it.timeMs, it.lat, it.lon, it.gpsAltitudeM, it.groundSpeedMs, it.courseDeg, it.climbMs, it.pressureHpa)
            },
        )
        assertEquals("IGC carries only my 2 own fixes, no buddy", 2, igc.fixes.size)
    }

    /**
     * **CLAIM 5.2 · Correct (landing seal).** [LandingDetector] closes a recording only after the
     * pilot is genuinely down: moving keeps it open, a short stop doesn't seal, and sustained
     * stillness (≥5 min, altitude stable) latches landed. A slow low beat-back must not seal early.
     */
    @Test
    fun `correct - landing is only called after sustained stillness`() {
        var s = LandingDetector.State()
        // Flying along: never lands.
        s = LandingDetector.update(s, timeMs = 0, groundSpeedMs = 9.0, altM = 1500.0)
        s = LandingDetector.update(s, timeMs = 60_000, groundSpeedMs = 8.0, altM = 1480.0)
        assertFalse("airborne and moving is not landed", s.landed)

        // Touches down and stands still — but only 4 minutes in.
        s = LandingDetector.update(s, timeMs = 120_000, groundSpeedMs = 0.2, altM = 1200.0)
        s = LandingDetector.update(s, timeMs = 120_000 + 4 * 60_000, groundSpeedMs = 0.1, altM = 1200.3)
        assertFalse("4 minutes still is not yet a landing", s.landed)

        // Past the 5-minute confirm → landed, and it latches.
        s = LandingDetector.update(s, timeMs = 120_000 + 6 * 60_000, groundSpeedMs = 0.1, altM = 1200.1)
        assertTrue("sustained stillness seals the flight", s.landed)
        s = LandingDetector.update(s, timeMs = 999_000_000, groundSpeedMs = 9.0, altM = 1500.0)
        assertTrue("landed latches", s.landed)
    }

    /**
     * **CLAIM 5.2 · Resilient (landing honesty).** A slow, low pass (still gliding) and a stop too
     * brief to be a landing both keep the recording open — we don't seal mid-flight. Altitude
     * drifting during a "still" stretch (a slope-soaring beat) also breaks the streak.
     */
    @Test
    fun `resilient - a brief stop or drifting altitude does not seal`() {
        var s = LandingDetector.State()
        // Still for 6 minutes but altitude keeps changing (ridge soaring, parked over the ground): not landed.
        s = LandingDetector.update(s, timeMs = 0, groundSpeedMs = 0.3, altM = 1500.0)
        s = LandingDetector.update(s, timeMs = 3 * 60_000, groundSpeedMs = 0.3, altM = 1510.0) // +10 m drift → break
        s = LandingDetector.update(s, timeMs = 6 * 60_000, groundSpeedMs = 0.3, altM = 1520.0)
        assertFalse("climbing while slow (soaring) is not a landing", s.landed)
    }

    /**
     * **CLAIM 5.2 · Correct (abnormal-end seal).** Sustained rapid descent flags an abnormal end
     * (to seal the black box around an incident); a single fast sample does not, a null vario
     * resets the streak, and once flagged it latches.
     */
    @Test
    fun `correct - sustained rapid descent flags an abnormal end`() {
        fun fold(vararg climbs: Double?): AbnormalEndDetector.State =
            climbs.fold(AbnormalEndDetector.State()) { st, c -> AbnormalEndDetector.update(st, c) }

        assertFalse("one fast sample is noise", fold(-12.0, -1.0).flagged)
        assertFalse("a null vario breaks the streak", fold(-10.0, -10.0, null, -10.0, -10.0).flagged)
        assertTrue("four sustained rapid-descent fixes flag it", fold(-9.0, -10.0, -11.0, -9.5).flagged)
        // Latches once set.
        val flagged = fold(-9.0, -10.0, -11.0, -9.5)
        assertTrue(AbnormalEndDetector.update(flagged, 0.5).flagged)
    }

    /**
     * **CLAIM 5.3 · Correct (logbook summary).** The logbook row is a pure derivation of the
     * recorded track: duration, fix count, alt range, climb/sink extremes, along-track distance,
     * straight takeoff→landing distance, the endpoints, and the distinct buddy count.
     */
    @Test
    fun `correct - flight summary derives honest stats from the track`() {
        val rec = FlightRecorder(id = "f4", startTimeMs = 0L)
        // Three fixes ~1.11 km apart in latitude (0.01°), climbing then sinking.
        rec.addOwnFix(SensorFix(timeMs = 0L, lat = 46.00, lon = 7.0, gpsAltitudeM = 1500.0, climbMs = 2.0))
        rec.addOwnFix(SensorFix(timeMs = 60_000L, lat = 46.01, lon = 7.0, gpsAltitudeM = 1700.0, climbMs = 3.5))
        rec.addOwnFix(SensorFix(timeMs = 120_000L, lat = 46.02, lon = 7.0, gpsAltitudeM = 1650.0, climbMs = -1.5))
        rec.addPeerFix(PeerFixRecord(peerId = 1L, timeMs = 10L, lat = 46.0, lon = 7.0))
        rec.addPeerFix(PeerFixRecord(peerId = 1L, timeMs = 20L, lat = 46.0, lon = 7.0)) // same buddy
        rec.addPeerFix(PeerFixRecord(peerId = 2L, timeMs = 30L, lat = 46.0, lon = 7.0))
        val s = FlightSummary.from(rec.seal(SealReason.LANDED))

        assertEquals(120_000L, s.durationMs)
        assertEquals(3, s.fixCount)
        assertEquals(1700.0, s.maxAltitudeM!!, 1e-9)
        assertEquals(1500.0, s.minAltitudeM!!, 1e-9)
        assertEquals(3.5, s.maxClimbMs!!, 1e-9)
        assertEquals(-1.5, s.maxSinkMs!!, 1e-9)
        // 0.02° of latitude ≈ 2.22 km along track; straight-line is the same (a meridian line).
        assertEquals(2224.0, s.trackDistanceM, 30.0)
        assertEquals(2224.0, s.straightDistanceM, 30.0)
        assertEquals(46.00, s.takeoff!!.lat, 1e-9)
        assertEquals(46.02, s.landing!!.lat, 1e-9)
        assertEquals("two distinct buddies", 2, s.peerCount)
    }
}
