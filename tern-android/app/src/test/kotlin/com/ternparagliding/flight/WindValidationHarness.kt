package com.ternparagliding.flight

import com.ternparagliding.flight.WindEstimator.GroundVel
import com.ternparagliding.flight.WindEstimator.TrackSample
import com.ternparagliding.sim.igc.IgcParser
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Thorough, opt-in validation of [WindEstimator] over a corpus of real flights — for
 * trusting the algorithm, not regression-gating it (the claim test [
 * com.ternparagliding.claims.FlightStateClaimsTest] does the gating). Skipped in normal
 * runs; enabled by pointing it at IGC files:
 *
 * ```
 * # validate against your own tracks (recursively scanned for *.igc):
 * TERN_IGC_DIR=/path/to/your/igc ./gradlew :app:testDebugUnitTest \
 *     --tests "com.ternparagliding.flight.WindValidationHarness"
 *
 * # or just re-run the bundled fixtures:
 * ./gradlew :app:testDebugUnitTest -Dtern.igc.validate=true \
 *     --tests "com.ternparagliding.flight.WindValidationHarness"
 * ```
 *
 * **Why this is real validation without ground truth.** We have no measured wind for an
 * arbitrary track, so we triangulate three methods that fail in *different* ways and check
 * they agree:
 *  1. **circle-fit** (production) — least-squares circle in velocity space; centre = wind.
 *  2. **vector-average** — the mean ground-velocity over a turn (air velocity integrates to
 *     zero, so the residual is the wind). Independent of any fit.
 *  3. **min/max ground speed** — wind = (Vmax − Vmin)/2, blowing toward the track at Vmax.
 *     Uses only speeds + one direction; structurally unlike the other two.
 *
 * Plus two model-free sanity checks the data must satisfy on its own: the recovered airspeed
 * (circle radius) must land in a paraglider's band and stay *stable within a flight* (we
 * never feed it), and adjacent circles must read a steady wind (low jitter).
 */
class WindValidationHarness {

    private val windowMs = 30_000L
    private val stepMs = 10_000L
    private val bundled = listOf(
        "/igc/flights/fr/2026-04-25-aravis-team-cbe.igc",
        "/igc/flights/fr/2026-04-25-aravis-team-cor.igc",
        "/igc/flights/fr/2026-04-25-aravis-team-lma.igc",
        "/igc/flights/fr/2026-04-25-aravis-team-tonio24.igc",
        "/igc/flights/us/2026-05-29-edithsgap-josh.igc",
        "/igc/flights/us/2026-05-29-edithsgap-stephen.igc",
        "/igc/flights/in/2025-10-11-birbilling-richard.igc",
        "/igc/flights/in/2025-10-11-birbilling-ariel.igc",
        "/igc/flights/in/2025-10-11-birbilling-barney.igc",
    )

    private data class Sample(val timeMs: Long, val circleDir: Double, val circleSpd: Double,
                              val vecDir: Double, val minMaxDir: Double, val airspeed: Double)

    @Test
    fun `VALIDATE wind estimator over an IGC corpus`() {
        val dir = System.getProperty("tern.igc.dir") ?: System.getenv("TERN_IGC_DIR")
        val validateFlag = System.getProperty("tern.igc.validate") == "true" ||
            System.getenv("TERN_IGC_VALIDATE") == "true"
        assumeTrue(
            "opt-in: set TERN_IGC_DIR=<dir> or -Dtern.igc.validate=true to run",
            dir != null || validateFlag,
        )

        val tracks: List<Pair<String, String>> = if (dir != null) {
            val root = File(dir)
            require(root.exists()) { "TERN_IGC_DIR does not exist: $dir" }
            root.walkTopDown().filter { it.isFile && it.extension.equals("igc", true) }
                .sortedBy { it.path }
                .map { it.name to it.readText() }
                .toList()
        } else {
            bundled.mapNotNull { p ->
                javaClass.getResourceAsStream(p)?.bufferedReader()?.use { it.readText() }?.let { p.substringAfterLast('/') to it }
            }
        }
        require(tracks.isNotEmpty()) { "no IGC files found" }

        println("\n================ WIND ESTIMATOR VALIDATION (${tracks.size} flights) ================")
        println("source: ${dir ?: "bundled fixtures"}\n")

        // Aggregate across the whole corpus.
        val allCircleVsVec = ArrayList<Double>()
        val allCircleVsMinMax = ArrayList<Double>()
        val allAirspeedMed = ArrayList<Double>()
        val allJitter = ArrayList<Double>()
        var flightsWithCircles = 0

        for ((name, text) in tracks) {
            val flight = try { IgcParser.parseString(text) } catch (e: Exception) {
                println("%-40s  PARSE ERROR: ${e.message}".format(name)); continue
            }
            val track = flight.fixes.filter { it.fixValid }
                .map { TrackSample(it.timestamp.toEpochMilli(), it.latitude, it.longitude) }
            if (track.size < 30) { println("%-40s  too short (${track.size} fixes)".format(name)); continue }

            val t0 = track.first().timeMs
            val tEnd = track.last().timeMs
            var windows = 0; var withheld = 0
            val samples = ArrayList<Sample>()

            var startMs = t0
            while (startMs + windowMs <= tEnd) {
                windows++
                val w = track.filter { it.timeMs in startMs..(startMs + windowMs) }
                val est = WindEstimator.estimateWindow(w)
                if (est == null) { withheld++; startMs += stepMs; continue }
                if (est.confidence > 0.4) {
                    val vels = WindEstimator.groundVelocities(w)
                    val vec = vectorAverageWind(vels)
                    val mm = minMaxWind(vels)
                    if (vec != null && mm != null) {
                        samples.add(Sample(startMs + windowMs / 2, est.directionDeg, est.speedMs,
                            vec.first, mm.first, est.airspeedMs))
                    }
                }
                startMs += stepMs
            }

            val meaningful = samples.filter { it.circleSpd >= 1.5 } // direction is ill-conditioned in near-calm
            val airspeeds = samples.map { it.airspeed }.sorted()
            val durMin = (tEnd - t0) / 60_000

            print("%-40s %3dmin %5df  win=%-4d withheld=%2d%%  circles=%-4d".format(
                name, durMin, track.size, windows, if (windows > 0) withheld * 100 / windows else 0, samples.size))

            if (airspeeds.isEmpty() || meaningful.size < 3) { println("\n   → too few circling windows to validate\n"); continue }
            flightsWithCircles++

            val asMed = airspeeds[airspeeds.size / 2]
            allAirspeedMed.add(asMed)

            // Cross-method agreement (only where there's meaningful wind to have a direction).
            val cvVec = meaningful.map { angularDiff(it.circleDir, it.vecDir) }.sorted()
            val cvMM = meaningful.map { angularDiff(it.circleDir, it.minMaxDir) }.sorted()
            allCircleVsVec.addAll(cvVec); allCircleVsMinMax.addAll(cvMM)

            // Jitter between time-adjacent circles.
            val steps = ArrayList<Double>()
            for (i in 1 until meaningful.size)
                if (meaningful[i].timeMs - meaningful[i - 1].timeMs <= 2 * stepMs)
                    steps.add(angularDiff(meaningful[i - 1].circleDir, meaningful[i].circleDir))
            val jit = if (steps.isEmpty()) Double.NaN else steps.sorted()[steps.size / 2]
            if (!jit.isNaN()) allJitter.add(jit)

            // Wind by flight-third, to show real veering is tracked (not flattened).
            val thirds = (0..2).mapNotNull { idx ->
                val g = meaningful.filter { ((it.timeMs - t0).toDouble() / (tEnd - t0) * 3).toInt().coerceIn(0, 2) == idx }
                if (g.isEmpty()) null else "T$idx %.0f°/%.1f".format(circularMean(g.map { it.circleDir }), g.map { it.circleSpd }.average())
            }

            println()
            println("   airspeed med=%.1f (p05=%.1f p95=%.1f) m/s   adj-jitter=%s".format(
                asMed, airspeeds[airspeeds.size * 5 / 100], airspeeds[airspeeds.size * 95 / 100],
                if (jit.isNaN()) "n/a" else "%.0f°".format(jit)))
            println("   agreement  circle-vs-vecavg med=%.0f° p90=%.0f°   circle-vs-minmax med=%.0f° p90=%.0f°".format(
                cvVec[cvVec.size / 2], cvVec[(cvVec.size * 9 / 10).coerceAtMost(cvVec.size - 1)],
                cvMM[cvMM.size / 2], cvMM[(cvMM.size * 9 / 10).coerceAtMost(cvMM.size - 1)]))
            println("   wind/third ${thirds.joinToString("  ")}")
            println()
        }

        println("================ CORPUS SUMMARY ================")
        println("flights with circling: $flightsWithCircles / ${tracks.size}")
        if (allAirspeedMed.isNotEmpty())
            println("per-flight airspeed medians: %.1f–%.1f m/s (all should be a PG's, ~8–14)".format(
                allAirspeedMed.min(), allAirspeedMed.max()))
        if (allCircleVsVec.isNotEmpty()) {
            allCircleVsVec.sort(); allCircleVsMinMax.sort()
            println("circle-vs-vector-average:  median=%.1f°  p90=%.1f°  (independent fit-free check)".format(
                allCircleVsVec[allCircleVsVec.size / 2], allCircleVsVec[allCircleVsVec.size * 9 / 10]))
            println("circle-vs-min/max-speed:   median=%.1f°  p90=%.1f°  (structurally different method)".format(
                allCircleVsMinMax[allCircleVsMinMax.size / 2], allCircleVsMinMax[allCircleVsMinMax.size * 9 / 10]))
        }
        if (allJitter.isNotEmpty()) { allJitter.sort(); println("adjacent-circle jitter: median %.0f° across flights".format(allJitter[allJitter.size / 2])) }
        println("================================================\n")
    }

    // ── independent cross-check estimators (validation only) ──────────────────────────

    /** Wind = mean ground-velocity over the turn (air velocity averages to zero). Returns (fromDeg, speed). */
    private fun vectorAverageWind(vels: List<GroundVel>): Pair<Double, Double>? {
        if (vels.size < WindEstimator.MIN_SAMPLES) return null
        val mE = vels.map { it.east }.average()
        val mN = vels.map { it.north }.average()
        val from = (Math.toDegrees(atan2(mE, mN)) + 180.0 + 360.0) % 360.0
        return from to hypot(mE, mN)
    }

    /** Wind = (Vmax − Vmin)/2, blowing toward the track at Vmax. Robust avg of the extreme 20%. */
    private fun minMaxWind(vels: List<GroundVel>): Pair<Double, Double>? {
        if (vels.size < WindEstimator.MIN_SAMPLES) return null
        val sorted = vels.sortedBy { it.speedMs }
        val k = (vels.size * 20 / 100).coerceAtLeast(1)
        val vmin = sorted.take(k).map { it.speedMs }.average()
        val vmax = sorted.takeLast(k).map { it.speedMs }.average()
        val downwind = circularMean(sorted.takeLast(k).map { it.trackDeg })
        return (downwind + 180.0) % 360.0 to (vmax - vmin) / 2.0
    }

    private fun circularMean(dirs: List<Double>): Double {
        var s = 0.0; var c = 0.0
        dirs.forEach { s += sin(Math.toRadians(it)); c += cos(Math.toRadians(it)) }
        return (Math.toDegrees(atan2(s, c)) + 360.0) % 360.0
    }

    private fun angularDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }
}
