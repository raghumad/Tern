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
}
