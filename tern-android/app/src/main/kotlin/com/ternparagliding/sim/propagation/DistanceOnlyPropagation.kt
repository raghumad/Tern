package com.ternparagliding.sim.propagation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First-cut propagation model: a packet is delivered if and only if
 * the three-dimensional distance between sender and receiver is no
 * more than [maxRangeMeters].
 *
 * "Three-dimensional distance" means the surface (great-circle)
 * distance combined with the altitude difference, treated as the
 * legs of a right triangle. That's a small simplification: it ignores
 * Earth curvature in the altitude leg, which is irrelevant at the
 * scales this model targets (single-digit km between paraglider
 * pilots, hundreds to thousands of metres altitude).
 *
 * What this model deliberately does NOT do:
 *  - terrain blocking (no DEM lookups) — that's a future
 *    `LineOfSightPropagation` (WS1.3 hooks, not yet implemented).
 *  - Fresnel-zone or fade modelling.
 *  - co-channel interference, duty-cycle limits.
 *
 * A flat distance threshold is a known overestimate of real LoRa
 * reach in mountainous terrain. We use it as the first cut because:
 *  - it is exactly the model the BDD test vocabulary uses ("a LoRa
 *    propagation model with a 15 km clear-air range"), and
 *  - it lets the rest of the simulator (WS1.4) be built and tested
 *    end-to-end before any DEM-backed model exists.
 *
 * @param maxRangeMeters inclusive upper bound on 3-D distance for a
 *   packet to count as delivered. Callers decide the value — typical
 *   first-cut value used by the convergence test is 15_000 m.
 */
class DistanceOnlyPropagation(
    val maxRangeMeters: Int,
) : PropagationModel {

    init {
        require(maxRangeMeters > 0) {
            "maxRangeMeters must be positive, got $maxRangeMeters"
        }
    }

    override fun propagate(
        sender: PilotEndpoint,
        receiver: PilotEndpoint,
        txPower: TxPower,
    ): PropagationOutcome {
        val distance = distance3dMeters(sender, receiver)
        // Inclusive boundary: distance == maxRangeMeters counts as Delivered.
        // The choice is arbitrary at the edge but matters for repeatable
        // tests. We pick inclusive so a "5 km range, pilots exactly 5 km
        // apart" test reads as Delivered without spurious floating-point
        // wobble in either direction.
        return if (distance <= maxRangeMeters.toDouble()) {
            PropagationOutcome.Delivered
        } else {
            PropagationOutcome.Lost(LossReason.OutOfRange)
        }
    }

    companion object {

        /**
         * Mean Earth radius in metres used by the Haversine formula. The
         * 6371.0 km value is the spherical-Earth mean recommended by the
         * IUGG; using WGS-84 ellipsoidal geodesics would change the answer
         * by under 0.5 % at our scales, which is well below the
         * uncertainty of any propagation effect we are modelling.
         */
        const val EARTH_RADIUS_METERS = 6_371_000.0

        /**
         * Great-circle surface distance between two latitude/longitude
         * points, in metres.
         *
         * Formula: Haversine.
         *   a = sin²(Δφ/2) + cos(φ1)·cos(φ2)·sin²(Δλ/2)
         *   c = 2·atan2(√a, √(1-a))
         *   d = R·c
         * Reference: Chris Veness, "Calculate distance, bearing and more
         * between Latitude/Longitude points",
         * https://www.movable-type.co.uk/scripts/latlong.html
         */
        fun haversineMeters(
            lat1Deg: Double,
            lon1Deg: Double,
            lat2Deg: Double,
            lon2Deg: Double,
        ): Double {
            val phi1 = Math.toRadians(lat1Deg)
            val phi2 = Math.toRadians(lat2Deg)
            val dPhi = Math.toRadians(lat2Deg - lat1Deg)
            val dLambda = Math.toRadians(lon2Deg - lon1Deg)

            val sinDPhi = sin(dPhi / 2.0)
            val sinDLambda = sin(dLambda / 2.0)
            val a = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
            val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
            return EARTH_RADIUS_METERS * c
        }

        /**
         * 3-D distance between two [PilotEndpoint]s.
         *
         * Treats the surface (Haversine) distance and the altitude
         * difference as orthogonal legs and combines them with
         * Pythagoras: d = √(surface² + Δalt²). See class-level KDoc for
         * why the small-Earth-curvature simplification is acceptable
         * here.
         */
        fun distance3dMeters(a: PilotEndpoint, b: PilotEndpoint): Double {
            val surface = haversineMeters(
                a.latitudeDeg, a.longitudeDeg,
                b.latitudeDeg, b.longitudeDeg,
            )
            val dAlt = a.altitudeMeters - b.altitudeMeters
            // hypot avoids overflow/underflow on intermediate squares.
            return hypot(surface, dAlt)
        }
    }
}
