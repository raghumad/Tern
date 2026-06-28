package com.ternparagliding.flight

/**
 * Pure flight-deck instrument math — the computational core the HUD renders. Deps-free and
 * total so each piece is a claim (tested synthetic-exact and via IGC→`$XCTRC`→parser replay).
 */
object FlightMetrics {

    /** Sink slower than this (m/s) isn't a meaningful glide — L/D is undefined. */
    const val SINK_THRESHOLD_MS = 0.1

    /** Cap absurd L/D from a near-zero sink so the readout stays sane. */
    const val MAX_GLIDE = 100.0

    /**
     * Current glide ratio = ground speed / sink. Returns null while climbing or near level
     * (the K7 honesty rule — don't show a glide number that means nothing), and clamps the
     * blow-up as sink approaches zero.
     */
    fun glideRatio(groundSpeedMs: Double, climbMs: Double): Double? {
        if (climbMs >= -SINK_THRESHOLD_MS) return null
        return (groundSpeedMs / -climbMs).coerceAtMost(MAX_GLIDE)
    }

    /** Height above the takeoff datum (m). */
    fun heightAboveTakeoff(altM: Double, takeoffDatumM: Double): Double = altM - takeoffDatumM

    enum class AltRef { MSL, ABOVE_TAKEOFF }

    /** The altitude to display, per the pilot's reference choice. */
    fun displayAltitude(altM: Double, takeoffDatumM: Double, ref: AltRef): Double = when (ref) {
        AltRef.MSL -> altM
        AltRef.ABOVE_TAKEOFF -> altM - takeoffDatumM
    }

    /** Climb (m/s) above which the pilot is working a thermal, not gliding. */
    const val CLIMB_THRESHOLD_MS = 0.2

    /** "Near" cloudbase: within this many metres *below* it, the gap is the read that matters
     *  (you're about to reach cloud) and it pre-empts L/D and height-gain. */
    const val CLOUDBASE_NEAR_M = 300.0

    /**
     * The HUD's **contextual cell** — the single readout that changes with flight phase. The
     * tape already carries instantaneous climb + altitude; this picks the one *situational*
     * number worth the pilot's glance, in priority order:
     *  - [CloudbaseGap] when cloudbase is known and you're near (and below) it — the safety cue
     *    "you're about to be in cloud" out-ranks everything.
     *  - [HeightGain] while climbing — the thermal's payoff, height won above takeoff.
     *  - [GlideRatio] while gliding — L/D, meaningful only when actually sinking.
     *  - [None] when nothing is computable (level flight, or the inputs aren't there yet).
     */
    sealed interface HudContext {
        data class CloudbaseGap(val gapM: Double) : HudContext   // metres below cloudbase
        data class HeightGain(val gainM: Double) : HudContext    // metres above takeoff
        data class GlideRatio(val ld: Double) : HudContext       // current L/D
        object None : HudContext
    }

    fun hudContext(
        climbMs: Double?,
        groundSpeedMs: Double?,
        altitudeMslM: Double?,
        takeoffDatumM: Double?,
        cloudBaseMslM: Double?,
    ): HudContext {
        // Cloudbase proximity first — it's the one cue you can't afford to miss.
        if (cloudBaseMslM != null && altitudeMslM != null) {
            val gap = cloudBaseMslM - altitudeMslM
            if (gap in 0.0..CLOUDBASE_NEAR_M) return HudContext.CloudbaseGap(gap)
        }
        if (climbMs != null && climbMs > CLIMB_THRESHOLD_MS) {
            return if (altitudeMslM != null && takeoffDatumM != null) {
                HudContext.HeightGain(altitudeMslM - takeoffDatumM)
            } else {
                HudContext.None
            }
        }
        if (climbMs != null && groundSpeedMs != null) {
            glideRatio(groundSpeedMs, climbMs)?.let { return HudContext.GlideRatio(it) }
        }
        return HudContext.None
    }
}
