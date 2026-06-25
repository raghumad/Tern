package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Spot
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.WaypointSelection
import com.ternparagliding.redux.mapReducer
import com.ternparagliding.redux.resolvedTasks
import org.junit.Test

/**
 * Claim **K13 · Identity vs feature edits** (Stage C) — editing a spot's identity
 * flows to every task that references it; per-task features (radius, gates) live on
 * the reference and can be *cleared*. Dragging a linked point moves the spot, so the
 * edit shows instead of being painted over by the resolver.
 */
class TaskMutationClaimsTest {

    private val spot = Spot(id = "B42", code = "B42", name = "Old Name", lat = 32.0, lon = 76.0)
    private val t1 = Task(id = "t1", name = "Task 1", waypoints = listOf(
        Waypoint(id = "t1p1", lat = 32.0, lon = 76.0, label = "B42", spotId = "B42", radius = 1000.0, openTime = "13:00")))
    private val t2 = Task(id = "t2", name = "Task 2", waypoints = listOf(
        Waypoint(id = "t2p1", lat = 32.0, lon = 76.0, label = "B42", spotId = "B42", radius = 200.0)))

    private fun state() = MapState(
        tasks = listOf(t1, t2),
        waypointLibrary = listOf(spot),
        selectedTaskId = "t1",
        selectedWaypoint = WaypointSelection("t1", "t1p1"),
    )

    @Test
    fun `editing a spot flows to every task referencing it`() {
        val s = mapReducer(state(), MapAction.UpdateSpot("B42", name = "Gold's Point", lat = 33.0, lon = 77.0))
        val resolved = s.resolvedTasks()
        for (t in resolved) {
            val wp = t.waypoints.single()
            assertThat(wp.lat).isWithin(1e-9).of(33.0)
            assertThat(wp.lon).isWithin(1e-9).of(77.0)
            assertThat(wp.description).isEqualTo("Gold's Point")
        }
        // …but each task keeps its own cylinder (a per-task feature).
        assertThat(resolved.first { it.id == "t1" }.waypoints.single().radius).isEqualTo(1000.0)
        assertThat(resolved.first { it.id == "t2" }.waypoints.single().radius).isEqualTo(200.0)
    }

    @Test
    fun `renaming a task point edits the spot, flowing to the other task`() {
        val s = mapReducer(state(), MapAction.UpdateWaypointDescription("t1", "t1p1", "Gold's Point"))
        assertThat(s.waypointLibrary.single { it.id == "B42" }.name).isEqualTo("Gold's Point")
        // The other task, untouched by the action, sees the new name via the resolver.
        val other = s.resolvedTasks().first { it.id == "t2" }.waypoints.single()
        assertThat(other.description).isEqualTo("Gold's Point")
    }

    @Test
    fun `a time gate can be cleared`() {
        val s = mapReducer(state(), MapAction.UpdateWaypointTimeGates("t1", "t1p1", null, null))
        assertThat(s.tasks.first { it.id == "t1" }.waypoints.single().openTime).isNull()
    }

    @Test
    fun `a cylinder radius can be cleared`() {
        val s = mapReducer(state(), MapAction.UpdateWaypointRadius("t1", "t1p1", null))
        assertThat(s.tasks.first { it.id == "t1" }.waypoints.single().radius).isNull()
    }

    @Test
    fun `dragging a linked point moves the spot, so the move is visible`() {
        val started = mapReducer(state(), MapAction.StartWaypointDrag("t1", "t1p1"))
        val dragged = mapReducer(started, MapAction.UpdateWaypointDrag(34.0, 78.0))
        assertThat(dragged.waypointLibrary.single { it.id == "B42" }.lat).isWithin(1e-9).of(34.0)
        assertThat(dragged.resolvedTasks().first { it.id == "t1" }.waypoints.single().lat).isWithin(1e-9).of(34.0)
    }

    @Test
    fun `cancelling a drag restores the spot's original position`() {
        val started = mapReducer(state(), MapAction.StartWaypointDrag("t1", "t1p1"))
        val dragged = mapReducer(started, MapAction.UpdateWaypointDrag(34.0, 78.0))
        val cancelled = mapReducer(dragged, MapAction.CancelWaypointDrag)
        val spotNow = cancelled.waypointLibrary.single { it.id == "B42" }
        assertThat(spotNow.lat).isWithin(1e-9).of(32.0)
        assertThat(spotNow.lon).isWithin(1e-9).of(76.0)
    }
}
