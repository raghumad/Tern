package com.ternparagliding.claims

import com.ternparagliding.flight.WindEstimator
import com.ternparagliding.flight.WindEstimator.TrackSample
import com.ternparagliding.sim.igc.IgcParser
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
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
}
