package com.madanala.tern.sim.swarm

import com.madanala.tern.sim.igc.IgcFix
import com.madanala.tern.sim.igc.IgcFlight
import java.time.Instant

/**
 * Per-pilot playback state inside a [SwarmPlayback].
 *
 * Owns the pilot's parsed [IgcFlight] and the position lookup. The
 * lookup is a binary search on the immutable, time-sorted fix list:
 *  - Hits an exact-timestamp fix when the requested time matches.
 *  - Otherwise linearly interpolates between the two bracketing fixes.
 *  - Returns null for pre-launch (before first fix) and post-landing
 *    (after last fix) so callers can distinguish "no signal yet" from
 *    "at the launch position."
 *
 * Construction validates that the fix list is non-empty and
 * monotonically non-decreasing in timestamp. The IGC spec mandates
 * ascending B-record times; the parser preserves file order but
 * doesn't enforce it (per [IgcFlight]'s contract), so the engine
 * enforces it here.
 */
class PilotState(
    val pilotId: PilotId,
    val scenarioPilot: ScenarioPilot,
    val flight: IgcFlight,
) {
    init {
        require(flight.fixes.isNotEmpty()) {
            "pilot '${pilotId}' has no fixes"
        }
        // Cheap check: walk once, fail on the first regression so the
        // error message points at the offending pair.
        var prev = flight.fixes[0].timestamp
        for (i in 1 until flight.fixes.size) {
            val cur = flight.fixes[i].timestamp
            require(!cur.isBefore(prev)) {
                "pilot '${pilotId}' fix $i goes backwards in time: $prev -> $cur"
            }
            prev = cur
        }
    }

    /** First fix's timestamp (inclusive lower bound of valid time). */
    val firstFixTime: Instant get() = flight.fixes.first().timestamp

    /** Last fix's timestamp (inclusive upper bound of valid time). */
    val lastFixTime: Instant get() = flight.fixes.last().timestamp

    /**
     * Position of this pilot at [atTime].
     *
     * Returns null if [atTime] is strictly before [firstFixTime]
     * (pre-launch) or strictly after [lastFixTime] (post-landing).
     */
    fun positionAt(atTime: Instant): PilotPosition? {
        if (atTime.isBefore(firstFixTime) || atTime.isAfter(lastFixTime)) {
            return null
        }
        val fixes = flight.fixes
        val idx = findFloorIndex(fixes, atTime)
        val floor = fixes[idx]

        // Exact match at the floor, or the floor is the last fix —
        // either way no interpolation is needed.
        if (floor.timestamp == atTime || idx == fixes.size - 1) {
            return PilotPosition(
                latitude = floor.latitude,
                longitude = floor.longitude,
                altitudeMeters = floor.gpsAltitude,
                isInterpolated = false,
                sourceFixTimestamp = floor.timestamp,
            )
        }

        val ceil = fixes[idx + 1]
        return interpolate(floor, ceil, atTime)
    }

    /**
     * Largest index `i` such that `fixes[i].timestamp <= target`.
     * Caller has already guaranteed `target` lies inside
     * [firstFixTime, lastFixTime], so the answer is always in
     * `0..fixes.size-1`.
     */
    private fun findFloorIndex(fixes: List<IgcFix>, target: Instant): Int {
        var lo = 0
        var hi = fixes.size - 1
        while (lo < hi) {
            // Bias up: when narrowing to two, pick the upper one if
            // it still satisfies `<= target`. Keeps the loop moving
            // toward the *largest* satisfying index.
            val mid = (lo + hi + 1) ushr 1
            if (fixes[mid].timestamp.isAfter(target)) {
                hi = mid - 1
            } else {
                lo = mid
            }
        }
        return lo
    }

    private fun interpolate(a: IgcFix, b: IgcFix, atTime: Instant): PilotPosition {
        val span = b.timestamp.toEpochMilli() - a.timestamp.toEpochMilli()
        // span is > 0 here because monotonicity is enforced in init and
        // atTime != a.timestamp (handled in positionAt before calling).
        val t = (atTime.toEpochMilli() - a.timestamp.toEpochMilli()).toDouble() / span
        val lat = a.latitude + (b.latitude - a.latitude) * t
        val lon = a.longitude + (b.longitude - a.longitude) * t
        // Altitude stays integer metres — sub-metre interpolation is
        // not meaningful given GPS noise. Round to nearest, not floor,
        // to avoid a downward bias.
        val alt = Math.round(a.gpsAltitude + (b.gpsAltitude - a.gpsAltitude) * t).toInt()
        return PilotPosition(
            latitude = lat,
            longitude = lon,
            altitudeMeters = alt,
            isInterpolated = true,
            sourceFixTimestamp = a.timestamp,
        )
    }
}
