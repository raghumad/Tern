package com.ternparagliding.flight

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Wind from drift, while circling — the first flight-deck "brain" (claim **K7**).
 *
 * A paraglider has no airspeed sensor, so the airplane trick of "groundspeed − airspeed"
 * is off the table. But a glider circling at roughly constant airspeed gives the wind away
 * for free: as it turns through 360°, its air-relative velocity vector sweeps every
 * direction and averages to zero, so the *only* thing left in the averaged ground velocity
 * is the wind. Geometrically, the measured ground-velocity vectors trace a **circle in
 * velocity space** whose **centre is the wind vector** and whose **radius is the airspeed**.
 * Fit that circle and you have both — the technique XCSoar/LK8000 use, and exactly the
 * "GPS-drift estimate while circling" the FlightState design names.
 *
 * This is pure and deps-free on purpose: it runs off any `(time, lat, lon)` stream — phone
 * GPS today, an XC Tracer's higher-rate fix tomorrow, or an IGC replay under test. The
 * XC Tracer's gyro will later *sharpen* circling detection, but it is not required here.
 *
 * Honesty over bravado (the [Measured]/K7 principle): a straight glide has no circle to
 * fit, so the estimator **withholds** (returns null) rather than reporting noise. A read is
 * only returned when the samples genuinely cover enough of a turn to trust the fit.
 */
object WindEstimator {

    /** Earth radius (m) for the local equirectangular projection used to get velocities. */
    private const val EARTH_R_M = 6_371_000.0

    /** Fewer samples than this can't pin a circle. */
    const val MIN_SAMPLES = 8

    /** Angular coverage (deg) of the swept circle below which we don't trust the fit. */
    const val MIN_COVERAGE_DEG = 270.0

    /** A paraglider's true airspeed lives in this band (m/s ≈ 14–72 km/h); outside it the
     *  "circle" is some other motion (cruise, GPS noise) and the fit is rejected. */
    private val PLAUSIBLE_AIRSPEED_MS = 4.0..20.0

    /** One position fix in the track stream. */
    data class TrackSample(val timeMs: Long, val lat: Double, val lon: Double)

    /** A wind read with its provenance, so consumers can gate on trust (K7 · honesty). */
    data class WindEstimate(
        /** Wind speed (m/s). */
        val speedMs: Double,
        /** Meteorological direction the wind blows **from** (deg, 0 = N, 90 = E). */
        val directionDeg: Double,
        /** Fitted circle radius — the glider's true airspeed estimate (m/s). */
        val airspeedMs: Double,
        /** 0..1; built from angular coverage and fit residual. */
        val confidence: Double,
        /** How many velocity samples the fit used. */
        val sampleCount: Int,
        /** How much of the 360° circle the samples actually swept (deg). */
        val coverageDeg: Double,
    )

    enum class FlightPhase { UNKNOWN, STRAIGHT, CIRCLING }

    /**
     * Estimate the wind from one window of consecutive track samples (typically the last
     * ~20–40 s). Returns null when the window doesn't describe a trustworthy circle — too
     * few samples, too little of the turn swept, or an implausible airspeed.
     */
    fun estimateWindow(samples: List<TrackSample>): WindEstimate? {
        val vels = velocities(samples)
        if (vels.size < MIN_SAMPLES) return null

        val coverage = angularCoverageDeg(vels)
        if (coverage < MIN_COVERAGE_DEG) return null

        val fit = fitCircle(vels) ?: return null
        val (cEast, cNorth, radius) = fit
        if (radius !in PLAUSIBLE_AIRSPEED_MS) return null

        val windSpeed = hypot(cEast, cNorth)
        if (windSpeed > 30.0) return null // hurricane-grade: it's a bad fit, not wind

        // Wind vector (cEast, cNorth) is the velocity the air imparts — i.e. the direction
        // the wind blows *toward*. Meteorology names the *from* direction, so add 180°.
        val toDeg = normalizeDeg(Math.toDegrees(atan2(cEast, cNorth)))
        val fromDeg = normalizeDeg(toDeg + 180.0)

        val normResidual = fitResidual(vels, cEast, cNorth, radius) / radius
        val covFactor = ((coverage - 180.0) / (340.0 - 180.0)).coerceIn(0.0, 1.0)
        val fitFactor = (1.0 - normResidual / 0.25).coerceIn(0.0, 1.0)
        val confidence = covFactor * fitFactor

        return WindEstimate(
            speedMs = windSpeed,
            directionDeg = fromDeg,
            airspeedMs = radius,
            confidence = confidence,
            sampleCount = vels.size,
            coverageDeg = coverage,
        )
    }

    /**
     * Classify what the glider is doing over a window — straight vs circling — from how much
     * of a turn the track swept. Cheap proxy for the gyro we'll get from the XC Tracer.
     */
    fun classifyPhase(samples: List<TrackSample>): FlightPhase {
        val vels = velocities(samples)
        if (vels.size < MIN_SAMPLES) return FlightPhase.UNKNOWN
        return if (angularCoverageDeg(vels) >= MIN_COVERAGE_DEG) FlightPhase.CIRCLING
        else FlightPhase.STRAIGHT
    }

    // ── internals ──────────────────────────────────────────────────────────────────

    /** Ground-velocity vectors (east, north m/s) between consecutive fixes. */
    private fun velocities(samples: List<TrackSample>): List<Vel> {
        if (samples.size < 2) return emptyList()
        val out = ArrayList<Vel>(samples.size - 1)
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dtS = (b.timeMs - a.timeMs) / 1000.0
            if (dtS <= 0.0) continue
            val latMean = Math.toRadians((a.lat + b.lat) / 2.0)
            val dEast = Math.toRadians(b.lon - a.lon) * cos(latMean) * EARTH_R_M
            val dNorth = Math.toRadians(b.lat - a.lat) * EARTH_R_M
            out.add(Vel(dEast / dtS, dNorth / dtS))
        }
        return out
    }

    private data class Vel(val e: Double, val n: Double)

    /** Largest swept arc (360° − biggest gap) over the velocity directions. */
    private fun angularCoverageDeg(vels: List<Vel>): Double {
        if (vels.size < 2) return 0.0
        val angles = vels
            .map { normalizeDeg(Math.toDegrees(atan2(it.e, it.n))) }
            .sorted()
        var maxGap = 0.0
        for (i in 1 until angles.size) maxGap = maxOf(maxGap, angles[i] - angles[i - 1])
        // wrap-around gap between last and first
        maxGap = maxOf(maxGap, 360.0 - (angles.last() - angles.first()))
        return 360.0 - maxGap
    }

    /** Algebraic (Kåsa) least-squares circle fit. Returns (centreEast, centreNorth, radius). */
    private fun fitCircle(vels: List<Vel>): Triple<Double, Double, Double>? {
        // Fit x²+y² = a·x + b·y + c ; centre = (a/2, b/2), r² = c + a²/4 + b²/4.
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        val n = vels.size.toDouble()
        for (v in vels) {
            val x = v.e; val y = v.n; val z = x * x + y * y
            sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
            sxz += x * z; syz += y * z; sz += z
        }
        val m = arrayOf(
            doubleArrayOf(sxx, sxy, sx),
            doubleArrayOf(sxy, syy, sy),
            doubleArrayOf(sx, sy, n),
        )
        val rhs = doubleArrayOf(sxz, syz, sz)
        val sol = solve3(m, rhs) ?: return null
        val (a, b, c) = Triple(sol[0], sol[1], sol[2])
        val cE = a / 2.0; val cN = b / 2.0
        val r2 = c + cE * cE + cN * cN
        if (r2 <= 0.0) return null
        return Triple(cE, cN, sqrt(r2))
    }

    /** RMS distance of the velocity points from the fitted circle. */
    private fun fitResidual(vels: List<Vel>, cE: Double, cN: Double, r: Double): Double {
        var acc = 0.0
        for (v in vels) {
            val d = hypot(v.e - cE, v.n - cN) - r
            acc += d * d
        }
        return sqrt(acc / vels.size)
    }

    /** Solve a 3×3 system by Gaussian elimination with partial pivoting; null if singular. */
    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { i -> doubleArrayOf(a[i][0], a[i][1], a[i][2], b[i]) }
        for (col in 0 until 3) {
            var pivot = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (r in 0 until 3) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (k in col until 4) m[r][k] -= f * m[col][k]
            }
        }
        return doubleArrayOf(m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2])
    }

    private fun normalizeDeg(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }
}
