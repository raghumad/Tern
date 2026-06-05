package com.ternparagliding.utils.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.osmdroid.util.GeoPoint
import kotlin.random.Random

/**
 * Correctness gate for the Hilbert-range spatial query
 * ([MapOverlayCacheUtils.queryHilbertRange]).
 *
 * The interval-cover query MUST return exactly the same features as a
 * brute-force haversine full scan — this is the proof that we did not
 * reintroduce the old single-Hilbert-window bug, which silently dropped
 * features across the curve's folds. See
 * docs/design/hilbert-spatial-query-restore.md.
 */
class HilbertSpatialQueryTest {

    private val bits = 16 // airspace / PG-spot / weather precision

    /** Same formula as MapOverlayCacheUtils.haversineMeters, so boundary cases agree. */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    private fun entry(lat: Double, lon: Double): MapOverlayCacheUtils.HilbertIndexEntry =
        MapOverlayCacheUtils.HilbertIndexEntry(
            hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(GeoPoint(lat, lon), bits),
            byteOffset = 0,
            byteLength = 100, // > 4 so the query doesn't skip it
            centroidLat = lat,
            centroidLon = lon,
        )

    @Test
    fun hilbertRangeMatchesFullScanEverywhere() {
        val rnd = Random(20260604)
        val points = ArrayList<GeoPoint>()
        // Global scatter.
        repeat(4000) { points.add(GeoPoint(rnd.nextDouble(-82.0, 82.0), rnd.nextDouble(-180.0, 180.0))) }
        // Dense clusters incl. across the antimeridian, to stress curve folds.
        listOf(
            GeoPoint(39.74, -105.0),  // Denver
            GeoPoint(45.92, 6.13),    // Annecy
            GeoPoint(1.0, 179.4),     // near +180
            GeoPoint(-1.0, -179.4),   // near -180
            GeoPoint(51.5, 0.0),      // London (prime meridian)
        ).forEach { c ->
            repeat(400) {
                val lat = (c.latitude + rnd.nextDouble(-1.8, 1.8)).coerceIn(-89.0, 89.0)
                var lon = c.longitude + rnd.nextDouble(-2.2, 2.2)
                if (lon > 180.0) lon -= 360.0
                if (lon < -180.0) lon += 360.0
                points.add(GeoPoint(lat, lon))
            }
        }

        val entries = points.map { entry(it.latitude, it.longitude) }
        val sorted = entries.sortedBy { it.hilbertIndex }

        val queries = buildList {
            add(GeoPoint(39.74, -105.0) to 200_000.0)
            add(GeoPoint(45.92, 6.13) to 200_000.0)
            add(GeoPoint(1.0, 179.5) to 250_000.0)    // antimeridian crossing
            add(GeoPoint(-1.0, -179.6) to 250_000.0)  // antimeridian crossing
            add(GeoPoint(0.0, 0.0) to 600_000.0)
            add(GeoPoint(70.0, 20.0) to 300_000.0)    // high latitude
            repeat(80) {
                add(
                    GeoPoint(rnd.nextDouble(-75.0, 75.0), rnd.nextDouble(-179.0, 179.0)) to
                        rnd.nextDouble(15_000.0, 450_000.0),
                )
            }
        }

        for ((center, radius) in queries) {
            val oracle = entries
                .filter { haversineMeters(center.latitude, center.longitude, it.centroidLat, it.centroidLon) <= radius }
                .map { it.centroidLat to it.centroidLon }
                .toSet()
            val got = MapOverlayCacheUtils
                .queryHilbertRange(sorted, bits, center, radius, Int.MAX_VALUE)
                .map { it.centroidLat to it.centroidLon }
                .toSet()
            assertThat(got).isEqualTo(oracle)
        }
    }

    @Test
    fun respectsLimitNearestFirst() {
        val rnd = Random(7)
        val center = GeoPoint(40.0, -105.0)
        val entries = (0 until 500).map {
            entry(center.latitude + rnd.nextDouble(-1.0, 1.0), center.longitude + rnd.nextDouble(-1.0, 1.0))
        }.sortedBy { it.hilbertIndex }

        val limited = MapOverlayCacheUtils.queryHilbertRange(entries, bits, center, 500_000.0, 10)
        assertThat(limited.size).isAtMost(10)
        // Nearest-first: distances must be non-decreasing.
        val dists = limited.map { haversineMeters(center.latitude, center.longitude, it.centroidLat, it.centroidLon) }
        assertThat(dists).isInOrder()
    }

    @Test
    fun emptyIndexReturnsEmpty() {
        val got = MapOverlayCacheUtils.queryHilbertRange(emptyList(), bits, GeoPoint(0.0, 0.0), 100_000.0, 100)
        assertThat(got).isEmpty()
    }
}
