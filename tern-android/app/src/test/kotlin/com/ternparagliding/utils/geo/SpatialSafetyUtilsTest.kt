package com.ternparagliding.utils.geo

import com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * K2 · task-vs-airspace. The headline case is **a leg that flies through controlled
 * airspace while both its waypoints sit outside** — the old point-in-polygon check missed it.
 */
class SpatialSafetyUtilsTest {

    // A 1°×1° square, class C, lon 6..7 / lat 46..47 (a closed ring), name "Geneva TMA".
    private val square = listOf(
        listOf(6.0, 46.0), listOf(7.0, 46.0), listOf(7.0, 47.0), listOf(6.0, 47.0), listOf(6.0, 46.0),
    )

    private fun airspace(
        name: String,
        cls: String,
        coords: List<List<Double>> = square,
        type: String = "Polygon",
    ): OverlayFeature {
        val geometry = mapOf(
            "type" to type,
            "coordinates" to if (type == "MultiPolygon") listOf(listOf(coords)) else listOf(coords),
        )
        // class/name at the feature top level so getStringProperty's fallback finds them
        // (mirrors the flattened shape the FlexBuffers path deserializes to).
        val feature = mapOf<String, Any>("geometry" to geometry, "class" to cls, "name" to name)
        val centroid = GeoPoint(coords.map { it[1] }.average(), coords.map { it[0] }.average())
        return OverlayFeature(
            internalId = "as-$name", feature = feature, centroid = centroid,
            hilbertIndex = 1L, overlayType = "airspace",
        )
    }

    // GeoPoint(lat, lon).
    private fun pt(lat: Double, lon: Double) = GeoPoint(lat, lon)

    @Test
    fun `a leg crossing the airspace with both waypoints outside is flagged`() {
        // West→east at lat 46.5: enters at lon 6, exits at lon 7; both endpoints (lon 5, lon 8) are outside.
        val task = listOf(pt(46.5, 5.0), pt(46.5, 8.0))
        val airspaces = listOf(airspace("Geneva TMA", "C"))
        assertTrue("a leg crossing controlled airspace must be flagged", SpatialSafetyUtils.checkTaskCollision(task, airspaces))
        assertEquals(listOf("Geneva TMA"), SpatialSafetyUtils.taskAirspaceConflicts(task, airspaces))
    }

    @Test
    fun `a waypoint inside the airspace is flagged`() {
        val task = listOf(pt(46.5, 6.5), pt(50.0, 6.5))
        assertTrue(SpatialSafetyUtils.checkTaskCollision(task, listOf(airspace("Geneva TMA", "C"))))
    }

    @Test
    fun `a task clear of the airspace is not flagged`() {
        // Entirely west of the square, no crossing.
        val task = listOf(pt(46.5, 3.0), pt(46.6, 4.0))
        assertFalse(SpatialSafetyUtils.checkTaskCollision(task, listOf(airspace("Geneva TMA", "C"))))
        assertTrue(SpatialSafetyUtils.taskAirspaceConflicts(task, listOf(airspace("Geneva TMA", "C"))).isEmpty())
    }

    @Test
    fun `an uncontrolled class is ignored even when crossed`() {
        val task = listOf(pt(46.5, 5.0), pt(46.5, 8.0)) // crosses the square
        assertFalse(SpatialSafetyUtils.checkTaskCollision(task, listOf(airspace("Glider Sector", "E"))))
    }

    @Test
    fun `a MultiPolygon airspace is honoured`() {
        val task = listOf(pt(46.5, 5.0), pt(46.5, 8.0))
        assertTrue(SpatialSafetyUtils.checkTaskCollision(task, listOf(airspace("Geneva TMA", "C", type = "MultiPolygon"))))
    }

    @Test
    fun `a single point inside is flagged by the point check`() {
        assertTrue(SpatialSafetyUtils.checkAirspaceCollision(pt(46.5, 6.5), listOf(airspace("Geneva TMA", "C"))))
        assertFalse(SpatialSafetyUtils.checkAirspaceCollision(pt(46.5, 3.0), listOf(airspace("Geneva TMA", "C"))))
    }
}
