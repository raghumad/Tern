package com.madanala.tern

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.model.Waypoint
import com.madanala.tern.model.Route
import org.junit.jupiter.api.Test

/**
 * Unit tests for Route business logic
 * Tests distance calculations, waypoint management, and route metrics
 */
class RouteTest {

    @Test
    fun `route with no waypoints has zero distance and time`() {
        val route = Route(name = "Empty Route")

        assertThat(route.totalDistanceKm).isEqualTo(0.0)
        assertThat(route.estimatedFlightTimeMinutes).isEqualTo(0)
    }

    @Test
    fun `route with single waypoint has zero distance and time`() {
        val route = Route(
            name = "Single Waypoint Route",
            waypoints = listOf(
                Waypoint(
                    lat = 40.0,
                    lon = -74.0,
                    type = LocationType.TURNPOINT,
                    label = "WP1-1"
                )
            )
        )

        assertThat(route.totalDistanceKm).isEqualTo(0.0)
        assertThat(route.estimatedFlightTimeMinutes).isEqualTo(0)
    }

    @Test
    fun `route with two waypoints calculates distance and time correctly`() {
        // New York to Los Angeles approximate coordinates
        val nyc = Waypoint(lat = 40.7128, lon = -74.0060, type = LocationType.TURNPOINT, label = "NYC")
        val lax = Waypoint(lat = 33.9416, lon = -118.4085, type = LocationType.TURNPOINT, label = "LAX")

        val route = Route.fromWaypoints("Cross Country", listOf(nyc, lax))

        // Should have non-zero distance and time
        assertThat(route.totalDistanceKm).isGreaterThan(3900.0) // NYC to LAX is ~3930km
        assertThat(route.totalDistanceKm).isLessThan(4000.0)
        assertThat(route.estimatedFlightTimeMinutes).isGreaterThan(0)
    }

    @Test
    fun `adding waypoint updates route metrics`() {
        val route = Route(name = "Test Route")
        val waypoint1 = Waypoint(lat = 0.0, lon = 0.0, type = LocationType.TURNPOINT, label = "Start")
        val waypoint2 = Waypoint(lat = 1.0, lon = 1.0, type = LocationType.TURNPOINT, label = "End")

        val routeWithOne = route.addWaypoint(waypoint1.lat, waypoint1.lon, waypoint1.type, waypoint1.label)
        assertThat(routeWithOne.totalDistanceKm).isEqualTo(0.0) // Single waypoint

        val routeWithTwo = routeWithOne.addWaypoint(waypoint2.lat, waypoint2.lon, waypoint2.type, waypoint2.label)
        assertThat(routeWithTwo.totalDistanceKm).isGreaterThan(0.0) // Two waypoints
        assertThat(routeWithTwo.estimatedFlightTimeMinutes).isGreaterThan(0)
    }

    @Test
    fun `removing waypoint updates route metrics`() {
        val waypoint1 = Waypoint(lat = 0.0, lon = 0.0, type = LocationType.TURNPOINT, label = "Start")
        val waypoint2 = Waypoint(lat = 1.0, lon = 1.0, type = LocationType.TURNPOINT, label = "End")

        val route = Route.fromWaypoints("Test Route", listOf(waypoint1, waypoint2))
        assertThat(route.totalDistanceKm).isGreaterThan(0.0)

        val routeAfterRemoval = route.removeWaypoint(waypoint2.id)
        assertThat(routeAfterRemoval.totalDistanceKm).isEqualTo(0.0) // Back to single waypoint
        assertThat(routeAfterRemoval.estimatedFlightTimeMinutes).isEqualTo(0)
    }

    @Test
    fun `route waypoints maintain correct ownership`() {
        val route = Route(name = "Test Route")
        val waypoint = Waypoint(lat = 40.0, lon = -74.0, type = LocationType.TURNPOINT, label = "Test")

        val updatedRoute = route.addWaypoint(waypoint.lat, waypoint.lon, waypoint.type, waypoint.label)

        // All waypoints should have the route ID set
        updatedRoute.waypoints.forEach { wp ->
            assertThat(wp.routeId).isEqualTo(updatedRoute.id)
        }
    }

    @Test
    fun `route fromWaypoints factory creates correct route`() {
        val waypoint1 = Waypoint(lat = 40.0, lon = -74.0, type = LocationType.TURNPOINT, label = "Start")
        val waypoint2 = Waypoint(lat = 41.0, lon = -75.0, type = LocationType.TURNPOINT, label = "End")

        val route = Route.fromWaypoints("Factory Test", listOf(waypoint1, waypoint2))

        assertThat(route.name).isEqualTo("Factory Test")
        assertThat(route.waypoints).hasSize(2)
        assertThat(route.totalDistanceKm).isGreaterThan(0.0)
        assertThat(route.estimatedFlightTimeMinutes).isGreaterThan(0)

        // All waypoints should have route ID set
        route.waypoints.forEach { wp ->
            assertThat(wp.routeId).isEqualTo(route.id)
        }
    }
}
