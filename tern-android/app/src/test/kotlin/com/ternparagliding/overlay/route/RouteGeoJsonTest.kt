package com.ternparagliding.overlay.route

import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.priority.OverlayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeoJsonTest {

    // -- Fixtures ---------------------------------------------------------

    private fun waypoint(
        lat: Double,
        lon: Double,
        type: LocationType = LocationType.TURNPOINT,
        label: String? = null,
        id: String = "wp-${lat.hashCode()}-${lon.hashCode()}",
    ) = Waypoint(id = id, lat = lat, lon = lon, type = type, label = label)

    private val aravisRoute = Route(
        id = "route-aravis",
        name = "Aravis XC",
        waypoints = listOf(
            waypoint(45.86, 6.48, LocationType.LAUNCH, "Planfait", id = "wp-launch"),
            waypoint(45.82, 6.44, LocationType.SSS, "SSS Ring", id = "wp-sss"),
            waypoint(45.75, 6.35, LocationType.TURNPOINT, "La Clusaz", id = "wp-tp"),
            waypoint(45.70, 6.30, LocationType.ESS, "ESS Ring", id = "wp-ess"),
            waypoint(45.68, 6.28, LocationType.GOAL, "Goal Field", id = "wp-goal"),
        ),
    )

    private val singleWpRoute = Route(
        id = "route-single",
        name = "Single WP",
        waypoints = listOf(
            waypoint(46.0, 7.0, LocationType.LAUNCH, "Solo"),
        ),
    )

    private val emptyRoute = Route(id = "route-empty", name = "Empty", waypoints = emptyList())

    private val hiddenRoute = aravisRoute.copy(id = "route-hidden", isVisible = false)

    // -- routeLines -------------------------------------------------------

    @Test
    fun `routeLines produces one LineString per visible multi-waypoint route`() {
        val fc = RouteGeoJson.routeLines(listOf(aravisRoute, singleWpRoute, emptyRoute))
        assertEquals("only aravisRoute has >= 2 waypoints", 1, fc.features.size)

        val lineFeature = fc.features.first()
        val coords = lineFeature.geometry.coordinates
        assertEquals(5, coords.size)
        // First coordinate is lon,lat of the first waypoint.
        assertEquals(6.48, coords[0].longitude, 1e-9)
        assertEquals(45.86, coords[0].latitude, 1e-9)
    }

    @Test
    fun `routeLines skips hidden routes`() {
        val fc = RouteGeoJson.routeLines(listOf(hiddenRoute))
        assertTrue(fc.features.isEmpty())
    }

    @Test
    fun `routeLines empty input produces empty collection`() {
        val fc = RouteGeoJson.routeLines(emptyList())
        assertTrue(fc.features.isEmpty())
    }

    @Test
    fun `routeLines feature carries routeId property`() {
        val fc = RouteGeoJson.routeLines(listOf(aravisRoute))
        val props = fc.features.first().properties
        assertEquals("route-aravis", props["routeId"]?.toString()?.trim('"'))
    }

    // -- waypointPoints ---------------------------------------------------

    @Test
    fun `waypointPoints produces one Point per waypoint in visible routes`() {
        val fc = RouteGeoJson.waypointPoints(listOf(aravisRoute))
        assertEquals(5, fc.features.size)
    }

    @Test
    fun `waypointPoints skips hidden routes`() {
        val fc = RouteGeoJson.waypointPoints(listOf(hiddenRoute))
        assertTrue(fc.features.isEmpty())
    }

    @Test
    fun `waypointPoints single-waypoint routes produce one feature`() {
        val fc = RouteGeoJson.waypointPoints(listOf(singleWpRoute))
        assertEquals(1, fc.features.size)
    }

    @Test
    fun `waypointFeature carries name, type, label, waypointId, routeId`() {
        val wp = waypoint(45.86, 6.48, LocationType.LAUNCH, "Planfait", id = "wp-1")
        val feature = RouteGeoJson.waypointFeature(wp, 0, "route-1")

        val props = feature.properties
        assertEquals("\"Planfait\"", props["name"].toString())
        assertEquals("\"LAUNCH\"", props["type"].toString())
        assertEquals("\"Planfait\\nLAUNCH\"", props["label"].toString())
        assertEquals("\"wp-1\"", props["waypointId"].toString())
        assertEquals("\"route-1\"", props["routeId"].toString())
    }

    @Test
    fun `waypointFeature defaults label to WP index when label is null`() {
        val wp = waypoint(45.0, 6.0, LocationType.TURNPOINT, label = null, id = "wp-x")
        val feature = RouteGeoJson.waypointFeature(wp, 2, "r")
        val props = feature.properties
        assertEquals("\"WP 3\"", props["name"].toString())
    }

    @Test
    fun `waypointFeature point coordinates are lon,lat`() {
        val wp = waypoint(45.86, 6.48, LocationType.LAUNCH, "Planfait", id = "wp-1")
        val feature = RouteGeoJson.waypointFeature(wp, 0, "route-1")
        assertEquals(6.48, feature.geometry.longitude, 1e-9)
        assertEquals(45.86, feature.geometry.latitude, 1e-9)
    }

    // -- waypointCandidates -----------------------------------------------

    @Test
    fun `waypointCandidates wraps each waypoint as ROUTE_WAYPOINT candidate`() {
        val candidates = RouteGeoJson.waypointCandidates(listOf(aravisRoute))
        assertEquals(5, candidates.size)
        assertTrue(candidates.all { it.kind == OverlayKind.ROUTE_WAYPOINT })
    }

    @Test
    fun `waypointCandidates skips hidden routes`() {
        val candidates = RouteGeoJson.waypointCandidates(listOf(hiddenRoute))
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `waypointCandidates position matches waypoint coordinates`() {
        val candidates = RouteGeoJson.waypointCandidates(listOf(singleWpRoute))
        val c = candidates.single()
        assertEquals(46.0, c.position.latitudeDeg, 1e-9)
        assertEquals(7.0, c.position.longitudeDeg, 1e-9)
    }

    @Test
    fun `waypointCandidates preserves original waypoint reference`() {
        val candidates = RouteGeoJson.waypointCandidates(listOf(aravisRoute))
        assertEquals(aravisRoute.waypoints[0], candidates[0].waypoint)
    }

    // -- Multiple routes --------------------------------------------------

    @Test
    fun `multiple visible routes produce combined waypoint set`() {
        val route2 = Route(
            id = "route-2",
            name = "Route 2",
            waypoints = listOf(
                waypoint(46.0, 7.0, LocationType.LAUNCH, "Start2", id = "wp-2-1"),
                waypoint(46.1, 7.1, LocationType.GOAL, "End2", id = "wp-2-2"),
            ),
        )
        val fc = RouteGeoJson.waypointPoints(listOf(aravisRoute, route2))
        assertEquals(7, fc.features.size) // 5 + 2
    }

    @Test
    fun `multiple routes produce multiple line features`() {
        val route2 = Route(
            id = "route-2",
            name = "Route 2",
            waypoints = listOf(
                waypoint(46.0, 7.0, LocationType.LAUNCH, "Start2", id = "wp-2-1"),
                waypoint(46.1, 7.1, LocationType.GOAL, "End2", id = "wp-2-2"),
            ),
        )
        val fc = RouteGeoJson.routeLines(listOf(aravisRoute, route2))
        assertEquals(2, fc.features.size)
    }
}
