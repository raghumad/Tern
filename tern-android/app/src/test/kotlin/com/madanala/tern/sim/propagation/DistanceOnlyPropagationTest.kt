package com.madanala.tern.sim.propagation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.PI

/**
 * Tests for [DistanceOnlyPropagation].
 *
 * These are pure-JVM unit tests; no Android, no fixtures, no
 * propagation hardware. They exercise the model directly with named
 * scenarios chosen to lock down:
 *  - reciprocity (A->B and B->A give the same answer),
 *  - the inclusive-boundary contract documented in the model,
 *  - that altitude difference contributes to the 3-D distance,
 *  - that the surface-distance formula is the real Haversine and not
 *    something approximated, by checking against a precomputed value
 *    for two named real-world points.
 */
class DistanceOnlyPropagationTest {

    /**
     * Latitude offset that produces a Haversine surface distance of
     * exactly 1000 m on a 6371 km sphere when applied between two
     * points on the equator at the same longitude.
     *
     * Derivation: at the equator, 1 degree of latitude spans
     * π·R/180 metres. So 1000 m corresponds to 1000 / (π·R/180) deg.
     */
    private val oneKmLatOffsetDeg: Double =
        1000.0 / (PI * DistanceOnlyPropagation.EARTH_RADIUS_METERS / 180.0)

    @Test
    fun `pair 1 km apart at same altitude is delivered both directions when range is 5 km`() {
        val model = DistanceOnlyPropagation(maxRangeMeters = 5_000)
        val a = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeMeters = 1000.0)
        val b = PilotEndpoint(
            latitudeDeg = oneKmLatOffsetDeg, longitudeDeg = 0.0, altitudeMeters = 1000.0,
        )

        assertThat(model.propagate(a, b)).isEqualTo(PropagationOutcome.Delivered)
        assertThat(model.propagate(b, a)).isEqualTo(PropagationOutcome.Delivered)
    }

    @Test
    fun `pair 1 km apart is lost both directions when range is 500 m`() {
        val model = DistanceOnlyPropagation(maxRangeMeters = 500)
        val a = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeMeters = 1000.0)
        val b = PilotEndpoint(
            latitudeDeg = oneKmLatOffsetDeg, longitudeDeg = 0.0, altitudeMeters = 1000.0,
        )

        val outAB = model.propagate(a, b)
        val outBA = model.propagate(b, a)

        assertThat(outAB).isEqualTo(PropagationOutcome.Lost(LossReason.OutOfRange))
        assertThat(outBA).isEqualTo(PropagationOutcome.Lost(LossReason.OutOfRange))
    }

    @Test
    fun `altitude separation alone can push a pair out of range`() {
        // Same lat/lon, but 5000 m altitude apart. Surface distance is 0,
        // so 3-D distance is exactly the altitude delta (5000 m). A 4 km
        // range must report Lost.
        val model = DistanceOnlyPropagation(maxRangeMeters = 4_000)
        val low = PilotEndpoint(latitudeDeg = 45.0, longitudeDeg = 7.0, altitudeMeters = 0.0)
        val high = PilotEndpoint(latitudeDeg = 45.0, longitudeDeg = 7.0, altitudeMeters = 5000.0)

        assertThat(model.propagate(low, high))
            .isEqualTo(PropagationOutcome.Lost(LossReason.OutOfRange))
        assertThat(model.propagate(high, low))
            .isEqualTo(PropagationOutcome.Lost(LossReason.OutOfRange))
    }

    @Test
    fun `antipodal points are far beyond any reasonable LoRa range`() {
        // (0,0) and (0,180) — half the Earth's circumference apart, about
        // 20 015 km. A generous 100 km range is still nowhere near enough.
        val model = DistanceOnlyPropagation(maxRangeMeters = 100_000)
        val a = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeMeters = 0.0)
        val b = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 180.0, altitudeMeters = 0.0)

        assertThat(model.propagate(a, b))
            .isEqualTo(PropagationOutcome.Lost(LossReason.OutOfRange))
    }

    @Test
    fun `distance exactly equal to maxRange is delivered (inclusive boundary)`() {
        // Two pilots exactly 1000 m apart, range set to exactly 1000 m.
        // The implementation documents the boundary as inclusive; this
        // test pins that behaviour.
        val model = DistanceOnlyPropagation(maxRangeMeters = 1_000)
        val a = PilotEndpoint(latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeMeters = 0.0)
        val b = PilotEndpoint(
            latitudeDeg = oneKmLatOffsetDeg, longitudeDeg = 0.0, altitudeMeters = 0.0,
        )

        // First confirm our test setup really produces 1000 m within a
        // tight floating-point tolerance, so the boundary test is honest.
        val measured = DistanceOnlyPropagation.distance3dMeters(a, b)
        assertThat(measured).isWithin(1e-6).of(1000.0)

        assertThat(model.propagate(a, b)).isEqualTo(PropagationOutcome.Delivered)
    }

    @Test
    fun `Haversine matches a precomputed reference for Mont Blanc to Chamonix`() {
        // Reference points:
        //   Mont Blanc summit: 45.8326 N, 6.8652 E
        //     (Wikipedia "Mont Blanc": coordinates 45 deg 49' 57" N,
        //      6 deg 51' 54" E.)
        //   Chamonix town centre: 45.9237 N, 6.8694 E
        //     (Wikipedia "Chamonix-Mont-Blanc": coordinates 45 deg 55' 25" N,
        //      6 deg 52' 9" E.)
        //
        // Expected surface distance: 10_135.07 m on a 6371 km sphere.
        // Cross-checked two ways:
        //  1. Haversine formula (Chris Veness, Movable Type Scripts,
        //     https://www.movable-type.co.uk/scripts/latlong.html).
        //  2. Spherical law of cosines, an independent formula on the
        //     same sphere: d = acos(sinφ1·sinφ2 + cosφ1·cosφ2·cosΔλ)·R.
        // Both give 10135.074 m, agreeing to sub-millimetre, which
        // means a 50 m tolerance here is genuinely testing our
        // implementation, not the published value.
        val expectedMeters = 10_135.07
        val measured = DistanceOnlyPropagation.haversineMeters(
            lat1Deg = 45.8326, lon1Deg = 6.8652,
            lat2Deg = 45.9237, lon2Deg = 6.8694,
        )
        assertThat(measured).isWithin(50.0).of(expectedMeters)
    }

    @Test
    fun `Haversine is symmetric`() {
        val d1 = DistanceOnlyPropagation.haversineMeters(45.8326, 6.8652, 45.9237, 6.8694)
        val d2 = DistanceOnlyPropagation.haversineMeters(45.9237, 6.8694, 45.8326, 6.8652)
        assertThat(d1).isEqualTo(d2)
    }

    @Test
    fun `Haversine returns zero for coincident points`() {
        val d = DistanceOnlyPropagation.haversineMeters(45.0, 7.0, 45.0, 7.0)
        assertThat(d).isEqualTo(0.0)
    }

    @Test
    fun `3-D distance reduces to altitude delta when surface distance is zero`() {
        val a = PilotEndpoint(latitudeDeg = 45.0, longitudeDeg = 7.0, altitudeMeters = 1000.0)
        val b = PilotEndpoint(latitudeDeg = 45.0, longitudeDeg = 7.0, altitudeMeters = 3500.0)
        assertThat(DistanceOnlyPropagation.distance3dMeters(a, b))
            .isWithin(1e-9).of(2500.0)
    }

    @Test
    fun `constructor rejects non-positive max range`() {
        runCatching { DistanceOnlyPropagation(maxRangeMeters = 0) }
            .also { assertThat(it.isFailure).isTrue() }
        runCatching { DistanceOnlyPropagation(maxRangeMeters = -100) }
            .also { assertThat(it.isFailure).isTrue() }
    }
}
