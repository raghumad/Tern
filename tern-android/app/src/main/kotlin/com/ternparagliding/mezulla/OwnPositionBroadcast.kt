package com.ternparagliding.mezulla

import com.ternparagliding.flight.SensorFix
import com.ternparagliding.mezulla.connection.PeerPosition
import kotlin.math.roundToInt

/**
 * **What we send over Mezulla so a buddy can see us** — grounded in the Bir Billing scrub.
 *
 * The board has no GPS (Mezulla NO_PIN), so only the phone knows where we are: our own *position*
 * is the one thing the app must actively transmit. Callsign (NodeInfo) and battery (Telemetry) ride
 * in on the firmware's own periodic broadcasts; a peer's climb rate is *derived* receiver-side from
 * the altitude deltas in our position frames — which is exactly why the cadence matters.
 *
 * One position frame carries everything all three peer view modes read:
 *   - SAFETY   → altitude (+ age)
 *   - CLIMB    → climb, derived from successive altitudes (needs a steady ~1 Hz of fixes)
 *   - TACTICAL → distance/bearing (from lat/lon) + ground speed
 *
 * Cadence is **synced to the vario**: we broadcast on the vario's positioned fixes (≈1 Hz on XC
 * Tracer, matching the 1 s tick the whole peer UI was modelled around) so the altitude is baro-fresh
 * and climb derivation is accurate. [PositionBroadcastPolicy] only adds a floor so a faster/jittery
 * fix stream can't flood the shared LoRa airtime.
 */
object PositionBroadcastPolicy {

    /**
     * Floor between broadcasts (ms). At/below the vario's ~1 Hz this passes essentially every
     * positioned fix; it only bites if fixes arrive faster, capping us at ~1.3 Hz to protect LoRa
     * airtime (every pilot shares one channel). Set to 0 to broadcast on literally every fix.
     */
    const val MIN_INTERVAL_MS = 750L

    /**
     * Should we broadcast this fix? Only positioned fixes (the board can't relay a fix with no
     * lat/lon), and never closer together than [MIN_INTERVAL_MS]. [lastSentMs] is the epoch-ms of
     * the previous broadcast (null = none yet); [nowMs] is this fix's time.
     */
    fun shouldBroadcast(hasPosition: Boolean, lastSentMs: Long?, nowMs: Long): Boolean =
        hasPosition && (lastSentMs == null || nowMs - lastSentMs >= MIN_INTERVAL_MS)
}

/**
 * Build the over-the-mesh [PeerPosition.Fix] the codec broadcasts, from whatever source has our
 * position (vario or phone GPS). Altitude is rounded to whole metres (Meshtastic `Position.altitude`
 * is an int); [timeMs] is epoch milliseconds and becomes `Position.time` in seconds. Optional
 * channels stay null when the source doesn't report them (a basic phone may lack speed/bearing).
 */
fun peerPositionFix(
    latitudeDeg: Double,
    longitudeDeg: Double,
    altitudeM: Double?,
    groundSpeedMs: Double?,
    trackDeg: Double?,
    timeMs: Long,
): PeerPosition.Fix = PeerPosition.Fix(
    latitudeDeg = latitudeDeg,
    longitudeDeg = longitudeDeg,
    altitudeMeters = altitudeM?.roundToInt(),
    groundSpeedMetersPerSecond = groundSpeedMs,
    groundTrackDegrees = trackDeg,
    timestampSeconds = timeMs / 1000,
)

/**
 * Map a live [SensorFix] (vario path) to a broadcastable [PeerPosition.Fix]. Returns null for a fix
 * with no position (nothing to send). Phone-GPS callers build the fix directly via [peerPositionFix].
 */
fun SensorFix.toPeerPositionFix(): PeerPosition.Fix? {
    val la = lat ?: return null
    val lo = lon ?: return null
    return peerPositionFix(la, lo, gpsAltitudeM, groundSpeedMs, courseDeg, timeMs)
}
