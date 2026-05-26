package com.ternparagliding.offline

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Route
import com.ternparagliding.model.TernBoundingBox
import com.ternparagliding.model.Waypoint
import com.ternparagliding.model.LocationType
import org.junit.jupiter.api.Test

/**
 * Tests for the bounding-box + padding computations used by RouteTileCacher.
 *
 * These are pure-math tests (no Android context needed) that verify the
 * spatial reasoning is correct before any MapLibre API is touched.
 */
class RouteTileCacherTest {

    // ---------- TernBoundingBox.withPaddingKm ----------

    @Test
    fun `withPaddingKm expands box by roughly correct amount at equator`() {
        // 1x1 degree box centered at the equator
        val box = TernBoundingBox(
            minLat = -0.5, minLon = -0.5,
            maxLat = 0.5, maxLon = 0.5
        )

        val padded = box.withPaddingKm(10.0)

        // 10 km ~ 0.0899 degrees latitude (10 / 111.32)
        val expectedLatDelta = 10.0 / 111.32
        assertThat(padded.minLat).isWithin(0.001).of(box.minLat - expectedLatDelta)
        assertThat(padded.maxLat).isWithin(0.001).of(box.maxLat + expectedLatDelta)

        // At equator cos(0) = 1, so lon delta == lat delta
        assertThat(padded.minLon).isWithin(0.001).of(box.minLon - expectedLatDelta)
        assertThat(padded.maxLon).isWithin(0.001).of(box.maxLon + expectedLatDelta)
    }

    @Test
    fun `withPaddingKm lon expansion grows at higher latitudes`() {
        // Box centered at ~45 degrees north (typical European flying area)
        val box = TernBoundingBox(
            minLat = 44.5, minLon = 5.5,
            maxLat = 45.5, maxLon = 6.5
        )

        val padded = box.withPaddingKm(10.0)

        val latDelta = 10.0 / 111.32
        val lonDelta = 10.0 / (111.32 * Math.cos(Math.toRadians(box.centerLat)))

        // At 45 deg, lon degrees are wider than lat degrees in km,
        // so the lon delta in degrees should be LARGER than the lat delta.
        assertThat(lonDelta).isGreaterThan(latDelta)

        assertThat(padded.minLat).isWithin(0.001).of(box.minLat - latDelta)
        assertThat(padded.maxLat).isWithin(0.001).of(box.maxLat + latDelta)
        assertThat(padded.minLon).isWithin(0.001).of(box.minLon - lonDelta)
        assertThat(padded.maxLon).isWithin(0.001).of(box.maxLon + lonDelta)
    }

    @Test
    fun `withPaddingKm zero padding returns identical box`() {
        val box = TernBoundingBox(
            minLat = 40.0, minLon = -105.0,
            maxLat = 41.0, maxLon = -104.0
        )

        val padded = box.withPaddingKm(0.0)

        assertThat(padded.minLat).isEqualTo(box.minLat)
        assertThat(padded.minLon).isEqualTo(box.minLon)
        assertThat(padded.maxLat).isEqualTo(box.maxLat)
        assertThat(padded.maxLon).isEqualTo(box.maxLon)
    }

    // ---------- Route.extent ----------

    @Test
    fun `route extent covers all waypoints`() {
        val route = Route(name = "Test")
            .addWaypoint(45.0, 6.0, LocationType.TURNPOINT, "A")
            .addWaypoint(46.0, 7.0, LocationType.TURNPOINT, "B")
            .addWaypoint(44.5, 5.5, LocationType.TURNPOINT, "C")

        val extent = route.extent!!

        assertThat(extent.minLat).isEqualTo(44.5)
        assertThat(extent.maxLat).isEqualTo(46.0)
        assertThat(extent.minLon).isEqualTo(5.5)
        assertThat(extent.maxLon).isEqualTo(7.0)
    }

    @Test
    fun `route with no waypoints has null extent`() {
        val route = Route(name = "Empty")
        assertThat(route.extent).isNull()
    }

    @Test
    fun `route extent with single waypoint is a zero-size box`() {
        val route = Route(name = "Single")
            .addWaypoint(45.0, 6.0, LocationType.TURNPOINT, "Only")

        val extent = route.extent!!
        assertThat(extent.minLat).isEqualTo(45.0)
        assertThat(extent.maxLat).isEqualTo(45.0)
        assertThat(extent.minLon).isEqualTo(6.0)
        assertThat(extent.maxLon).isEqualTo(6.0)
    }

    @Test
    fun `single-waypoint route padded by 10km still produces valid box`() {
        val route = Route(name = "Single")
            .addWaypoint(45.0, 6.0, LocationType.TURNPOINT, "Only")

        val padded = route.extent!!.withPaddingKm(RouteTileCacher.PADDING_KM)

        // The padded box should have non-zero area
        assertThat(padded.heightLat).isGreaterThan(0.0)
        assertThat(padded.widthLon).isGreaterThan(0.0)
        // And should be centred on the waypoint
        assertThat(padded.centerLat).isWithin(0.001).of(45.0)
        assertThat(padded.centerLon).isWithin(0.001).of(6.0)
    }

    // ---------- CacheProgress ----------

    @Test
    fun `CacheProgress fraction is zero when no resources required`() {
        val progress = RouteTileCacher.CacheProgress(
            completedResources = 0,
            requiredResources = 0,
            completedBytes = 0,
            isComplete = true
        )
        assertThat(progress.fraction).isEqualTo(0f)
    }

    @Test
    fun `CacheProgress fraction calculates correctly`() {
        val progress = RouteTileCacher.CacheProgress(
            completedResources = 50,
            requiredResources = 200,
            completedBytes = 1024,
            isComplete = false
        )
        assertThat(progress.fraction).isWithin(0.001f).of(0.25f)
    }

    @Test
    fun `CacheProgress fraction is clamped to 1`() {
        // Edge case: completed > required (can happen if estimate was low)
        val progress = RouteTileCacher.CacheProgress(
            completedResources = 250,
            requiredResources = 200,
            completedBytes = 1024,
            isComplete = true
        )
        assertThat(progress.fraction).isEqualTo(1f)
    }
}
