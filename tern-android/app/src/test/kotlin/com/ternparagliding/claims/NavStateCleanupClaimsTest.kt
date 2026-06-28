package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.mapReducer
import org.junit.Test

/**
 * Claim **K14 · Nav state never dangles** (Stage C) — deleting the task/point a
 * pilot is flying clears `activeWaypointId`/`taggedWaypointIds`, so the compass
 * rosette and ribbon never point at a vanished waypoint.
 */
class NavStateCleanupClaimsTest {

    private fun state() = MapState(
        tasks = listOf(Task(id = "t1", name = "T1", waypoints = listOf(
            Waypoint(id = "p1", lat = 0.0, lon = 0.0, label = "A"),
            Waypoint(id = "p2", lat = 1.0, lon = 1.0, label = "B"),
        ))),
        activeWaypointId = "p2",
        taggedWaypointIds = setOf("p1"),
    )

    @Test
    fun `removing the task clears nav pointing at its waypoints`() {
        val s = mapReducer(state(), MapAction.RemoveTask("t1"))
        assertThat(s.activeWaypointId).isNull()
        assertThat(s.taggedWaypointIds).isEmpty()
    }

    @Test
    fun `clearing all tasks clears nav`() {
        val s = mapReducer(state(), MapAction.ClearAllTasks)
        assertThat(s.activeWaypointId).isNull()
        assertThat(s.taggedWaypointIds).isEmpty()
    }

    @Test
    fun `removing the active waypoint clears the active target`() {
        val s = mapReducer(state(), MapAction.RemoveWaypoint("t1", "p2"))
        assertThat(s.activeWaypointId).isNull()
        assertThat(s.taggedWaypointIds).containsExactly("p1") // unrelated tag kept
    }

    @Test
    fun `removing a tagged waypoint drops it from the tagged set`() {
        val s = mapReducer(state(), MapAction.RemoveWaypoint("t1", "p1"))
        assertThat(s.taggedWaypointIds).isEmpty()
        assertThat(s.activeWaypointId).isEqualTo("p2") // active target untouched
    }
}
