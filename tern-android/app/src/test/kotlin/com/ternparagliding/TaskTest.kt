package com.ternparagliding

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Waypoint
import com.ternparagliding.model.Task
import com.ternparagliding.model.LocationType
import org.junit.jupiter.api.Test

/**
 * Unit tests for Task business logic
 * Tests distance calculations, waypoint management, and task metrics
 */
class TaskTest {

    @Test
    fun `task with no waypoints has zero distance and time`() {
        val task = Task(name = "Empty Task")

        assertThat(task.totalDistanceKm).isEqualTo(0.0)
        assertThat(task.estimatedFlightTimeMinutes).isEqualTo(0)
    }

    @Test
    fun `task with single waypoint has zero distance and time`() {
        val task = Task(
            name = "Single Waypoint Task",
            waypoints = listOf(
                Waypoint(
                    lat = 40.0,
                    lon = -74.0,
                    type = LocationType.TURNPOINT,
                    label = "WP1-1"
                )
            )
        )

        assertThat(task.totalDistanceKm).isEqualTo(0.0)
        assertThat(task.estimatedFlightTimeMinutes).isEqualTo(0)
    }

    @Test
    fun `task with two waypoints calculates distance and time correctly`() {
        // New York to Los Angeles approximate coordinates
        val nyc = Waypoint(lat = 40.7128, lon = -74.0060, type = LocationType.TURNPOINT, label = "NYC")
        val lax = Waypoint(lat = 33.9416, lon = -118.4085, type = LocationType.TURNPOINT, label = "LAX")

        val task = Task.fromWaypoints("Cross Country", listOf(nyc, lax))

        // Should have non-zero distance and time
        assertThat(task.totalDistanceKm).isGreaterThan(3900.0) // NYC to LAX is ~3930km
        assertThat(task.totalDistanceKm).isLessThan(4000.0)
        assertThat(task.estimatedFlightTimeMinutes).isGreaterThan(0)
    }

    @Test
    fun `adding waypoint updates task metrics`() {
        val task = Task(name = "Test Task")
        val waypoint1 = Waypoint(lat = 0.0, lon = 0.0, type = LocationType.TURNPOINT, label = "Start")
        val waypoint2 = Waypoint(lat = 1.0, lon = 1.0, type = LocationType.TURNPOINT, label = "End")

        val taskWithOne = task.addWaypoint(waypoint1.lat, waypoint1.lon, waypoint1.type, waypoint1.label)
        assertThat(taskWithOne.totalDistanceKm).isEqualTo(0.0) // Single waypoint

        val taskWithTwo = taskWithOne.addWaypoint(waypoint2.lat, waypoint2.lon, waypoint2.type, waypoint2.label)
        assertThat(taskWithTwo.totalDistanceKm).isGreaterThan(0.0) // Two waypoints
        assertThat(taskWithTwo.estimatedFlightTimeMinutes).isGreaterThan(0)
    }

    @Test
    fun `removing waypoint updates task metrics`() {
        val waypoint1 = Waypoint(lat = 0.0, lon = 0.0, type = LocationType.TURNPOINT, label = "Start")
        val waypoint2 = Waypoint(lat = 1.0, lon = 1.0, type = LocationType.TURNPOINT, label = "End")

        val task = Task.fromWaypoints("Test Task", listOf(waypoint1, waypoint2))
        assertThat(task.totalDistanceKm).isGreaterThan(0.0)

        val taskAfterRemoval = task.removeWaypoint(waypoint2.id)
        assertThat(taskAfterRemoval.totalDistanceKm).isEqualTo(0.0) // Back to single waypoint
        assertThat(taskAfterRemoval.estimatedFlightTimeMinutes).isEqualTo(0)
    }

    @Test
    fun `task waypoints maintain correct ownership`() {
        val task = Task(name = "Test Task")
        val waypoint = Waypoint(lat = 40.0, lon = -74.0, type = LocationType.TURNPOINT, label = "Test")

        val updatedTask = task.addWaypoint(waypoint.lat, waypoint.lon, waypoint.type, waypoint.label)

        // All waypoints should have the task ID set
        updatedTask.waypoints.forEach { wp ->
            assertThat(wp.taskId).isEqualTo(updatedTask.id)
        }
    }

    @Test
    fun `task fromWaypoints factory creates correct task`() {
        val waypoint1 = Waypoint(lat = 40.0, lon = -74.0, type = LocationType.TURNPOINT, label = "Start")
        val waypoint2 = Waypoint(lat = 41.0, lon = -75.0, type = LocationType.TURNPOINT, label = "End")

        val task = Task.fromWaypoints("Factory Test", listOf(waypoint1, waypoint2))

        assertThat(task.name).isEqualTo("Factory Test")
        assertThat(task.waypoints).hasSize(2)
        assertThat(task.totalDistanceKm).isGreaterThan(0.0)
        assertThat(task.estimatedFlightTimeMinutes).isGreaterThan(0)

        // All waypoints should have task ID set
        task.waypoints.forEach { wp ->
            assertThat(wp.taskId).isEqualTo(task.id)
        }
    }
}
