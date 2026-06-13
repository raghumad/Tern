package com.ternparagliding.flight

/**
 * One normalized sample from an external sensor (XC Tracer today; any GPS/vario adapter
 * later). This is the **device-agnostic stream** the brains read: the wind estimator, the
 * vario, the deck. A per-device parser ([XcTracerParser]) is the only thing that knows the
 * wire format — everything above it speaks `SensorFix`.
 *
 * Every channel is nullable because real devices send partial fixes: an XC Tracer streams a
 * vario the instant it powers on, but lat/lon stay null until it gets a GPS fix. Consumers
 * gate on what's present (the K7 honesty principle) rather than assuming a full fix.
 */
data class SensorFix(
    /** UTC epoch milliseconds. */
    val timeMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    /** GPS altitude, metres. */
    val gpsAltitudeM: Double? = null,
    /** Ground speed, m/s. */
    val groundSpeedMs: Double? = null,
    /** Course over ground, degrees (0 = N). */
    val courseDeg: Double? = null,
    /** Fused vertical speed (the vario), m/s, + up. */
    val climbMs: Double? = null,
    /** Raw barometric pressure, hPa. */
    val pressureHpa: Double? = null,
    /** Battery charge, percent. */
    val batteryPct: Int? = null,
) {
    val hasPosition: Boolean get() = lat != null && lon != null

    /** A position fix for the wind estimator, or null if this sample has no GPS yet. */
    fun toTrackSample(): WindEstimator.TrackSample? =
        if (lat != null && lon != null) WindEstimator.TrackSample(timeMs, lat, lon) else null
}
