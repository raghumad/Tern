package com.madanala.tern.sim.swarm

import java.time.Instant

/**
 * A pilot's position at an instant in time, as returned by
 * [SwarmPlayback.currentPosition].
 *
 * Coordinates are decimal degrees with negative for south / west, to
 * match [com.madanala.tern.sim.igc.IgcFix]. Altitude is metres above
 * the WGS-84 ellipsoid (GPS altitude from the IGC, not pressure
 * altitude — pressure is usually zero in pure-GPS recorders and not
 * useful for map rendering).
 *
 * @property isInterpolated true if the position was linearly
 *   interpolated between two adjacent fixes. False if the requested
 *   time matched an actual IGC fix timestamp exactly.
 * @property sourceFixTimestamp the timestamp of the IGC fix this
 *   position is anchored to. For an interpolated position this is
 *   the *earlier* of the two bracketing fixes — useful when a test
 *   wants to know which file row the position derives from.
 */
data class PilotPosition(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Int,
    val isInterpolated: Boolean,
    val sourceFixTimestamp: Instant,
)
