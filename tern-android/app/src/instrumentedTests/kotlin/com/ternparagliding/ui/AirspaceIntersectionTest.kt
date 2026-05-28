package com.ternparagliding.ui

import org.junit.Ignore
import org.junit.Test

/**
 * Airspace intersection test using OSMDroid Polygon overlays.
 *
 * M8: DISABLED. This test created OSMDroid Polygon overlays directly on
 * MapView and used GeoJsonUtils.isPointInPolygon to test intersection.
 * Airspaces are now rendered as MapLibre FillLayer/LineLayer via
 * AirspaceOverlay. The intersection logic (if needed) should be tested
 * against the GeoJSON data, not against rendered overlays.
 */
@Ignore("M8: OSMDroid Polygon/MapView deleted during MapLibre migration")
class AirspaceIntersectionTest {

    @Test
    fun testWaypointInsideAirspace() {
        // Placeholder -- see class-level @Ignore
    }
}
