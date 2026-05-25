package com.madanala.tern.overlay.priority

import com.madanala.tern.sim.propagation.DistanceOnlyPropagation

/**
 * Renderer-agnostic geographic position. This is Tern's own type --
 * it does not depend on OSMDroid GeoPoint, MapLibre Position, or any
 * other map library.
 */
data class Position(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Double = 0.0,
) {
    /** Surface distance to [other] in kilometres, using Haversine. */
    fun distanceKm(other: Position): Double {
        val meters = DistanceOnlyPropagation.haversineMeters(
            latitudeDeg, longitudeDeg,
            other.latitudeDeg, other.longitudeDeg,
        )
        return meters / 1_000.0
    }
}
