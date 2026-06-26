package com.ternparagliding.flight.recording

import kotlin.math.abs

/**
 * Pure "have we landed?" decision used **only to close a recording** — the inverse of
 * [com.ternparagliding.flight.FlightDetector] (which detects takeoff and deliberately never lands,
 * so the follow-cam can't flicker off mid-flight). Sealing the black box is a different question:
 * we want to stop the log once the pilot is genuinely down.
 *
 * Landed = on the ground and stopped: ground speed below [STILL_SPEED_MS] and altitude staying
 * within [STILL_ALT_BAND_M] of where it settled, sustained for [CONFIRM_MS] (≈5 min — long enough
 * that a slow top-landing approach or a low slow beat-back doesn't seal early). A manual "end
 * flight" is always available as the fallback; this just automates the common case.
 */
object LandingDetector {

    /** Ground speed (m/s) below which the pilot reads as "not moving across the ground". */
    const val STILL_SPEED_MS = 1.0

    /** Altitude (m) may wander this much around the settle point and still count as "still". */
    const val STILL_ALT_BAND_M = 3.0

    /** Sustained still time before we call it landed. */
    const val CONFIRM_MS = 5 * 60 * 1000L

    /**
     * @param landed latched once true.
     * @param stillSinceMs when the current still streak began (null = not currently still).
     * @param settleAltM the altitude anchor for the drift band (set when the streak began).
     */
    data class State(
        val landed: Boolean = false,
        val stillSinceMs: Long? = null,
        val settleAltM: Double? = null,
    )

    /**
     * Fold one fix into the landed decision. A fix is "still" only if we have a ground-speed
     * reading below [STILL_SPEED_MS] *and* (if altitude is known) it hasn't drifted past
     * [STILL_ALT_BAND_M] from the settle anchor. A null ground speed can't confirm stillness, so
     * it breaks the streak (conservative — better a late seal than a false one; manual end exists).
     */
    fun update(prev: State, timeMs: Long, groundSpeedMs: Double?, altM: Double?): State {
        if (prev.landed) return prev

        // Moving (or no speed reading to confirm stillness) breaks any streak.
        if (groundSpeedMs == null || groundSpeedMs >= STILL_SPEED_MS) {
            return State(landed = false, stillSinceMs = null, settleAltM = altM)
        }

        // Low speed: starting a fresh streak anchors the clock and altitude here.
        if (prev.stillSinceMs == null) {
            return State(landed = false, stillSinceMs = timeMs, settleAltM = altM)
        }

        // Ongoing streak: altitude wandering off the anchor (a slope-soaring beat) restarts it.
        val drifted = prev.settleAltM != null && altM != null &&
            abs(altM - prev.settleAltM) > STILL_ALT_BAND_M
        if (drifted) {
            return State(landed = false, stillSinceMs = timeMs, settleAltM = altM)
        }

        val landed = (timeMs - prev.stillSinceMs) >= CONFIRM_MS
        return State(landed = landed, stillSinceMs = prev.stillSinceMs, settleAltM = prev.settleAltM)
    }
}
