package com.ternparagliding.redux

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Waypoint
import com.ternparagliding.model.Task
import com.ternparagliding.model.LocationType
import org.junit.Test

import java.time.Instant

class MapReducersTest {

    private val initialState = MapState()

    @Test
    fun `AddTask action adds task to state and limits count`() {
        val task = Task(id = "task1", name = "Test Task", createdAt = Instant.now())
        
        val newState = mapReducer(initialState, MapAction.AddTask(task))
        
        assertThat(newState.tasks).hasSize(1)
        assertThat(newState.tasks[0].id).isEqualTo("task1")
    }

    @Test
    fun `RemoveTask action removes task and clears selection`() {
        val task = Task(id = "task1", name = "Test Task")
        val stateWithTask = initialState.copy(
            tasks = listOf(task),
            selectedTaskId = "task1"
        )

        val newState = mapReducer(stateWithTask, MapAction.RemoveTask("task1"))

        assertThat(newState.tasks).isEmpty()
        assertThat(newState.selectedTaskId).isNull()
    }

    @Test
    fun `SelectTask action updates selectedTaskId`() {
        val newState = mapReducer(initialState, MapAction.SelectTask("task1"))
        
        assertThat(newState.selectedTaskId).isEqualTo("task1")
    }

    @Test
    fun `AddWaypointToTask adds waypoint to correct task`() {
        val task = Task(id = "task1", name = "Test Task")
        val stateWithTask = initialState.copy(tasks = listOf(task))

        val newState = mapReducer(
            stateWithTask, 
            MapAction.AddWaypointToTask("task1", 10.0, 20.0, LocationType.TURNPOINT)
        )

        assertThat(newState.tasks[0].waypoints).hasSize(1)
        assertThat(newState.tasks[0].waypoints[0].lat).isEqualTo(10.0)
        assertThat(newState.tasks[0].waypoints[0].lon).isEqualTo(20.0)
    }

    @Test
    fun `RemoveWaypoint removes waypoint and clears selection if selected`() {
        val waypoint = Waypoint(id = "wp1", lat = 10.0, lon = 20.0)
        val task = Task(id = "task1", name = "Test Task", waypoints = listOf(waypoint))
        
        val stateWithWaypoint = initialState.copy(
            tasks = listOf(task),
            selectedWaypoint = WaypointSelection("task1", "wp1", false)
        )

        val newState = mapReducer(stateWithWaypoint, MapAction.RemoveWaypoint("task1", "wp1"))

        assertThat(newState.tasks[0].waypoints).isEmpty()
        assertThat(newState.selectedWaypoint).isNull()
    }

}
