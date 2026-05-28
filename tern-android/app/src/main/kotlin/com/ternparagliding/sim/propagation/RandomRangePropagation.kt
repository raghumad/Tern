package com.ternparagliding.sim.propagation

import kotlin.random.Random

/**
 * Propagation model with a randomised effective range.
 *
 * On each [propagate] call, a fresh effective range is drawn uniformly
 * from `[minRangeMeters, maxRangeMeters]`. The packet is delivered if
 * and only if the 3-D distance between sender and receiver is at most
 * the drawn range (inclusive boundary, matching [DistanceOnlyPropagation]).
 *
 * This models the real-world observation that LoRa range is not a hard
 * circle: atmospheric conditions, antenna orientation, local terrain
 * reflections, and body shielding all shift the effective range from
 * packet to packet. The uniform distribution is a deliberate
 * simplification — real fade statistics are Rayleigh/Rician — but it
 * produces enough variance to exercise the simulator's "sometimes in
 * range, sometimes not" code paths without pretending to be a radio
 * propagation textbook.
 *
 * Uses the same Haversine + altitude distance calculation as
 * [DistanceOnlyPropagation].
 *
 * @param minRangeMeters lower bound of the effective-range draw
 *   (inclusive). Must be > 0.
 * @param maxRangeMeters upper bound of the effective-range draw
 *   (inclusive). Must be >= [minRangeMeters].
 * @param random source of randomness. Defaults to [Random.Default];
 *   pass a seeded instance (e.g. `Random(42)`) for deterministic tests.
 */
class RandomRangePropagation(
    val minRangeMeters: Double,
    val maxRangeMeters: Double,
    private val random: Random = Random.Default,
) : PropagationModel {

    init {
        require(minRangeMeters > 0.0) {
            "minRangeMeters must be positive, got $minRangeMeters"
        }
        require(maxRangeMeters >= minRangeMeters) {
            "maxRangeMeters ($maxRangeMeters) must be >= minRangeMeters ($minRangeMeters)"
        }
    }

    override fun propagate(
        sender: PilotEndpoint,
        receiver: PilotEndpoint,
        txPower: TxPower,
    ): PropagationOutcome {
        val effectiveRange = random.nextDouble(minRangeMeters, maxRangeMeters + INCLUSIVE_EPSILON)
        val distance = DistanceOnlyPropagation.distance3dMeters(sender, receiver)
        return if (distance <= effectiveRange) {
            PropagationOutcome.Delivered
        } else {
            PropagationOutcome.Lost(LossReason.OutOfRange)
        }
    }

    companion object {
        /**
         * Tiny epsilon added to [maxRangeMeters] when calling
         * [Random.nextDouble] so the upper bound is effectively
         * inclusive. [Random.nextDouble] returns values in `[from, until)`;
         * adding a sub-millimetre offset makes `until` just past
         * [maxRangeMeters] so values equal to max are reachable.
         */
        private const val INCLUSIVE_EPSILON: Double = 1e-9
    }
}
