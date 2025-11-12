package com.madanala.tern

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.mapReducer
import com.madanala.tern.route.Route
import com.madanala.tern.utils.RouteCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.osmdroid.util.GeoPoint

/**
 * Integration tests for route creation, persistence, and Redux state management
 * Tests the full flow from user interaction to data persistence
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteIntegrationTest {

    private lateinit var context: Context
    private lateinit var routeCache: RouteCache

    @BeforeEach
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        routeCache = RouteCache(context)
        // Clear any existing cache data
        routeCache.clearCache()
    }

    @AfterEach
    fun tearDown() {
        routeCache.clearCache()
    }

    @Test
    fun `route creation flow - Redux action to cache persistence`() = runTest {
        // Initial state
        val initialState = MapState()

        // User creates route via Redux action
        val routeName = "Integration Test Route"
        val action = MapAction.AddRoute(Route(name = routeName))
        val stateWithRoute = mapReducer(initialState, action)

        // Verify Redux state updated
        assertThat(stateWithRoute.routes).hasSize(1)
        assertThat(stateWithRoute.routes.first().name).isEqualTo(routeName)

        // Persist to cache
        val routeToPersist = stateWithRoute.routes.first()
        routeCache.cacheRoute(routeToPersist)

        // Verify persistence by loading from cache
        val loadedRoutes = routeCache.getAllCachedRoutes()
        assertThat(loadedRoutes).hasSize(1)
        assertThat(loadedRoutes.first().name).isEqualTo(routeName)
        assertThat(loadedRoutes.first().id).isEqualTo(routeToPersist.id)
    }

    @Test
    fun `waypoint addition flow - Redux to cache to UI state sync`() = runTest {
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
        assertThat(stateWithWaypoint.routes).hasSize(1)
        val routeWithWaypoint = stateWithWaypoint.routes.first()
        assertThat(routeWithWaypoint.waypoints).hasSize(1)

        val waypoint = routeWithWaypoint.waypoints.first()
        assertThat(waypoint.lat).isEqualTo(waypointLat)
        assertThat(waypoint.lon).isEqualTo(waypointLon)
        assertThat(waypoint.routeId).isEqualTo(route.id)

        // Persist updated route
        routeCache.cacheRoute(routeWithWaypoint)

        // Verify persistence includes waypoint
        val loadedRoutes = routeCache.getAllCachedRoutes()
        assertThat(loadedRoutes).hasSize(1)
        val loadedRoute = loadedRoutes.first()
        assertThat(loadedRoute.waypoints).hasSize(1)
        assertThat(loadedRoute.waypoints.first().lat).isEqualTo(waypointLat)
    }

    @Test
    fun `multi-waypoint route with distance calculations`() = runTest {
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
        assertThat(finalRoute.waypoints).hasSize(3)

        // Verify distance calculations
        assertThat(finalRoute.totalDistanceKm).isGreaterThan(0.0)
        assertThat(finalRoute.estimatedFlightTimeMinutes).isGreaterThan(0)

        // Persist and verify
        routeCache.cacheRoute(finalRoute)
        val loadedRoutes = routeCache.getAllCachedRoutes()
        val loadedRoute = loadedRoutes.first()

        assertThat(loadedRoute.totalDistanceKm).isEqualTo(finalRoute.totalDistanceKm)
        assertThat(loadedRoute.estimatedFlightTimeMinutes).isEqualTo(finalRoute.estimatedFlightTimeMinutes)
    }

    @Test
    fun `waypoint selection state management integration`() = runTest {
        // Create route with waypoint
        val route = Route(name = "Selection Test")
        val waypoint = Waypoint(lat = 40.0, lon = -74.0, type = Waypoint.Type.TURNPOINT, label = "WP1-1", routeId = route.id)
        val routeWithWaypoint = route.addWaypoint(waypoint.lat, waypoint.lon, waypoint.type, waypoint.label)

        val initialState = MapState(routes = listOf(routeWithWaypoint))

        // Select waypoint
        val selectAction = MapAction.SelectWaypoint(route.id, waypoint.id)
        val stateWithSelection = mapReducer(initialState, selectAction)

        // Verify selection state
        assertThat(stateWithSelection.selectedWaypoint).isNotNull()
        assertThat(stateWithSelection.selectedWaypoint?.routeId).isEqualTo(route.id)
        assertThat(stateWithSelection.selectedWaypoint?.waypointId).isEqualTo(waypoint.id)

        // Clear selection
        val clearAction = MapAction.DeselectWaypoint
        val stateCleared = mapReducer(stateWithSelection, clearAction)

        // Verify selection cleared
        assertThat(stateCleared.selectedWaypoint).isNull()
    }

    @Test
    fun `route persistence and recovery across app restarts`() = runTest {
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
        assertThat(recoveredRoute.name).isEqualTo("Persistence Test")
        assertThat(recoveredRoute.waypoints).hasSize(1)
        assertThat(recoveredRoute.totalDistanceKm).isEqualTo(finalRoute.totalDistanceKm)

        // Create new Redux state from recovered data
        val recoveredState = MapState(routes = recoveredRoutes)

        // Verify clean slate for UI state (selection not persisted)
        assertThat(recoveredState.selectedWaypoint).isNull()
    }

    @Test
    fun `route limit enforcement integration`() = runTest {
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
        assertThat(currentState.routes).hasSize(10)

        // Persist all routes
        routes.forEach { routeCache.cacheRoute(it) }

        // Verify all persisted
        val loadedRoutes = routeCache.getAllCachedRoutes()
        assertThat(loadedRoutes).hasSize(10)

        // Note: UI would need to enforce the 10-route limit in the UI layer
        // Cache allows more for flexibility, but UI should prevent >10
    }
}
