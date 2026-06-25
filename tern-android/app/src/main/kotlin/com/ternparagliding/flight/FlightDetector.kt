package com.ternparagliding.flight

/**
 * Pure "are we actually flying?" decision for the follow-camera. A vario streams fixes the whole
 * time it's powered — including while the pilot sits on launch rigging up — so "vario connected"
 * is *not* "in flight". Auto-zoom / track-up / recenter must only engage once airborne, otherwise
 * the map grabs control while the pilot is trying to study the launch and the air around it.
 *
 * Airborne is decided from motion, not from a button: sustained ground speed (you're moving across
 * the ground) *or* height gained above the launch datum (you're soaring up in light wind with little
 * ground speed). A short confirm streak rejects a one-fix GPS speed blip while standing still.
 *
 * Latched: once airborne we stay airborne for the session. We never try to auto-detect a landing —
 * a flicker back to "grounded" mid-flight (e.g. a slow top-landing approach) would be far more
 * jarring than leaving follow on. [State] is reset between sessions (replay restart / vario drop).
 */
object FlightDetector {
    /** Ground speed (m/s ≈ 9 km/h) that, sustained, reads as "flying", not "standing on launch". */
    const val TAKEOFF_SPEED_MS = 2.5

    /** Height above the launch datum (m) that reads as airborne even with near-zero ground speed. */
    const val TAKEOFF_HEIGHT_M = 12.0

    /** Consecutive qualifying fixes before we commit — rejects a lone GPS speed spike at rest. */
    const val CONFIRM_FIXES = 3

    /** Carried between fixes by the caller. [streak] counts consecutive qualifying fixes so far. */
    data class State(val airborne: Boolean = false, val streak: Int = 0)

    /**
     * Fold one fix into the airborne decision. Once [State.airborne] is true it stays true. A fix
     * qualifies if ground speed ≥ [TAKEOFF_SPEED_MS] or height-above-takeoff ≥ [TAKEOFF_HEIGHT_M];
     * [CONFIRM_FIXES] qualifying fixes in a row flip us airborne. Either argument may be null
     * (sensor not yet reporting it) and is treated as "doesn't qualify on its own".
     */
    fun update(prev: State, groundSpeedMs: Double?, heightAboveTakeoffM: Double?): State {
        if (prev.airborne) return prev
        val qualifies = (groundSpeedMs ?: 0.0) >= TAKEOFF_SPEED_MS ||
            (heightAboveTakeoffM ?: Double.NEGATIVE_INFINITY) >= TAKEOFF_HEIGHT_M
        val streak = if (qualifies) prev.streak + 1 else 0
        return State(airborne = streak >= CONFIRM_FIXES, streak = streak)
    }
}
