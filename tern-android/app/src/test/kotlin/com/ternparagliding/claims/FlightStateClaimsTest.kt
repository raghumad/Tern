package com.ternparagliding.claims

import com.ternparagliding.flight.CirclingWindTracker
import com.ternparagliding.flight.FlightCamera
import com.ternparagliding.flight.FlightMetrics
import com.ternparagliding.flight.FlightTrack
import com.ternparagliding.flight.IgcToXctrc
import com.ternparagliding.flight.NmeaLineAssembler
import com.ternparagliding.flight.SensorFix
import com.ternparagliding.flight.ThermalAverager
import com.ternparagliding.flight.WindEstimator
import com.ternparagliding.units.Units
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

    // ── Deck instrument math (batch 1) ───────────────────────────────────────────────

    /**
     * **CLAIM K7 · Correct (glide ratio).** L/D = ground speed / sink, withheld while climbing
     * or near level (no meaningful glide), and clamped as sink → 0.
     */
    @Test
    fun `correct - glide ratio is ground-speed over sink, withheld when not gliding`() {
        assertEquals(11.0, FlightMetrics.glideRatio(11.0, -1.0)!!, 1e-6)
        assertNull("climbing has no glide", FlightMetrics.glideRatio(11.0, 2.0))
        assertNull("near-level sink isn't a glide", FlightMetrics.glideRatio(11.0, -0.05))
        assertEquals("clamped as sink→0", FlightMetrics.MAX_GLIDE, FlightMetrics.glideRatio(50.0, -0.2)!!, 1e-6)
    }

    /**
     * **CLAIM K7 · Correct (thermal averager).** The averaged climb tracks the trailing window;
     * old samples age out. This is the needle pilots center thermals by.
     */
    @Test
    fun `correct - thermal averager smooths climb over its window`() {
        val avg = ThermalAverager(windowMs = 10_000L)
        assertEquals(2.0, avg.add(0, 2.0), 1e-9)
        assertEquals(2.0, avg.add(2_000, 2.0), 1e-9)
        assertEquals(3.0, avg.add(4_000, 5.0), 1e-9) // (2+2+5)/3
        assertEquals(5.0, avg.add(20_000, 5.0), 1e-9) // earlier samples aged out → only the last
    }

    /**
     * **CLAIM K7 · Resilient (averager on a real flight).** Replayed through the live parse path
     * (`$XCTRC` → parser → climb → averager), a real Bir Billing flight's best 25 s-averaged
     * climb lands in a plausible thermal band — the realism check on the coarse 1 Hz climb.
     */
    @Test
    fun `resilient - thermal averager finds a plausible best thermal on a real flight`() {
        val text = javaClass.getResourceAsStream("/igc/flights/in/2025-10-11-birbilling-richard.igc")!!
            .bufferedReader().use { it.readText() }
        val flight = com.ternparagliding.sim.igc.IgcParser.parseString(text)
        val parsed = IgcToXctrc.sentences(flight).mapNotNull { XcTracerParser.parse(it) }
        val avg = ThermalAverager(25_000L)
        var best = Double.NEGATIVE_INFINITY
        parsed.forEach { f -> f.climbMs?.let { best = maxOf(best, avg.add(f.timeMs, it)) } }
        assertTrue("best 25 s-avg climb is a real thermal (got $best)", best in 2.0..8.0)
    }

    /**
     * **CLAIM K7 · Correct (altitude reference).** Height-above-takeoff is the launch-datum
     * subtraction, and the readout switches MSL ↔ above-takeoff per the pilot's setting.
     */
    @Test
    fun `correct - height above takeoff and altitude reference`() {
        // Bir Billing peak: 5011 m MSL, takeoff 2332 m.
        assertEquals(2679.0, FlightMetrics.heightAboveTakeoff(5011.0, 2332.0), 1e-6)
        assertEquals(5011.0, FlightMetrics.displayAltitude(5011.0, 2332.0, FlightMetrics.AltRef.MSL), 1e-6)
        assertEquals(2679.0, FlightMetrics.displayAltitude(5011.0, 2332.0, FlightMetrics.AltRef.ABOVE_TAKEOFF), 1e-6)
    }

    /**
     * **CLAIM K7 · Correct (vario units).** Climb formats in the pilot's unit (m/s or ft/min),
     * signed, and falls back to canonical for an unknown setting.
     */
    @Test
    fun `correct - vario formats in the pilot's units`() {
        assertEquals("+1.4 m/s", Units.vario(1.4, "m/s"))
        assertEquals("-2.0 m/s", Units.vario(-2.0, "m/s"))
        assertEquals(196.850394, Units.varioValue(1.0, "ft/min"), 1e-3)
        assertEquals("+197 ft/min", Units.vario(1.0, "ft/min"))
        assertEquals("m/s", Units.varioSymbol("garbage")) // total: unknown → canonical
    }

    /**
     * **CLAIM K7 · Correct (flight replay through the parser).** A real Bir Billing flight,
     * replayed as `$XCTRC` sentences (the own-ship replay vehicle) and run through the *actual*
     * parse path, round-trips: every synthesized sentence parses, position/altitude match the
     * IGC, and the parsed `SensorFix` stream still yields a plausible circling wind. This is the
     * test vehicle the deck claims (averager, altitude-ref, glide) will ride on — they exercise
     * the live pipeline, not a shortcut.
     */
    @Test
    fun `correct - a real flight replays through the XCTRC parser end-to-end`() {
        val text = javaClass.getResourceAsStream("/igc/flights/in/2025-10-11-birbilling-richard.igc")
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull(text)
        val flight = com.ternparagliding.sim.igc.IgcParser.parseString(text!!)
        val validFixes = flight.fixes.filter { it.fixValid }
        val sentences = IgcToXctrc.sentences(flight)
        assertEquals("one sentence per valid fix", validFixes.size, sentences.size)

        val parsed = sentences.mapNotNull { XcTracerParser.parse(it) }
        assertEquals("every synthesized \$XCTRC parses (checksums valid)", sentences.size, parsed.size)

        // Position/altitude round-trip on a mid-flight fix.
        val k = validFixes.size / 2
        assertEquals(validFixes[k].latitude, parsed[k].lat!!, 1e-5)
        assertEquals(validFixes[k].longitude, parsed[k].lon!!, 1e-5)
        assertEquals(validFixes[k].gpsAltitude.toDouble(), parsed[k].gpsAltitudeM!!, 0.1)

        // End-to-end: the parsed stream still produces a plausible circling wind.
        val tracker = CirclingWindTracker()
        val winds = ArrayList<WindEstimator.WindEstimate>()
        parsed.forEach { fix -> tracker.add(fix)?.let { winds.add(it) } }
        assertTrue("replay through the parser yields wind estimates", winds.isNotEmpty())
        assertTrue(
            "and at least one carries a plausible PG airspeed",
            winds.any { it.airspeedMs in 5.0..18.0 },
        )
    }

    // ── Flight track (batch 2) ───────────────────────────────────────────────────────

    private fun fixAt(t: Long, lat: Double, lon: Double, climb: Double? = null) =
        SensorFix(timeMs = t, lat = lat, lon = lon, climbMs = climb)

    /**
     * **CLAIM K7 · Correct (flight track).** Own-position fixes accumulate into a *decimated*,
     * *ring-buffered* trail: a fix too close to the last kept one is dropped (so a 1 Hz thermal
     * doesn't stack points), an unpositioned vario-only fix is ignored, and once the cap is hit
     * the oldest point falls off the tail — the trailing-window behaviour the live deck draws.
     */
    @Test
    fun `correct - flight track decimates and ring-buffers`() {
        val lat = 46.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))

        // Decimation: a 2 m hop is dropped; a 22 m hop is kept.
        val track = FlightTrack(maxPoints = 100, minSpacingM = 10.0)
        var lon = 7.0
        assertTrue("first fix is always kept", track.add(fixAt(0, lat, lon)))
        lon += 2.0 / mPerDegLon
        assertFalse("a 2 m hop is below the spacing floor", track.add(fixAt(1_000, lat, lon)))
        lon += 20.0 / mPerDegLon
        assertTrue("now 22 m from the last kept point → kept", track.add(fixAt(2_000, lat, lon)))
        assertFalse("a vario-only fix (no GPS) is ignored", track.add(SensorFix(timeMs = 3_000, climbMs = 1.0)))
        assertEquals(2, track.points.size)

        // Ring buffer: feed 5 well-spaced fixes into a cap of 3 → only the last 3 survive.
        val rb = FlightTrack(maxPoints = 3, minSpacingM = 5.0)
        var lon2 = 7.0
        repeat(5) { i ->
            lon2 += 20.0 / mPerDegLon
            rb.add(fixAt(i.toLong() * 1_000, lat, lon2))
        }
        assertEquals(3, rb.points.size)
        assertEquals("oldest dropped at the cap", 2_000L, rb.points.first().timeMs)
        assertEquals(4_000L, rb.points.last().timeMs)
    }

    /**
     * **CLAIM K7 · Correct (track tint).** A segment's colour maps from the climb at its newer
     * endpoint — lift (green) ≥ +0.2, sink (red) ≤ −0.2, neutral in the dead-band and when climb
     * is unknown. And a logger-gap between two points breaks the trail (no phantom segment).
     */
    @Test
    fun `correct - track segment tint maps from climb`() {
        assertEquals(FlightTrack.TrackTint.LIFT, FlightTrack.trackTint(0.5))
        assertEquals("boundary is lift", FlightTrack.TrackTint.LIFT, FlightTrack.trackTint(0.2))
        assertEquals(FlightTrack.TrackTint.SINK, FlightTrack.trackTint(-0.5))
        assertEquals("boundary is sink", FlightTrack.TrackTint.SINK, FlightTrack.trackTint(-0.2))
        assertEquals("dead-band is neutral", FlightTrack.TrackTint.NEUTRAL, FlightTrack.trackTint(0.1))
        assertEquals("unknown climb → neutral, not implied lift", FlightTrack.TrackTint.NEUTRAL, FlightTrack.trackTint(null))

        val lat = 46.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        val gt = FlightTrack(minSpacingM = 1.0, gapMs = 10_000L)
        var lon = 7.0
        gt.add(fixAt(0, lat, lon, 1.0)); lon += 20.0 / mPerDegLon
        gt.add(fixAt(5_000, lat, lon, 1.5)); lon += 20.0 / mPerDegLon // 5 s gap → a segment
        gt.add(fixAt(25_000, lat, lon, -1.0)) // 20 s gap → break, no segment
        val segs = gt.segments()
        assertEquals("the 20 s gap breaks the trail", 1, segs.size)
        assertEquals("segment is tinted by its newer endpoint's climb", FlightTrack.TrackTint.LIFT, segs.first().tint)
    }

    /**
     * **CLAIM K7 · Resilient (track on a real flight).** Built straight from the live parse path
     * (`$XCTRC` → parser → fix → track), a real Bir Billing flight produces a substantial trail
     * carrying *both* lift and sink segments — a real thermal map, not a monochrome line.
     */
    @Test
    fun `resilient - flight track builds through the live parse path with lift and sink`() {
        val text = javaClass.getResourceAsStream("/igc/flights/in/2025-10-11-birbilling-richard.igc")!!
            .bufferedReader().use { it.readText() }
        val flight = com.ternparagliding.sim.igc.IgcParser.parseString(text)
        val track = FlightTrack()
        IgcToXctrc.sentences(flight).mapNotNull { XcTracerParser.parse(it) }.forEach { track.add(it) }
        val segs = track.segments()
        assertTrue("a real flight builds a substantial trail (got ${segs.size})", segs.size > 50)
        assertTrue("thermalling shows lift segments", segs.any { it.tint == FlightTrack.TrackTint.LIFT })
        assertTrue("gliding shows sink segments", segs.any { it.tint == FlightTrack.TrackTint.SINK })
    }

    // ── Map camera (batch 3) ─────────────────────────────────────────────────────────

    /**
     * **CLAIM K7 · Correct (auto-zoom).** The map zooms with what you're doing: tight while
     * circling, wider on glide, and within glide a faster ground speed pulls the look-ahead
     * wider still — all clamped to a sane range. No manual input needed for the common case.
     */
    @Test
    fun `correct - auto-zoom is tighter circling than gliding and widens with speed`() {
        val circling = FlightCamera.autoZoom(WindEstimator.FlightPhase.CIRCLING, 4.0)
        val slowGlide = FlightCamera.autoZoom(WindEstimator.FlightPhase.STRAIGHT, 8.0)
        val fastGlide = FlightCamera.autoZoom(WindEstimator.FlightPhase.STRAIGHT, 18.0)

        assertEquals("circling sits at the tight zoom", FlightCamera.CIRCLING_ZOOM, circling, 1e-9)
        assertTrue("circling is tighter (more zoomed-in) than gliding", circling > slowGlide)
        assertTrue("a faster glide looks wider than a slow one", slowGlide > fastGlide)

        // Clamped: an absurd speed can't widen past the floor, circling can't exceed the ceiling.
        val tooFast = FlightCamera.autoZoom(WindEstimator.FlightPhase.STRAIGHT, 200.0)
        assertTrue("clamped to the widest", tooFast >= FlightCamera.MIN_ZOOM)
        assertTrue("circling stays within the ceiling", circling <= FlightCamera.MAX_ZOOM)
        // Unknown phase falls back to a sensible default, not an extreme.
        val unknown = FlightCamera.autoZoom(WindEstimator.FlightPhase.UNKNOWN, 0.0)
        assertTrue(unknown in FlightCamera.MIN_ZOOM..FlightCamera.MAX_ZOOM)
    }

    /**
     * **CLAIM K7 · Correct (keep-in-view).** The framing box spans own-position plus whichever
     * of (next-WP, nearest buddy) are present, so the tactical points never drift off-screen;
     * with only own-position it degenerates to a point box (the camera then leans on auto-zoom).
     */
    @Test
    fun `correct - the framing box keeps own-position, next-WP and nearest buddy in view`() {
        val own = FlightCamera.Point(46.0, 7.0)
        val wp = FlightCamera.Point(46.2, 7.3)
        val buddy = FlightCamera.Point(45.9, 6.8)

        val box = FlightCamera.framingBox(own, wp, buddy)
        assertTrue("own in view", box.contains(own))
        assertTrue("next-WP in view", box.contains(wp))
        assertTrue("nearest buddy in view", box.contains(buddy))
        assertEquals("box spans the southern/western extreme", 45.9, box.south, 1e-9)
        assertEquals(6.8, box.west, 1e-9)
        assertEquals("and the northern/eastern extreme", 46.2, box.north, 1e-9)
        assertEquals(7.3, box.east, 1e-9)

        // No route, no buddy → a point box around the pilot.
        val solo = FlightCamera.framingBox(own)
        assertTrue(solo.contains(own))
        assertEquals(own.lat, solo.south, 1e-9)
        assertEquals(own.lat, solo.north, 1e-9)
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
     * **CLAIM K7 · Correct (live wind tracker).** The streaming wrapper around the windowed
     * estimator recovers the wind from a circle fed fix-by-fix (as the BLE link will), and a
     * vario-only sample arriving before GPS lock neither crashes it nor disturbs the estimate.
     */
    @Test
    fun `correct - the live wind tracker recovers wind from a streamed circle`() {
        val tracker = CirclingWindTracker()
        val airspeed = 10.0; val windSpeed = 4.0; val windFromDeg = 240.0
        val toRad = Math.toRadians(windFromDeg + 180.0)
        val wE = windSpeed * sin(toRad); val wN = windSpeed * cos(toRad)
        var lat = 46.0; var lon = 7.0; var t = 0L
        val mPerDegLat = 111_320.0; val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        val dt = 2.0
        var theta = 0.0
        var lastWind: WindEstimator.WindEstimate? = null
        while (theta < 540.0) { // ~1.5 turns so the window holds a full circle
            val a = Math.toRadians(theta % 360.0)
            val gE = airspeed * sin(a) + wE; val gN = airspeed * cos(a) + wN
            lat += (gN * dt) / mPerDegLat; lon += (gE * dt) / mPerDegLon; t += (dt * 1000).toLong()
            lastWind = tracker.add(SensorFix(timeMs = t, lat = lat, lon = lon, climbMs = 1.2))
            theta += 30.0
        }
        assertNotNull("a streamed circle yields a live wind", lastWind)
        assertTrue("recovered the from-direction", angularDiff(lastWind!!.directionDeg, windFromDeg) < 12.0)
        // Vario-only fix (no GPS yet) is ignored for wind and returns the last estimate.
        val after = tracker.add(SensorFix(timeMs = t + 1000, climbMs = 2.0))
        assertEquals(lastWind, after)
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
