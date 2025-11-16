package com.madanala.tern.redux

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.model.FlightMode
import com.madanala.tern.model.Waypoint
import com.madanala.tern.route.Route
import org.junit.jupiter.api.Test
import org.osmdroid.util.GeoPoint

/**
 * Unit tests for Redux reducers
 * Tests state immutability and action processing
 */
class MapReducersTest {

    @Test
    fun `mapReducer handles UpdateUserLocation action correctly`() {
        val initialState = MapState()
        val newLocation = GeoPoint(40.0, -74.0)

        val action = MapAction.UpdateUserLocation(newLocation)
        val newState = mapReducer(initialState, action)

        assertThat(newState.userLocation).isEqualTo(newLocation)
        assertThat(initialState.userLocation).isNull() // Original state unchanged
    }

    @Test
    fun `mapReducer handles SetFlightMode action correctly`() {
        val initialState = MapState(currentFlightMode = FlightMode.GROUND)
        val newFlightMode = FlightMode.FLIGHT

        val action = MapAction.SetFlightMode(newFlightMode)
        val newState = mapReducer(initialState, action)

        assertThat(newState.currentFlightMode).isEqualTo(newFlightMode)
        assertThat(initialState.currentFlightMode).isEqualTo(FlightMode.GROUND) // Original unchanged
    }

    @Test
    fun `mapReducer handles AddRoute action correctly`() {
        val initialState = MapState(routes = emptyList())
        val newRoute = Route(name = "Test Route")

        val action = MapAction.AddRoute(newRoute)
        val newState = mapReducer(initialState, action)

        assertThat(newState.routes).hasSize(1)
        assertThat(newState.routes.first()).isEqualTo(newRoute)
        assertThat(initialState.routes).isEmpty() // Original unchanged
    }

    @Test
    fun `mapReducer handles SelectWaypoint action correctly`() {
        val initialState = MapState(selectedWaypoint = null)
        val route = Route(name = "Test Route")
        val routeWithWaypoint = route.addWaypoint(40.0, -74.0, Waypoint.Type.TURNPOINT, "WP1-1")
        val waypoint = routeWithWaypoint.waypoints.first()

        val action = MapAction.SelectWaypoint(routeWithWaypoint.id, waypoint.id)
        val newState = mapReducer(initialState, action)

        assertThat(newState.selectedWaypoint).isNotNull()
        assertThat(newState.selectedWaypoint?.routeId).isEqualTo(routeWithWaypoint.id)
        assertThat(newState.selectedWaypoint?.waypointId).isEqualTo(waypoint.id)
        assertThat(initialState.selectedWaypoint).isNull() // Original unchanged
    }

    @Test
    fun `mapReducer handles DeselectWaypoint action correctly`() {
        val route = Route(name = "Test Route")
        val waypoint = Waypoint(lat = 40.0, lon = -74.0, type = Waypoint.Type.TURNPOINT, label = "WP1-1", routeId = route.id)
        val initialState = MapState(selectedWaypoint = WaypointSelection(route.id, waypoint.id))

        val action = MapAction.DeselectWaypoint
        val newState = mapReducer(initialState, action)

        assertThat(newState.selectedWaypoint).isNull()
        assertThat(initialState.selectedWaypoint).isNotNull() // Original unchanged
    }

    @Test
    fun `mapReducer handles UpdateUserPreferences action correctly`() {
        val initialState = MapState(userPreferences = UserPreferencesState(handedness = Handedness.RIGHT_HANDED))
        val newPreferences = UserPreferencesState(handedness = Handedness.LEFT_HANDED)

        val action = MapAction.UpdateUserPreferences(newPreferences)
        val newState = mapReducer(initialState, action)

        assertThat(newState.userPreferences.handedness).isEqualTo(Handedness.LEFT_HANDED)
        assertThat(initialState.userPreferences.handedness).isEqualTo(Handedness.RIGHT_HANDED) // Original unchanged
    }

    @Test
    fun `mapReducer handles UpdateWaypointType action correctly`() {
        val route = Route(name = "Test Route")
        val routeWithWaypoint = route.addWaypoint(40.0, -74.0, Waypoint.Type.TURNPOINT, "WP1-1")
        val waypoint = routeWithWaypoint.waypoints.first()

        val initialState = MapState(routes = listOf(routeWithWaypoint))
        val newType = Waypoint.Type.LANDING

        val action = MapAction.UpdateWaypointType(routeWithWaypoint.id, waypoint.id, newType)
        val newState = mapReducer(initialState, action)

        // Verify waypoint type was updated
        val updatedRoute = newState.routes.first()
        val updatedWaypoint = updatedRoute.waypoints.first()
        assertThat(updatedWaypoint.type).isEqualTo(newType)

        // Verify original state unchanged
        val originalRoute = initialState.routes.first()
        val originalWaypoint = originalRoute.waypoints.first()
        assertThat(originalWaypoint.type).isEqualTo(Waypoint.Type.TURNPOINT)
    }

    @Test
    fun `mapReducer maintains state immutability for complex updates`() {
        val initialRoutes = listOf(Route(name = "Route 1"), Route(name = "Route 2"))
        val initialState = MapState(
            routes = initialRoutes,
            userLocation = GeoPoint(40.0, -74.0),
            currentFlightMode = FlightMode.GROUND
        )

        // Perform multiple actions
        val stateAfterLocation = mapReducer(initialState, MapAction.UpdateUserLocation(GeoPoint(41.0, -75.0)))
        val stateAfterFlightMode = mapReducer(stateAfterLocation, MapAction.SetFlightMode(FlightMode.FLIGHT))
        val stateAfterRouteAdd = mapReducer(stateAfterFlightMode, MapAction.AddRoute(Route(name = "Route 3")))

        // Verify final state
        assertThat(stateAfterRouteAdd.userLocation?.latitude).isEqualTo(41.0)
        assertThat(stateAfterRouteAdd.currentFlightMode).isEqualTo(FlightMode.FLIGHT)
        assertThat(stateAfterRouteAdd.routes).hasSize(3)

        // Verify original state unchanged
        assertThat(initialState.userLocation?.latitude).isEqualTo(40.0)
        assertThat(initialState.currentFlightMode).isEqualTo(FlightMode.GROUND)
        assertThat(initialState.routes).hasSize(2)
    }

    // Route Management Tests - Phase 7.3

    @Test
    fun `mapReducer handles SelectRoute action correctly`() {
        val initialState = MapState(selectedRouteId = null)
        val routeId = "test-route-123"

        val action = MapAction.SelectRoute(routeId)
        val newState = mapReducer(initialState, action)

        assertThat(newState.selectedRouteId).isEqualTo(routeId)
        assertThat(initialState.selectedRouteId).isNull() // Original unchanged
    }

    @Test
    fun `mapReducer handles DeselectRoute action correctly`() {
        val initialState = MapState(selectedRouteId = "test-route-123")

        val action = MapAction.DeselectRoute
        val newState = mapReducer(initialState, action)

        assertThat(newState.selectedRouteId).isNull()
        assertThat(initialState.selectedRouteId).isEqualTo("test-route-123") // Original unchanged
    }

    @Test
    fun `mapReducer handles RemoveRoute action correctly and clears selections`() {
        val routeToRemove = Route(id = "route-1", name = "Route 1")
        val routeToKeep = Route(id = "route-2", name = "Route 2")
        val initialState = MapState(
            routes = listOf(routeToRemove, routeToKeep),
            selectedRouteId = "route-1" // Selected route being removed
        )

        val action = MapAction.RemoveRoute("route-1")
        val newState = mapReducer(initialState, action)

        assertThat(newState.routes).hasSize(1)
        assertThat(newState.routes.first()).isEqualTo(routeToKeep)
        assertThat(newState.selectedRouteId).isNull() // Selection cleared
        assertThat(initialState.routes).hasSize(2) // Original unchanged
        assertThat(initialState.selectedRouteId).isEqualTo("route-1")
    }

    @Test
    fun `mapReducer handles RemoveRoute action when removing non-selected route`() {
        val routeToRemove = Route(id = "route-1", name = "Route 1")
        val routeToKeep = Route(id = "route-2", name = "Route 2")
        val initialState = MapState(
            routes = listOf(routeToRemove, routeToKeep),
            selectedRouteId = "route-2" // Different route selected
        )

        val action = MapAction.RemoveRoute("route-1")
        val newState = mapReducer(initialState, action)

        assertThat(newState.routes).hasSize(1)
        assertThat(newState.routes.first()).isEqualTo(routeToKeep)
        assertThat(newState.selectedRouteId).isEqualTo("route-2") // Selection preserved
    }

    @Test
    fun `mapReducer handles UpdateRoute action correctly with route renaming`() {
        val originalRoute = Route(id = "route-1", name = "Old Name")
        val updatedRoute = originalRoute.copy(name = "New Name")
        val initialState = MapState(routes = listOf(originalRoute))

        val action = MapAction.UpdateRoute(updatedRoute)
        val newState = mapReducer(initialState, action)

        assertThat(newState.routes).hasSize(1)
        assertThat(newState.routes.first().name).isEqualTo("New Name")
        assertThat(initialState.routes.first().name).isEqualTo("Old Name") // Original unchanged
    }

    @Test
    fun `mapReducer handles UpdateRoute action and preserves waypoint selections`() {
        val route = Route(id = "route-1", name = "Test Route")
        val routeWithWaypoint = route.addWaypoint(40.0, -74.0, Waypoint.Type.TURNPOINT, "WP1-1")
        val waypoint = routeWithWaypoint.waypoints.first()
        val updatedRoute = routeWithWaypoint.copy(name = "Updated Route")

        val initialState = MapState(
            routes = listOf(routeWithWaypoint),
            selectedWaypoint = WaypointSelection(routeWithWaypoint.id, waypoint.id)
        )

        val action = MapAction.UpdateRoute(updatedRoute)
        val newState = mapReducer(initialState, action)

        assertThat(newState.routes.first().name).isEqualTo("Updated Route")
        assertThat(newState.selectedWaypoint?.routeId).isEqualTo(routeWithWaypoint.id) // Selection preserved
        assertThat(newState.selectedWaypoint?.waypointId).isEqualTo(waypoint.id)
    }

    @Test
    fun `mapReducer handles ClearAllRoutes action correctly`() {
        val routes = listOf(
            Route(name = "Route 1"),
            Route(name = "Route 2"),
            Route(name = "Route 3")
        )
        val initialState = MapState(
            routes = routes,
            selectedRouteId = "route-2",
            selectedWaypoint = WaypointSelection("route-1", "wp-1")
        )

        val action = MapAction.ClearAllRoutes
        val newState = mapReducer(initialState, action)

        assertThat(newState.routes).isEmpty()
        assertThat(newState.selectedRouteId).isNull() // Selection cleared
        assertThat(newState.selectedWaypoint).isNull() // Waypoint selection cleared
        assertThat(initialState.routes).isNotEmpty() // Original unchanged
    }
}
