package com.ternparagliding.sim.propagation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.random.Random

/**
 * Tests for [RandomRangePropagation].
 *
 * Uses a seeded [Random] for determinism. The test scenarios exercise
 * three regimes:
 *  - distance well below minRange: always Delivered regardless of draw
 *  - distance well above maxRange: always Lost regardless of draw
 *  - distance between min and max: outcome varies with the draw
 */
class RandomRangePropagationTest {

    /**
     * Latitude offset that produces a Haversine surface distance of
     * exactly 1000 m on a 6371 km sphere, same derivation as
     * [DistanceOnlyPropagationTest].
     */
    private val oneKmLatOffsetDeg: Double =
        1000.0 / (PI * DistanceOnlyPropagation.EARTH_RADIUS_METERS / 180.0)

    // -- Always Delivered (distance < minRange) --------------------------

    @Test
    fun `pair well inside min range is always delivered`() {
        // 1 km apart, range drawn from 5..10 km -- always delivered.
        val model = RandomRangePropagation(
            minRangeMeters = 5_000.0,
            maxRangeMeters = 10_000.0,
            random = Random(42),
        )
        val a = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeMeters = 1000.0)
        val b = PilotEndpoint(
            latitudeDeg = oneKmLatOffsetDeg, longitudeDeg = 0.0, altitudeMeters = 1000.0,
        )

        // Run 100 times; every call must deliver.
        repeat(100) { i ->
            assertWithMessage("iteration $i")
                .that(model.propagate(a, b))
                .isEqualTo(PropagationOutcome.Delivered)
        }
    }

    // -- Always Lost (distance > maxRange) -------------------------------

    @Test
    fun `pair well beyond max range is always lost`() {
        // ~20 km apart, range drawn from 5..10 km -- always lost.
        val model = RandomRangePropagation(
            minRangeMeters = 5_000.0,
            maxRangeMeters = 10_000.0,
            random = Random(42),
        )
        val a = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeMeters = 1000.0)
        val b = PilotEndpoint(
            latitudeDeg = oneKmLatOffsetDeg * 20, longitudeDeg = 0.0, altitudeMeters = 1000.0,
        )

        repeat(100) { i ->
            assertWithMessage("iteration $i")
                .that(model.propagate(a, b))
                .isEqualTo(PropagationOutcome.Lost(LossReason.OutOfRange))
        }
    }

    // -- Mixed outcomes (distance between min and max) -------------------

    @Test
    fun `pair at mid-range sees a mix of delivered and lost`() {
        // Pilots ~7.5 km apart, range drawn from 5..10 km. Over many
        // draws we expect some Delivered and some Lost.
        val model = RandomRangePropagation(
            minRangeMeters = 5_000.0,
            maxRangeMeters = 10_000.0,
            random = Random(123),
        )
        val a = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeMeters = 1000.0)
        val b = PilotEndpoint(
            latitudeDeg = oneKmLatOffsetDeg * 7.5, longitudeDeg = 0.0, altitudeMeters = 1000.0,
        )

        val outcomes = (1..200).map { model.propagate(a, b) }
        val delivered = outcomes.count { it is PropagationOutcome.Delivered }
        val lost = outcomes.count { it is PropagationOutcome.Lost }

        assertWithMessage("expected some Delivered").that(delivered).isGreaterThan(0)
        assertWithMessage("expected some Lost").that(lost).isGreaterThan(0)
    }

    // -- Deterministic with seeded Random --------------------------------

    @Test
    fun `same seed produces same sequence of outcomes`() {
        val a = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeMeters = 1000.0)
        val b = PilotEndpoint(
            latitudeDeg = oneKmLatOffsetDeg * 7.5, longitudeDeg = 0.0, altitudeMeters = 1000.0,
        )

        val run1 = (1..50).map {
            RandomRangePropagation(5_000.0, 10_000.0, Random(999)).propagate(a, b)
        }
        val run2 = (1..50).map {
            RandomRangePropagation(5_000.0, 10_000.0, Random(999)).propagate(a, b)
        }

        assertThat(run1).isEqualTo(run2)
    }

    // -- Altitude contributes to distance --------------------------------

    @Test
    fun `altitude separation alone can push a pair out of random range`() {
        // Same lat/lon, 8000 m altitude apart. Range drawn from 5..7 km.
        // 8 km > 7 km max, so always lost.
        val model = RandomRangePropagation(
            minRangeMeters = 5_000.0,
            maxRangeMeters = 7_000.0,
            random = Random(42),
        )
        val low = PilotEndpoint(latitudeDeg = 45.0, longitudeDeg = 7.0, altitudeMeters = 0.0)
        val high = PilotEndpoint(latitudeDeg = 45.0, longitudeDeg = 7.0, altitudeMeters = 8000.0)

        repeat(50) { i ->
            assertWithMessage("iteration $i")
                .that(model.propagate(low, high))
                .isEqualTo(PropagationOutcome.Lost(LossReason.OutOfRange))
        }
    }

    // -- Constructor validation ------------------------------------------

    @Test
    fun `constructor rejects non-positive min range`() {
        runCatching { RandomRangePropagation(minRangeMeters = 0.0, maxRangeMeters = 100.0) }
            .also { assertThat(it.isFailure).isTrue() }
        runCatching { RandomRangePropagation(minRangeMeters = -1.0, maxRangeMeters = 100.0) }
            .also { assertThat(it.isFailure).isTrue() }
    }

    @Test
    fun `constructor rejects max less than min`() {
        runCatching { RandomRangePropagation(minRangeMeters = 10_000.0, maxRangeMeters = 5_000.0) }
            .also { assertThat(it.isFailure).isTrue() }
    }

    @Test
    fun `min equals max behaves like DistanceOnlyPropagation`() {
        // When min == max, every draw is effectively the same value.
        // 1 km apart, range fixed at 5 km -> always Delivered.
        val model = RandomRangePropagation(
            minRangeMeters = 5_000.0,
            maxRangeMeters = 5_000.0,
            random = Random(42),
        )
        val a = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeMeters = 1000.0)
        val b = PilotEndpoint(
            latitudeDeg = oneKmLatOffsetDeg, longitudeDeg = 0.0, altitudeMeters = 1000.0,
        )

        repeat(50) { i ->
            assertWithMessage("iteration $i")
                .that(model.propagate(a, b))
                .isEqualTo(PropagationOutcome.Delivered)
        }
    }
}
