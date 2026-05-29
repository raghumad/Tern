package com.ternparagliding.overlay.priority

/**
 * One thing that wants to appear on the map. Every overlay type
 * implements this interface; the [OverlayPrioritizer] sees them all
 * as the same thing and picks the best N.
 *
 * The default [score] is `safetyWeight * distanceDecay`. Override it
 * for type-specific urgency (gust fronts, NOTAMs, etc.).
 */
interface OverlayCandidate {
    val kind: OverlayKind
    val position: Position

    fun score(pilotPosition: Position): Double {
        val distanceKm = position.distanceKm(pilotPosition)
        return kind.safetyWeight * distanceDecay(distanceKm)
    }
}

/** Nearby = high, far = low, never zero. */
fun distanceDecay(distanceKm: Double): Double = 1.0 / (1.0 + distanceKm)
