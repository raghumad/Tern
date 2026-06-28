package com.ternparagliding.flight.recording

/**
 * Pure detector for an **abnormal end** worth sealing the black box around (Epic 05 5.2): a
 * sustained, unusually fast descent — the signature of a collapse/spiral/impact approach — so the
 * record captures the event itself, not just up to a tidy landing. SOS is handled separately (an
 * explicit trigger); this is the implicit one.
 *
 * Honest scope: a phone fix stream can't see an impact directly (no accelerometer here), so this
 * is descent-rate based, intentionally conservative. It only *flags*; the recorder decides to seal.
 */
object AbnormalEndDetector {

    /** Descent (m/s, negative) beyond this, sustained, reads as abnormal for a paraglider. */
    const val RAPID_DESCENT_MS = -8.0

    /** Consecutive qualifying fixes before flagging — one fast sample is sensor noise, not a fall. */
    const val CONFIRM_FIXES = 4

    data class State(val flagged: Boolean = false, val streak: Int = 0)

    /**
     * Fold one fix's vertical speed. [CONFIRM_FIXES] consecutive fixes at or below
     * [RAPID_DESCENT_MS] flip [State.flagged] (and it latches). A null climb (no vario) or any
     * slower fix resets the streak.
     */
    fun update(prev: State, climbMs: Double?): State {
        if (prev.flagged) return prev
        val qualifies = climbMs != null && climbMs <= RAPID_DESCENT_MS
        val streak = if (qualifies) prev.streak + 1 else 0
        return State(flagged = streak >= CONFIRM_FIXES, streak = streak)
    }
}
