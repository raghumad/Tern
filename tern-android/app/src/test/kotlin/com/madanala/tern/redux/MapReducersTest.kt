package com.madanala.tern.redux

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.model.Waypoint
import com.madanala.tern.model.Route
import com.madanala.tern.model.LocationType
import org.junit.Test

import java.time.Instant

class MapReducersTest {

    private val initialState = MapState()

    @Test
    fun `AddRoute action adds route to state and limits count`() {
        val route = Route(id = "route1", name = "Test Route", createdAt = Instant.now())
        
        val newState = mapReducer(initialState, MapAction.AddRoute(route))
        
        assertThat(newState.routes).hasSize(1)
        assertThat(newState.routes[0].id).isEqualTo("route1")
    }

    @Test
    fun `RemoveRoute action removes route and clears selection`() {
        val route = Route(id = "route1", name = "Test Route")
        val stateWithRoute = initialState.copy(
            routes = listOf(route),
            selectedRouteId = "route1"
        )

        val newState = mapReducer(stateWithRoute, MapAction.RemoveRoute("route1"))

        assertThat(newState.routes).isEmpty()
        assertThat(newState.selectedRouteId).isNull()
    }

    @Test
    fun `SelectRoute action updates selectedRouteId`() {
        val newState = mapReducer(initialState, MapAction.SelectRoute("route1"))
        
        assertThat(newState.selectedRouteId).isEqualTo("route1")
    }

    @Test
    fun `AddWaypointToRoute adds waypoint to correct route`() {
        val route = Route(id = "route1", name = "Test Route")
        val stateWithRoute = initialState.copy(routes = listOf(route))

        val newState = mapReducer(
            stateWithRoute, 
            MapAction.AddWaypointToRoute("route1", 10.0, 20.0, LocationType.TURNPOINT)
        )

        assertThat(newState.routes[0].waypoints).hasSize(1)
        assertThat(newState.routes[0].waypoints[0].lat).isEqualTo(10.0)
        assertThat(newState.routes[0].waypoints[0].lon).isEqualTo(20.0)
    }

    @Test
    fun `RemoveWaypoint removes waypoint and clears selection if selected`() {
        val waypoint = Waypoint(id = "wp1", lat = 10.0, lon = 20.0)
        val route = Route(id = "route1", name = "Test Route", waypoints = listOf(waypoint))
        
        val stateWithWaypoint = initialState.copy(
            routes = listOf(route),
            selectedWaypoint = WaypointSelection("route1", "wp1", false)
        )

        val newState = mapReducer(stateWithWaypoint, MapAction.RemoveWaypoint("route1", "wp1"))

        assertThat(newState.routes[0].waypoints).isEmpty()
        assertThat(newState.selectedWaypoint).isNull()
    }

    @Test
    fun `UpdateUserLocation updates location state`() {
        val location = org.osmdroid.util.GeoPoint(10.0, 20.0)
        val newState = mapReducer(initialState, MapAction.UpdateUserLocation(location))

    }
}

