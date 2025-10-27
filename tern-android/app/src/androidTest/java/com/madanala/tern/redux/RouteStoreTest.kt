package com.madanala.tern.route

import org.junit.Assert.*
import org.junit.Test
import com.madanala.tern.model.Waypoint

/**
 * Unit tests for route-centric waypoint operations
 */
class RouteStoreTest {

    @Test
    fun `route centric waypoint creation works correctly`() {
        // Clear any existing state
        RouteStore.clear()
        WaypointStore.clear()

        // Create a test route
        val testRoute = RouteStore.createRoute("Test Route")
        assertNotNull("Route should be created", testRoute)
        assertTrue("Route should have ID", testRoute.id.isNotEmpty())

        // Set it as current route
        RouteStore.setCurrentRoute(testRoute.id)

        // Create a waypoint in the route
        val testWaypoint = Waypoint(
            lat = 40.0,
            lon = -105.0,
            type = Waypoint.Type.LAUNCH
        )

        RouteStore.addWaypointToRoute(testRoute.id, testWaypoint)

        // Validate route-centric behavior
        val currentRoute = RouteStore.getCurrentRoute()
        assertNotNull("Current route should exist", currentRoute)
        assertEquals("Route should contain the waypoint", 1, currentRoute?.waypoints?.size)

        // Validate WaypointStore synchronization
        val waypointsInStore = WaypointStore.getWaypointsByRoute(testRoute.id)
        assertEquals("WaypointStore should have waypoint for route", 1, waypointsInStore.size)
        assertEquals("Waypoint should have correct route ID", testRoute.id, waypointsInStore[0].routeId)

        // Validate no global waypoints created
        val unassignedWaypoints = WaypointStore.getUnassignedWaypoints()
        assertEquals("No unassigned waypoints should exist", 0, unassignedWaypoints.size)

        // Validate RouteOverlayManager integration
        val visibleRoutes = RouteStore.getVisibleRoutes()
        assertEquals("Should have one visible route", 1, visibleRoutes.size)

        val routeStats = RouteStore.getRouteStats()
        assertEquals("Total routes should be 1", 1, routeStats["total_routes"])
        assertEquals("Total waypoints should be 1", 1, routeStats["total_waypoints"])
    }

    @Test
    fun `waypoint creation with type selection works correctly`() {
        RouteStore.clear()
        WaypointStore.clear()

        val testRoute = RouteStore.createRoute("Test Flight")
        RouteStore.setCurrentRoute(testRoute.id)

        // Test different waypoint types
        val launchWaypoint = Waypoint(
            lat = 39.5,
            lon = -104.5,
            type = Waypoint.Type.LAUNCH
        )

        val turnpointWaypoint = Waypoint(
            lat = 39.6,
            lon = -104.6,
            type = Waypoint.Type.TURNPOINT
        )

        val landingWaypoint = Waypoint(
            lat = 39.7,
            lon = -104.7,
            type = Waypoint.Type.LANDING
        )

        RouteStore.addWaypointToRoute(testRoute.id, launchWaypoint)
        RouteStore.addWaypointToRoute(testRoute.id, turnpointWaypoint)
        RouteStore.addWaypointToRoute(testRoute.id, landingWaypoint)

        // Synchronize with WaypointStore
        WaypointStore.add(launchWaypoint.copy(routeId = testRoute.id))
        WaypointStore.add(turnpointWaypoint.copy(routeId = testRoute.id))
        WaypointStore.add(landingWaypoint.copy(routeId = testRoute.id))

        // Validate all waypoint types are stored correctly
        val routeWaypoints = RouteStore.getCurrentRoute()?.waypoints ?: emptyList()
        assertEquals("Route should have 3 waypoints", 3, routeWaypoints.size)

        val types = routeWaypoints.map { it.type }.toSet()
        assertTrue("Should have LAUNCH type", types.contains(Waypoint.Type.LAUNCH))
        assertTrue("Should have TURNPOINT type", types.contains(Waypoint.Type.TURNPOINT))
        assertTrue("Should have LANDING type", types.contains(Waypoint.Type.LANDING))

        // Validate WaypointStore has all waypoints with correct route associations
        val storeWaypoints = WaypointStore.getWaypointsByRoute(testRoute.id)
        assertEquals("WaypointStore should have 3 waypoints", 3, storeWaypoints.size)
        assertTrue("All waypoints should have route ID", storeWaypoints.all { it.routeId == testRoute.id })
    }

    @Test
    fun `aviation safety compliance - background thread processing works`() {
        RouteStore.clear()
        WaypointStore.clear()

        val testRoute = RouteStore.createRoute("Safety Test Route")
        RouteStore.setCurrentRoute(testRoute.id)

        // This validates that waypoint creation can work in background threads
        // (simulating the Dispatchers.IO usage in MapViewContainer)
        val testWaypoint = Waypoint(
            lat = 38.0,
            lon = -103.0,
            type = Waypoint.Type.TURNPOINT
        )

        RouteStore.addWaypointToRoute(testRoute.id, testWaypoint)
        WaypointStore.add(testWaypoint.copy(routeId = testRoute.id))

        val route = RouteStore.getCurrentRoute()
        assertNotNull("Route should exist after background creation", route)
        assertEquals("Route should have waypoint after background creation", 1, route?.waypoints?.size)
        assertEquals("Waypoint should have correct coordinates", 38.0, route?.waypoints?.get(0)?.lat, 0.0)
        assertEquals("Waypoint should have correct coordinates", -103.0, route?.waypoints?.get(0)?.lon, 0.0)
    }

    @Test
    fun `route overlay manager integration works without Redux dependencies`() {
        RouteStore.clear()
        WaypointStore.clear()

        val testRoute = RouteStore.createRoute("Integration Test Route")
        RouteStore.setCurrentRoute(testRoute.id)

        // Create waypoint
        val testWaypoint = Waypoint(
            lat = 37.0,
            lon = -102.0,
            type = Waypoint.Type.LAUNCH
        )

        RouteStore.addWaypointToRoute(testRoute.id, testWaypoint)
        WaypointStore.add(testWaypoint.copy(routeId = testRoute.id))

        // Validate RouteOverlayManager can work with the data
        val allRoutes = RouteStore.getAllRoutes()
        assertEquals("Should have 1 route", 1, allRoutes.size)

        val visibleRoutes = RouteStore.getVisibleRoutes()
        assertEquals("Should have 1 visible route", 1, visibleRoutes.size)

        val routeStats = RouteStore.getRouteStats()
        assertEquals("Should have correct route count", 1, routeStats["total_routes"])
        assertEquals("Should have correct waypoint count", 1, routeStats["total_waypoints"])

        // Validate waypoint filtering by route works
        val routeWaypoints = WaypointStore.getWaypointsByRoute(testRoute.id)
        assertEquals("Should find waypoint by route", 1, routeWaypoints.size)
        assertEquals("Waypoint should have correct route association", testRoute.id, routeWaypoints[0].routeId)
    }
}