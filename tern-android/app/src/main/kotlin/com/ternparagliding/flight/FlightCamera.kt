package com.ternparagliding.flight

import kotlin.math.max
import kotlin.math.min

/**
 * Pure camera policy for the in-flight map — *what the map should show*, decided from what the
 * pilot is doing. No MapLibre here; the `[smoke]` layer turns these numbers into a camera move.
 *
 *  - [autoZoom] — phase-and-speed-adaptive zoom: tight while circling (your turn + immediate
 *    terrain), wide on glide (track ahead, next WP, airspace), and within glide a faster ground
 *    speed pulls the look-ahead wider still. Manual pinch is expected to override this upstream.
 *  - [framingBox] — the keep-in-view box: own-position always, plus the next waypoint and the
 *    nearest buddy when present, so the things that matter never drift off-screen.
 */
object FlightCamera {

    /** Widest / tightest zoom we'll auto-select (MapLibre zoom: higher = closer). */
    const val MIN_ZOOM = 10.5
    const val MAX_ZOOM = 15.0

    /**
     * Circling sits a touch tighter than gliding — but only by ~1–1.5 levels, not a jarring
     * jump. The smoothing in [ease] does the rest, so the camera glides between phases instead
     * of snapping (which reads as nauseating, especially under sped-up replay).
     */
    const val CIRCLING_ZOOM = 14.0

    /** Glide zoom interpolates across this ground-speed band (m/s ≈ 29–65 km/h). */
    const val GLIDE_SLOW_MS = 8.0
    const val GLIDE_FAST_MS = 18.0
    const val GLIDE_SLOW_ZOOM = 13.0
    const val GLIDE_FAST_ZOOM = 11.5

    /**
     * Smoothing factors for the follow-camera. Low = calm: each fix nudges the camera a fraction
     * of the way to its target, so a transient phase flip or a noisy heading doesn't whip the
     * view around. These make the motion frame-rate-independent in feel — gentle whether fixes
     * arrive at 1 Hz (real flight) or 12 Hz (replay).
     */
    const val ZOOM_EASE = 0.12
    const val BEARING_EASE = 0.08

    /**
     * Zoom for the current phase and ground speed. Circling → [CIRCLING_ZOOM]; gliding →
     * interpolated from [GLIDE_SLOW_ZOOM] (slow) to [GLIDE_FAST_ZOOM] (fast); unknown → the
     * slow-glide default. Always clamped to [[MIN_ZOOM], [MAX_ZOOM]].
     */
    fun autoZoom(phase: WindEstimator.FlightPhase, groundSpeedMs: Double): Double {
        val z = when (phase) {
            WindEstimator.FlightPhase.CIRCLING -> CIRCLING_ZOOM
            WindEstimator.FlightPhase.STRAIGHT -> {
                val span = GLIDE_FAST_MS - GLIDE_SLOW_MS
                val t = ((groundSpeedMs - GLIDE_SLOW_MS) / span).coerceIn(0.0, 1.0)
                GLIDE_SLOW_ZOOM + t * (GLIDE_FAST_ZOOM - GLIDE_SLOW_ZOOM)
            }
            WindEstimator.FlightPhase.UNKNOWN -> GLIDE_SLOW_ZOOM
        }
        return z.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /**
     * Exponential ease toward [target] by fraction [alpha] (a low-pass). [prev] = NaN (no prior
     * value) snaps straight to the target. Used to smooth the zoom so phase flips don't snap.
     */
    fun ease(prev: Double, target: Double, alpha: Double): Double =
        if (prev.isNaN()) target else prev + alpha * (target - prev)

    /**
     * Ease a *bearing* toward [target] the short way around the compass (so 350° → 10° turns +20°,
     * not −340°). [prev] = NaN snaps to the normalized target. Result is in [0, 360).
     */
    fun easeBearing(prev: Double, target: Double, alpha: Double): Double {
        if (target.isNaN()) return prev
        val t = ((target % 360.0) + 360.0) % 360.0
        if (prev.isNaN()) return t
        val delta = ((t - prev + 540.0) % 360.0) - 180.0 // shortest signed arc, −180..180
        return ((prev + alpha * delta) % 360.0 + 360.0) % 360.0
    }

    data class Point(val lat: Double, val lon: Double)

    /** A geographic bounding box (inclusive). */
    data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
        fun contains(p: Point): Boolean =
            p.lat in south..north && p.lon in west..east
    }

    /**
     * The smallest box containing own-position and any of (next waypoint, nearest buddy) that
     * are present. With only own-position it's a degenerate point box — the camera falls back on
     * [autoZoom] for framing in that case.
     */
    fun framingBox(own: Point, nextWp: Point? = null, nearestBuddy: Point? = null): GeoBox {
        val pts = listOfNotNull(own, nextWp, nearestBuddy)
        var s = own.lat; var n = own.lat; var w = own.lon; var e = own.lon
        for (p in pts) {
            s = min(s, p.lat); n = max(n, p.lat)
            w = min(w, p.lon); e = max(e, p.lon)
        }
        return GeoBox(south = s, west = w, north = n, east = e)
    }
}
