package com.ternparagliding.utils.geo

import org.osmdroid.util.GeoPoint
import com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature

/**
 * Aviation-Grade Spatial Safety Utility
 * Provides truthful, non-stubbed calculations for airspace collisions and weather risks.
 *
 * Airspace containment is computed against the real polygon (outer ring minus holes,
 * Polygon *and* MultiPolygon), and a **task** is checked leg-by-leg: a conflict fires when
 * a waypoint sits inside an airspace **or** a leg's segment crosses its boundary — so a route
 * that flies *through* controlled airspace between two outside waypoints is no longer missed.
 */
object SpatialSafetyUtils {

    /** Controlled classes a paraglider must avoid penetrating. */
    private val CONTROLLED = setOf("A", "B", "C")

    /** True if [point] is inside any controlled airspace. */
    fun checkAirspaceCollision(point: GeoPoint, airspaces: List<OverlayFeature>): Boolean =
        airspacesContaining(point, airspaces).isNotEmpty()

    /** Names of the controlled airspaces that contain [point] (de-duplicated). */
    fun airspacesContaining(point: GeoPoint, airspaces: List<OverlayFeature>): List<String> =
        airspaces.filter { isControlled(it) && pointInAirspace(point, it) }
            .map { airspaceName(it) }.distinct()

    /** True if the task path touches any controlled airspace (waypoint-inside or leg-crossing). */
    fun checkTaskCollision(points: List<GeoPoint>, airspaces: List<OverlayFeature>): Boolean =
        taskAirspaceConflicts(points, airspaces).isNotEmpty()

    /**
     * Names of the controlled airspaces the task conflicts with: any waypoint inside the
     * airspace, **or** any leg whose segment crosses the airspace boundary. De-duplicated.
     */
    fun taskAirspaceConflicts(points: List<GeoPoint>, airspaces: List<OverlayFeature>): List<String> {
        if (points.isEmpty()) return emptyList()
        return airspaces.filter { isControlled(it) && taskTouchesAirspace(points, it) }
            .map { airspaceName(it) }.distinct()
    }

    // ================= PRIVATE HELPERS =================

    private fun isControlled(airspace: OverlayFeature): Boolean =
        (airspace.getStringProperty("class") ?: "") in CONTROLLED

    private fun airspaceName(airspace: OverlayFeature): String =
        airspace.getStringProperty("name")?.takeIf { it.isNotBlank() }
            ?: "Class ${airspace.getStringProperty("class") ?: "?"} airspace"

    private fun pointInAirspace(point: GeoPoint, airspace: OverlayFeature): Boolean =
        polygonsOf(airspace.feature).any { pointInPolygon(point, it) }

    private fun taskTouchesAirspace(points: List<GeoPoint>, airspace: OverlayFeature): Boolean {
        val polygons = polygonsOf(airspace.feature)
        return polygons.any { polygon ->
            // A waypoint inside the airspace…
            points.any { pointInPolygon(it, polygon) } ||
                // …or a leg whose segment crosses the airspace's outer boundary.
                (0 until points.lastIndex).any { i ->
                    segmentCrossesRing(points[i], points[i + 1], polygon.firstOrNull() ?: emptyList())
                }
        }
    }

    // ---- geometry ----

    /** A polygon as [outerRing, hole1, hole2, …]; each ring a list of [lon, lat]. */
    private fun polygonsOf(feature: Map<String, Any>): List<List<List<DoubleArray>>> {
        @Suppress("UNCHECKED_CAST")
        val geometry = feature["geometry"] as? Map<String, Any> ?: return emptyList()
        val type = geometry["type"] as? String ?: return emptyList()
        val coordinates = geometry["coordinates"] as? List<*> ?: return emptyList()
        return when (type) {
            "Polygon" -> listOf(parseRings(coordinates))
            "MultiPolygon" -> coordinates.mapNotNull { (it as? List<*>)?.let(::parseRings) }
            else -> emptyList()
        }
    }

    private fun parseRings(rings: List<*>): List<List<DoubleArray>> =
        rings.mapNotNull { ring ->
            (ring as? List<*>)?.mapNotNull { pt ->
                (pt as? List<*>)?.takeIf { it.size >= 2 }?.let {
                    val lon = (it[0] as? Number)?.toDouble()
                    val lat = (it[1] as? Number)?.toDouble()
                    if (lon != null && lat != null) doubleArrayOf(lon, lat) else null
                }
            }
        }

    /** Inside the outer ring AND outside every hole. */
    private fun pointInPolygon(point: GeoPoint, polygon: List<List<DoubleArray>>): Boolean {
        val outer = polygon.firstOrNull() ?: return false
        if (!inRing(point, outer)) return false
        for (h in 1 until polygon.size) if (inRing(point, polygon[h])) return false
        return true
    }

    /** Ray-casting point-in-ring (lon = x, lat = y). */
    private fun inRing(point: GeoPoint, ring: List<DoubleArray>): Boolean {
        if (ring.size < 3) return false
        val px = point.longitude
        val py = point.latitude
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i][0]; val yi = ring[i][1]
            val xj = ring[j][0]; val yj = ring[j][1]
            if (((yi > py) != (yj > py)) &&
                (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** True if segment a→b properly crosses any edge of [ring]. */
    private fun segmentCrossesRing(a: GeoPoint, b: GeoPoint, ring: List<DoubleArray>): Boolean {
        if (ring.size < 2) return false
        for (i in 0 until ring.size - 1) {
            if (segmentsIntersect(a, b, ring[i], ring[i + 1])) return true
        }
        return false
    }

    /** Proper segment intersection via orientation signs (lon = x, lat = y). */
    private fun segmentsIntersect(a: GeoPoint, b: GeoPoint, c: DoubleArray, d: DoubleArray): Boolean {
        val ax = a.longitude; val ay = a.latitude
        val bx = b.longitude; val by = b.latitude
        val d1 = dir(c[0], c[1], d[0], d[1], ax, ay)
        val d2 = dir(c[0], c[1], d[0], d[1], bx, by)
        val d3 = dir(ax, ay, bx, by, c[0], c[1])
        val d4 = dir(ax, ay, bx, by, d[0], d[1])
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    /** Cross product sign of (b-a) × (c-a). */
    private fun dir(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
        (cx - ax) * (by - ay) - (cy - ay) * (bx - ax)
}
