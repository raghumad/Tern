package com.madanala.tern

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.mapReducer
import com.madanala.tern.route.Route
import com.madanala.tern.utils.RouteCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Integration tests for route creation, persistence, and Redux state management
 * Tests the full flow from user interaction to data persistence
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteIntegrationTest {

    private lateinit var context: Context
    private lateinit var routeCache: RouteCache

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        routeCache = RouteCache(context)
        // Clear any existing cache data
        routeCache.clearCache()
    }

    @After
    fun tearDown() {
        routeCache.clearCache()
    }

    @Test
    fun `route_creation_flow_Redux_action_to_cache_persistence`() = runTest {
        // Initial state
        val initialState = MapState()

        // User creates route via Redux action
        val routeName = "Integration Test Route"
        val action = MapAction.AddRoute(Route(name = routeName))
        val stateWithRoute = mapReducer(initialState, action)

        // Verify Redux state updated
        assertEquals(1, stateWithRoute.routes.size)
        assertEquals(routeName, stateWithRoute.routes.first().name)

        // Persist to cache
        val routeToPersist = stateWithRoute.routes.first()
        routeCache.cacheRoute(routeToPersist)

        // Verify persistence by loading from cache
        val loadedRoutes = routeCache.getAllCachedRoutes()
        assertEquals(1, loadedRoutes.size)
        assertEquals(routeName, loadedRoutes.first().name)
        assertEquals(routeToPersist.id, loadedRoutes.first().id)
    }

    @Test
    fun `waypoint_addition_flow_Redux_to_cache_to_UI_state_sync`() = runTest {
        // Start with empty route
        val route = Route(name = "Waypoint Integration Test")
        val initialState = MapState(routes = listOf(route))

        // Add waypoint via Redux action
        val waypointLat = 40.0
        val waypointLon = -74.0
        val action = MapAction.AddWaypointToRoute(
            routeId = route.id,
            lat = waypointLat,
            lon = waypointLon,
            type = Waypoint.Type.TURNPOINT,
            label = "WP1-1"
        )
        val stateWithWaypoint = mapReducer(initialState, action)

        // Verify Redux state has waypoint
        assertEquals(1, stateWithWaypoint.routes.size)
        val routeWithWaypoint = stateWithWaypoint.routes.first()
        assertEquals(1, routeWithWaypoint.waypoints.size)

        val waypoint = routeWithWaypoint.waypoints.first()
        assertEquals(waypointLat, waypoint.lat, 0.0)
        assertEquals(waypointLon, waypoint.lon, 0.0)
        assertEquals(route.id, waypoint.routeId)

        // Persist updated route
        routeCache.cacheRoute(routeWithWaypoint)

        // Verify persistence includes waypoint
        val loadedRoutes = routeCache.getAllCachedRoutes()
        assertEquals(1, loadedRoutes.size)
        val loadedRoute = loadedRoutes.first()
        assertEquals(1, loadedRoute.waypoints.size)
        assertEquals(waypointLat, loadedRoute.waypoints.first().lat, 0.0)
    }

    @Test
    fun `multi_waypoint_route_with_distance_calculations`() = runTest {
        val initialState = MapState()

        // Create route
        val createRouteAction = MapAction.AddRoute(Route(name = "Distance Test Route"))
        val stateWithRoute = mapReducer(initialState, createRouteAction)
        val route = stateWithRoute.routes.first()

        // Add multiple waypoints
        val waypoints = listOf(
            GeoPoint(40.7128, -74.0060) to "NYC",      // New York City
            GeoPoint(39.9526, -75.1652) to "PHL",      // Philadelphia
            GeoPoint(38.9072, -77.0369) to "DC"        // Washington DC
        )

        var currentState = stateWithRoute
        waypoints.forEachIndexed { index, (geoPoint, label) ->
            val action = MapAction.AddWaypointToRoute(
                routeId = route.id,
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = Waypoint.Type.TURNPOINT,
                label = "WP1-${index + 1}"
            )
            currentState = mapReducer(currentState, action)
        }

        // Verify route has all waypoints
        val finalRoute = currentState.routes.first()
        assertEquals(3, finalRoute.waypoints.size)

        // Verify distance calculations
        assertTrue(finalRoute.totalDistanceKm > 0.0)
        assertTrue(finalRoute.estimatedFlightTimeMinutes > 0)

        // Persist and verify
        routeCache.cacheRoute(finalRoute)
        val loadedRoutes = routeCache.getAllCachedRoutes()
        val loadedRoute = loadedRoutes.first()

        assertEquals(finalRoute.totalDistanceKm, loadedRoute.totalDistanceKm, 0.0)
        assertEquals(finalRoute.estimatedFlightTimeMinutes, loadedRoute.estimatedFlightTimeMinutes)
    }

    @Test
    fun `waypoint_selection_state_management_integration`() = runTest {
        // Create route with waypoint
        val route = Route(name = "Selection Test")
        val waypoint = Waypoint(lat = 40.0, lon = -74.0, type = Waypoint.Type.TURNPOINT, label = "WP1-1", routeId = route.id)
        val routeWithWaypoint = route.addWaypoint(waypoint.lat, waypoint.lon, waypoint.type, waypoint.label)

        val initialState = MapState(routes = listOf(routeWithWaypoint))

        // Select waypoint
        val selectAction = MapAction.SelectWaypoint(route.id, waypoint.id)
        val stateWithSelection = mapReducer(initialState, selectAction)

        // Verify selection state
        assertNotNull(stateWithSelection.selectedWaypoint)
        assertEquals(route.id, stateWithSelection.selectedWaypoint?.routeId)
        assertEquals(waypoint.id, stateWithSelection.selectedWaypoint?.waypointId)

        // Clear selection
        val clearAction = MapAction.DeselectWaypoint
        val stateCleared = mapReducer(stateWithSelection, clearAction)

        // Verify selection cleared
        assertNull(stateCleared.selectedWaypoint)
    }

    @Test
    fun `waypoint_deletion_via_long_press_integration`() = runTest {
        // Create route with multiple waypoints
        val route = Route(name = "Deletion Test")
        val waypoint1 = Waypoint(lat = 40.0, lon = -74.0, type = Waypoint.Type.TURNPOINT, label = "WP1-1", routeId = route.id)
        val waypoint2 = Waypoint(lat = 40.1, lon = -74.1, type = Waypoint.Type.TURNPOINT, label = "WP1-2", routeId = route.id)
        val waypoint3 = Waypoint(lat = 40.2, lon = -74.2, type = Waypoint.Type.TURNPOINT, label = "WP1-3", routeId = route.id)

        var routeWithWaypoints = route
            .addWaypoint(waypoint1.lat, waypoint1.lon, waypoint1.type, waypoint1.label)
            .addWaypoint(waypoint2.lat, waypoint2.lon, waypoint2.type, waypoint2.label)
            .addWaypoint(waypoint3.lat, waypoint3.lon, waypoint3.type, waypoint3.label)

        val initialState = MapState(routes = listOf(routeWithWaypoints))

        // Select the middle waypoint
        val selectAction = MapAction.SelectWaypoint(route.id, waypoint2.id)
        val stateWithSelection = mapReducer(initialState, selectAction)
        assertNotNull(stateWithSelection.selectedWaypoint)
        assertEquals(waypoint2.id, stateWithSelection.selectedWaypoint?.waypointId)

        // Delete the selected waypoint via RemoveWaypoint action
        val deleteAction = MapAction.RemoveWaypoint(route.id, waypoint2.id)
        val stateAfterDeletion = mapReducer(stateWithSelection, deleteAction)

        // Verify waypoint removed from route
        val updatedRoute = stateAfterDeletion.routes.first()
        assertEquals(2, updatedRoute.waypoints.size)
        assertNull(updatedRoute.waypoints.find { it.id == waypoint2.id })

        // Verify selection cleared after deletion
        assertNull(stateAfterDeletion.selectedWaypoint)

        // Verify remaining waypoints still exist
        assertNotNull(updatedRoute.waypoints.find { it.id == waypoint1.id })
        assertNotNull(updatedRoute.waypoints.find { it.id == waypoint3.id })

        // Persist updated route
        routeCache.cacheRoute(updatedRoute)
        val loadedRoutes = routeCache.getAllCachedRoutes()
        assertEquals(1, loadedRoutes.size)
        val loadedRoute = loadedRoutes.first()
        assertEquals(2, loadedRoute.waypoints.size)
        assertNull(loadedRoute.waypoints.find { it.id == waypoint2.id })
    }

    @Test
    fun `route_persistence_and_recovery_across_app_restarts`() = runTest {
        // Simulate app usage: create route, add waypoints, select waypoint
        val initialState = MapState()

        // Create route
        val createAction = MapAction.AddRoute(Route(name = "Persistence Test"))
        val stateWithRoute = mapReducer(initialState, createAction)
        val route = stateWithRoute.routes.first()

        // Add waypoints
        val addWaypointAction = MapAction.AddWaypointToRoute(
            routeId = route.id,
            lat = 40.0,
            lon = -74.0,
            type = Waypoint.Type.TURNPOINT,
            label = "WP1-1"
        )
        val stateWithWaypoint = mapReducer(stateWithRoute, addWaypointAction)

        // Select waypoint
        val finalRoute = stateWithWaypoint.routes.first()
        val selectAction = MapAction.SelectWaypoint(finalRoute.id, finalRoute.waypoints.first().id)
        val finalState = mapReducer(stateWithWaypoint, selectAction)

        // Persist route (selection state is UI-only, not persisted)
        routeCache.cacheRoute(finalRoute)

        // Simulate app restart - load from cache
        val recoveredRoutes = routeCache.getAllCachedRoutes()
        val recoveredRoute = recoveredRoutes.first()

        // Verify route data recovered
        assertEquals("Persistence Test", recoveredRoute.name)
        assertEquals(1, recoveredRoute.waypoints.size)
        assertEquals(finalRoute.totalDistanceKm, recoveredRoute.totalDistanceKm, 0.0)

        // Create new Redux state from recovered data
        val recoveredState = MapState(routes = recoveredRoutes)

        // Verify clean slate for UI state (selection not persisted)
        assertNull(recoveredState.selectedWaypoint)
    }

    @Test
    fun `route_limit_enforcement_integration`() = runTest {
        val initialState = MapState()

        // Create maximum routes (10 routes)
        var currentState = initialState
        val routes = mutableListOf<Route>()

        for (i in 1..10) {
            val route = Route(name = "Route $i")
            routes.add(route)
            val action = MapAction.AddRoute(route)
            currentState = mapReducer(currentState, action)
        }

        // Verify 10 routes allowed
        assertEquals(10, currentState.routes.size)

        // Persist all routes
        routes.forEach { routeCache.cacheRoute(it) }

        // Verify all persisted
        val loadedRoutes = routeCache.getAllCachedRoutes()
        assertEquals(10, loadedRoutes.size)

        // Note: UI would need to enforce the 10-route limit in the UI layer
        // Cache allows more for flexibility, but UI should prevent >10
    }
}
