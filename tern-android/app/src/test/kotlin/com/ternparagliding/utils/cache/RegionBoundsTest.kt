package com.ternparagliding.utils.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * The per-region bbox filter that lets queryAllCachedNearby skip far-away
 * countries without loading their index. It's an *optimization*: it must never
 * wrongly exclude a region that actually overlaps the query.
 */
class RegionBoundsTest {

    private fun bounds(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) =
        MapOverlayCacheUtils.RegionBounds(minLat, minLon, maxLat, maxLon)

    @Test
    fun ofRadiusContainsCentreAndScalesWithRadius() {
        val c = GeoPoint(38.9, -77.0) // DC
        val b = MapOverlayCacheUtils.RegionBounds.ofRadius(c, 200_000.0)
        assertThat(c.latitude).isIn(com.google.common.collect.Range.closed(b.minLat, b.maxLat))
        assertThat(c.longitude).isIn(com.google.common.collect.Range.closed(b.minLon, b.maxLon))
        // ~200 km ≈ 1.8° lat half-extent
        assertThat(b.maxLat - b.minLat).isWithin(0.2).of(3.6)
    }

    @Test
    fun usBoundsRejectFranceQueryButAcceptDcQuery() {
        // Rough US data bbox and FR data bbox.
        val us = bounds(24.5, -125.0, 49.4, -66.9)
        val fr = bounds(41.3, -5.2, 51.1, 9.6)

        val dcQuery = MapOverlayCacheUtils.RegionBounds.ofRadius(GeoPoint(38.9, -77.0), 200_000.0)
        val annecyQuery = MapOverlayCacheUtils.RegionBounds.ofRadius(GeoPoint(45.9, 6.13), 200_000.0)

        assertThat(us.intersects(dcQuery)).isTrue()
        assertThat(fr.intersects(dcQuery)).isFalse()   // France skipped for a DC query
        assertThat(fr.intersects(annecyQuery)).isTrue()
        assertThat(us.intersects(annecyQuery)).isFalse() // US skipped for an Annecy query
    }

    @Test
    fun antimeridianQueryIsFlaggedSoCallersDontFilter() {
        val q = MapOverlayCacheUtils.RegionBounds.ofRadius(GeoPoint(0.0, 179.6), 250_000.0)
        assertThat(q.crossesAntimeridian()).isTrue()
        val normal = MapOverlayCacheUtils.RegionBounds.ofRadius(GeoPoint(38.9, -77.0), 200_000.0)
        assertThat(normal.crossesAntimeridian()).isFalse()
    }
}
