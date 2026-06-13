package com.ternparagliding.claims

import com.ternparagliding.flight.NmeaLineAssembler
import com.ternparagliding.flight.WindEstimator
import com.ternparagliding.flight.WindEstimator.TrackSample
import com.ternparagliding.flight.XcTracerParser
import java.time.LocalDateTime
import java.time.ZoneOffset
import com.ternparagliding.sim.igc.IgcParser
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claim-driven tests for **K7 — Flight state / wind**.
 *
 * These drive the real [WindEstimator] (no mock of the math under test): once against a
 * synthetic, exactly-known drifting circle (the Correct axis — recovery to a tolerance), and
 * once against a real thermal flight replayed from an IGC fixture (the Resilient axis — does
 * it hold up on messy, real GPS, and does it stay honest when there's no circle to read).
 */
class FlightStateClaimsTest {

    /** Smallest angle between two compass bearings (deg, 0..180). */
    private fun angularDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    /**
     * **CLAIM K7 · Correct (wind from drift).** A glider circling at a known airspeed in a
     * known wind traces a circle in velocity space whose centre is the wind. Build exactly
     * that — airspeed 10 m/s, wind 4 m/s from 240° (SW) — and the estimator recovers both
     * the speed and the *from* direction to a tight tolerance. This is the math, pinned.
     */
    @Test
    fun `correct - circling wind is recovered from a known drifting circle`() {
        val airspeed = 10.0
        val windSpeed = 4.0
        val windFromDeg = 240.0
        // Wind blows TOWARD (from + 180) = 60°; its E/N components add to every air vector.
        val toRad = Math.toRadians(windFromDeg + 180.0)
        val wE = windSpeed * sin(toRad)
        val wN = windSpeed * cos(toRad)

        // A circle launched from (0,0); each fix is reached by integrating ground velocity.
        // Sample a full turn at 2 s spacing: air vector rotates, wind drifts the whole circle.
        val samples = ArrayList<TrackSample>()
        val dt = 2.0
        var lat = 46.0; var lon = 7.0; var t = 0L
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        samples.add(TrackSample(t, lat, lon))
        var theta = 0.0
        while (theta < 360.0) {
            val a = Math.toRadians(theta)
            val gE = airspeed * sin(a) + wE
            val gN = airspeed * cos(a) + wN
            lat += (gN * dt) / mPerDegLat
            lon += (gE * dt) / mPerDegLon
            t += (dt * 1000).toLong()
            samples.add(TrackSample(t, lat, lon))
            theta += 30.0
        }

        val est = WindEstimator.estimateWindow(samples)
        assertNotNull("a full drifting circle must yield a wind read", est)
        est!!
        assertEquals("wind speed recovered", windSpeed, est.speedMs, 0.6)
        assertEquals("airspeed recovered as the circle radius", airspeed, est.airspeedMs, 0.6)
        assertTrue(
            "wind FROM direction recovered (got ${est.directionDeg})",
            angularDiff(est.directionDeg, windFromDeg) < 8.0,
        )
        assertTrue("a clean full circle reads as high confidence", est.confidence > 0.7)
    }

    /**
     * **CLAIM K7 · Resilient (honesty over a straight glide).** A straight glide describes no
     * circle, so there is nothing to fit — the estimator must **withhold** rather than report
     * noise as wind. A glass cockpit that invents a wind vector is worse than a blank one.
     */
    @Test
    fun `resilient - a straight glide withholds the wind estimate`() {
        // Due-east cruise at 11 m/s, 2 s spacing.
        val samples = ArrayList<TrackSample>()
        var lat = 46.0; var lon = 7.0; var t = 0L
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        repeat(20) {
            samples.add(TrackSample(t, lat, lon))
            lon += (11.0 * 2.0) / mPerDegLon
            t += 2000
        }
        assertNull("no circle ⇒ no wind read", WindEstimator.estimateWindow(samples))
        assertEquals(
            WindEstimator.FlightPhase.STRAIGHT,
            WindEstimator.classifyPhase(samples),
        )
    }

    /**
     * **CLAIM K7 · Resilient (real thermal replay).** Replay a real Bir Billing thermalling
     * flight from an IGC fixture, slide a 30 s window across it, and assert that (a) the
     * circling segments produce confident reads with a *plausible paraglider airspeed*, that
     * (b) those reads *agree with each other* (a real wind is stable, not random), and that
     * (c) at least one non-circling window is honestly withheld. Real, messy GPS — not a
     * hand-fed circle.
     */
    @Test
    fun `resilient - a real thermal flight yields a stable, plausible wind`() {
        val text = javaClass.getResourceAsStream("/igc/flights/in/2025-10-11-birbilling-richard.igc")
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("Bir Billing fixture must be on the test classpath", text)
        val flight = IgcParser.parseString(text!!)
        val track = flight.fixes
            .filter { it.fixValid }
            .map { TrackSample(it.timestamp.toEpochMilli(), it.latitude, it.longitude) }
        assertTrue("fixture should have a substantial track", track.size > 200)

        val windowMs = 30_000L
        val stepMs = 10_000L
        val confident = ArrayList<Pair<Long, WindEstimator.WindEstimate>>() // (windowMidMs, est), time-ordered
        var withheldSome = false

        var startMs = track.first().timeMs
        val endMs = track.last().timeMs
        while (startMs + windowMs <= endMs) {
            val window = track.filter { it.timeMs in startMs..(startMs + windowMs) }
            val est = WindEstimator.estimateWindow(window)
            if (est == null) {
                withheldSome = true
            } else if (est.confidence > 0.4) {
                confident.add((startMs + windowMs / 2) to est)
            }
            startMs += stepMs
        }

        assertTrue("a thermalling flight should yield several confident wind reads", confident.size >= 5)
        assertTrue("non-circling stretches must be honestly withheld", withheldSome)

        // The recovered airspeed (circle radius) is a paraglider's: the vast majority of
        // reads land in 8–13 m/s and the median is right in the PG band — proof the fit
        // found a *wing circling*, not GPS noise or a cruise. A couple of transition windows
        // (entering/leaving a thermal) read high; we claim the bulk, not every outlier.
        val airspeeds = confident.map { it.second.airspeedMs }
        val inBand = airspeeds.count { it in 8.0..13.0 }
        assertTrue(
            "≥95% of confident reads carry a PG airspeed (got ${inBand}/${airspeeds.size} in band)",
            inBand >= 0.95 * airspeeds.size,
        )
        assertEquals("median recovered airspeed is a paraglider's", 10.5, airspeeds.sorted()[airspeeds.size / 2], 1.5)

        // Local stability: where there's *meaningful* wind (≥1.5 m/s — direction is
        // ill-conditioned in near-calm), adjacent-in-time circles agree. We don't claim
        // whole-flight constancy: the wind genuinely veers SW→NW over this XC, and asserting
        // it stayed put would encode a false assumption. The honest claim is no jitter.
        val meaningful = confident.filter { it.second.speedMs >= 1.5 }
        val steps = ArrayList<Double>()
        for (i in 1 until meaningful.size) {
            val (tPrev, ePrev) = meaningful[i - 1]
            val (tCur, eCur) = meaningful[i]
            if (tCur - tPrev <= 2 * stepMs) steps.add(angularDiff(ePrev.directionDeg, eCur.directionDeg))
        }
        assertTrue("there should be adjacent confident reads to compare", steps.size >= 5)
        val median = steps.sorted()[steps.size / 2]
        assertTrue(
            "adjacent circles read a steady wind (median step $median° should be small)",
            median < 20.0,
        )
    }

    /**
     * **CLAIM K7 · Correct (sensor ingest).** The XC Tracer `$XCTRC` BLE sentence parses to a
     * device-agnostic [com.ternparagliding.flight.SensorFix] with the right fields and units —
     * position, GPS altitude, ground speed (m/s), fused climb, pressure, battery, UTC time —
     * and that fix feeds straight into the wind brain. Field layout verified against XCSoar's
     * driver; the sentence below carries a genuine NMEA checksum.
     */
    @Test
    fun `correct - an XCTRC BLE sentence parses to a usable fix`() {
        val s = "\$XCTRC,2025,6,12,9,15,2,50,45.123456,6.654321,1850.5,9.80,235.0,1.45,,,,812.40,77*7E"
        val fix = XcTracerParser.parse(s)
        assertNotNull("a well-formed \$XCTRC must parse", fix)
        fix!!
        assertEquals(45.123456, fix.lat!!, 1e-6)
        assertEquals(6.654321, fix.lon!!, 1e-6)
        assertEquals(1850.5, fix.gpsAltitudeM!!, 1e-3)
        assertEquals("ground speed is m/s, stored as-is", 9.80, fix.groundSpeedMs!!, 1e-3)
        assertEquals(235.0, fix.courseDeg!!, 1e-3)
        assertEquals("fused vario", 1.45, fix.climbMs!!, 1e-3)
        assertEquals(812.40, fix.pressureHpa!!, 1e-2)
        assertEquals(77, fix.batteryPct)
        val expectedMs = LocalDateTime.of(2025, 6, 12, 9, 15, 2).toInstant(ZoneOffset.UTC).toEpochMilli() + 500
        assertEquals("UTC time incl. centiseconds", expectedMs, fix.timeMs)
        // It plugs into the wind brain unchanged.
        assertNotNull("a positioned fix yields a track sample", fix.toTrackSample())
    }

    /**
     * **CLAIM K7 · Correct (real device).** A sentence captured live off the actual XC Tracer
     * Mini II GPS over BLE (FFE0/FFE1) parses exactly — the real-world regression anchor:
     * negative longitude with 6 decimals, ground speed in m/s, empty IMU fields, pressure and
     * battery present. Checksum `*6a` matches the parser's XOR.
     */
    @Test
    fun `correct - a real XC Tracer device sentence parses`() {
        // Captured 2026-06-13 from device 8E412BC3E600, stationary indoors near Boulder, CO.
        val s = "\$XCTRC,2026,6,13,22,33,4,0,40.148471,-104.953582,1491.36,0.33,154.9,0.00,,,,853.71,24*6a"
        val fix = XcTracerParser.parse(s)
        assertNotNull("the real device sentence must parse", fix)
        fix!!
        assertEquals(40.148471, fix.lat!!, 1e-6)
        assertEquals(-104.953582, fix.lon!!, 1e-6)
        assertEquals(1491.36, fix.gpsAltitudeM!!, 1e-2)
        assertEquals(0.33, fix.groundSpeedMs!!, 1e-3)
        assertEquals(853.71, fix.pressureHpa!!, 1e-2)
        assertEquals(24, fix.batteryPct)
        assertTrue("indoors but it already had a GPS fix", fix.hasPosition)
    }

    /**
     * **CLAIM K7 · Correct (BLE reassembly).** The XC Tracer streams `$XCTRC` in ~20-byte BLE
     * notifications that split sentences mid-field. The exact chunk sequence captured from the
     * real device (longitude `-104.953582` arrives as `…,-10` + `4.953582`) must reassemble
     * into one whole sentence that the parser then reads correctly. Assembler + parser, on
     * real fragments.
     */
    @Test
    fun `correct - chunked BLE notifications reassemble into a parseable fix`() {
        // The actual notification fragments, in order, as captured from device 8E412BC3E600.
        val chunks = listOf(
            "\$XCTRC,2026,6,13", ",22,", "33,4,0,40.148471", ",-10", "4.953582,1491.36",
            ",0.3", "3,154.9,0.00,,,,", "853.", "71,24*6a\r\n",
        )
        val asm = NmeaLineAssembler()
        val lines = ArrayList<String>()
        chunks.forEach { lines += asm.append(it) }
        assertEquals("the fragments form exactly one complete line", 1, lines.size)
        val fix = XcTracerParser.parse(lines.first())
        assertNotNull("the reassembled real sentence must parse", fix)
        assertEquals(-104.953582, fix!!.lon!!, 1e-6)
        assertEquals(853.71, fix.pressureHpa!!, 1e-2)
    }

    /**
     * **CLAIM K7 · Resilient (sensor ingest).** A sentence corrupted in transit (bad checksum)
     * is rejected, not parsed into a plausible-but-wrong fix; and a vario sample that arrives
     * *before* the GPS has a fix still yields the climb with a null position (the device powers
     * up beeping before it sees satellites). Honesty over a confident lie.
     */
    @Test
    fun `resilient - corrupt XCTRC is rejected and a pre-GPS fix keeps the vario`() {
        // Same body, checksum digits flipped → must be rejected.
        val corrupt = "\$XCTRC,2025,6,12,9,15,2,50,45.123456,6.654321,1850.5,9.80,235.0,1.45,,,,812.40,77*00"
        assertNull("a bad checksum must not parse", XcTracerParser.parse(corrupt))
        // Not an XCTRC sentence at all → null (mixed streams just skip it).
        assertNull(XcTracerParser.parse("\$GPGGA,123519,4807.038,N*47"))

        // Vario before GPS: lat/lon blank, climb present.
        val noFix = "\$XCTRC,2015,1,5,16,34,33,36,,,,,,2.78,,,,964.93,98*54"
        val fix = XcTracerParser.parse(noFix)
        assertNotNull(fix)
        fix!!
        assertFalse("no GPS fix yet", fix.hasPosition)
        assertNull(fix.toTrackSample())
        assertEquals("but the vario is already live", 2.78, fix.climbMs!!, 1e-3)
        assertEquals(98, fix.batteryPct)
    }
}
