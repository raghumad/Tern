package com.ternparagliding.flight

import kotlin.math.cos
import kotlin.math.hypot

/**
 * The pilot's recent path, ready to render as a **climb-tinted breadcrumb** — the map's own
 * lift map. Pure (no Android, no MapLibre) so the decimation, eviction, and colour rules are
 * unit-testable; the `[smoke]` layer that draws [segments] onto the map is the only Android part.
 *
 * Three rules, each a K7 claim:
 *  - **Trailing window** — a ring buffer capped at [maxPoints]; the oldest fix drops off the
 *    tail at the cap so a five-hour flight can't grow unbounded. This is "the lift around me
 *    *now*", not a full IGC trace.
 *  - **Decimation** — a fix is kept only if it's at least [minSpacingM] from the last kept one,
 *    so a thermal at 1 Hz doesn't stack hundreds of points on one spot.
 *  - **Gap honesty** — consecutive kept points more than [gapMs] apart don't form a segment;
 *    the trail breaks rather than drawing a phantom straight line across a logger dropout (the
 *    same rule [IgcToXctrc] applies to synthesized velocity/climb).
 *
 * Each segment is tinted by the climb at its **newer** endpoint — colour follows where you were
 * going, not a trailing average — via [trackTint].
 */
class FlightTrack(
    private val maxPoints: Int = DEFAULT_MAX_POINTS,
    private val minSpacingM: Double = MIN_SPACING_M,
    private val gapMs: Long = GAP_MS,
) {

    /** One kept point on the trail. Climb is the fused vario at that fix (null pre-lock). */
    data class TrackPoint(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
        val climbMs: Double?,
    )

    /** A drawable segment between two kept points, coloured by the newer point's climb. */
    data class Segment(val from: TrackPoint, val to: TrackPoint, val tint: TrackTint)

    private val buf = ArrayDeque<TrackPoint>()

    /** The kept points, oldest → newest. */
    val points: List<TrackPoint> get() = buf.toList()

    /**
     * Offer a fix to the trail. Unpositioned fixes (vario before GPS lock) are ignored; a
     * positioned fix is kept only if it's moved far enough from the last kept point. Returns
     * true if it was added.
     */
    fun add(fix: SensorFix): Boolean {
        if (!fix.hasPosition) return false
        val last = buf.lastOrNull()
        if (last != null && distanceM(last.lat, last.lon, fix.lat!!, fix.lon!!) < minSpacingM) {
            return false
        }
        buf.addLast(TrackPoint(fix.timeMs, fix.lat!!, fix.lon!!, fix.climbMs))
        while (buf.size > maxPoints) buf.removeFirst()
        return true
    }

    /**
     * The drawable segments, in order. A segment is emitted for each adjacent pair of kept
     * points whose time gap is within [gapMs]; a larger gap breaks the trail (no segment).
     */
    fun segments(): List<Segment> {
        val pts = buf.toList()
        val out = ArrayList<Segment>(pts.size)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (b.timeMs - a.timeMs > gapMs) continue // logger gap → break the line
            out.add(Segment(a, b, trackTint(b.climbMs)))
        }
        return out
    }

    fun reset() = buf.clear()

    companion object {
        /** Trailing-window cap. At ~[MIN_SPACING_M] spacing this is ~10 km of recent path. */
        const val DEFAULT_MAX_POINTS = 2000
        const val MIN_SPACING_M = 5.0
        const val GAP_MS = 10_000L

        /** Climb at/above this (m/s) tints a segment as lift. */
        const val LIFT_THRESHOLD_MS = 0.2

        /** Climb at/below this (m/s) tints a segment as sink. */
        const val SINK_THRESHOLD_MS = -0.2

        private const val EARTH_R_M = 6_371_000.0

        /**
         * The thermal-map colour rule: lift (green) ≥ +0.2, sink (red) ≤ −0.2, neutral grey in
         * the vario dead-band between (and when climb is unknown — don't imply lift you can't see).
         */
        fun trackTint(climbMs: Double?): TrackTint = when {
            climbMs == null -> TrackTint.NEUTRAL
            climbMs >= LIFT_THRESHOLD_MS -> TrackTint.LIFT
            climbMs <= SINK_THRESHOLD_MS -> TrackTint.SINK
            else -> TrackTint.NEUTRAL
        }

        /** Equirectangular metres — exact enough for the few-metre decimation test. */
        private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val latMean = Math.toRadians((lat1 + lat2) / 2)
            val dE = Math.toRadians(lon2 - lon1) * cos(latMean) * EARTH_R_M
            val dN = Math.toRadians(lat2 - lat1) * EARTH_R_M
            return hypot(dE, dN)
        }
    }

    /** Segment colour classes; the map layer maps these to actual ARGB. */
    enum class TrackTint { LIFT, NEUTRAL, SINK }
}
