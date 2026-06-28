package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.task.TaskNavigator
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.mapReducer
import org.junit.Test

/**
 * Claim **K7 · Manual task overrides** (Phase 2) — the pilot can override the
 * auto-advancing target from the task ribbon: **Go to** any waypoint out of
 * sequence, **Skip** the active one, and go **Back** to a previous one.
 *
 * Driven through the exact reducer the UI dispatches into ([mapReducer]) and the
 * exact "next waypoint" rule the guidance reads ([TaskNavigator.nextWaypoint]),
 * so we assert the pilot-visible outcome (which waypoint is the target) rather
 * than any internal flag.
 */
class TaskOverrideClaimsTest {

    private fun task() = Task(
        id = "t",
        name = "T",
        waypoints = (0..4).map { Waypoint(id = "w$it", lat = it.toDouble(), lon = 0.0, label = "B$it", radius = 400.0) },
    )

    private fun stateWith(t: Task) = MapState(tasks = listOf(t), selectedTaskId = t.id)

    private fun targetId(s: MapState, t: Task) = TaskNavigator.nextWaypoint(t, s.taggedWaypointIds)?.id

    /** **CLAIM.** Tapping a dot retargets to that waypoint out of sequence: it
     *  becomes active, every prior waypoint counts as done, none after it do. */
    @Test
    fun `Go to retargets out of sequence`() {
        val t = task()
        var s = stateWith(t)
        assertThat(targetId(s, t)).isEqualTo("w0") // starts at the first

        s = mapReducer(s, MapAction.GoToWaypoint(t.id, "w3"))

        assertThat(s.activeWaypointId).isEqualTo("w3")
        assertThat(targetId(s, t)).isEqualTo("w3")
        assertThat(s.taggedWaypointIds).containsExactly("w0", "w1", "w2")
    }

    /** **CLAIM.** Skip tags the active waypoint, advancing the target to the next. */
    @Test
    fun `Skip advances to the next waypoint`() {
        val t = task()
        var s = stateWith(t).copy(activeWaypointId = "w0")

        s = mapReducer(s, MapAction.TagWaypoint("w0"))

        assertThat(targetId(s, t)).isEqualTo("w1")
    }

    /** **CLAIM.** Back returns to the previous waypoint (undo an accidental tag). */
    @Test
    fun `Back reverts to the previous waypoint`() {
        val t = task()
        var s = mapReducer(stateWith(t), MapAction.GoToWaypoint(t.id, "w3")) // at w3

        s = mapReducer(s, MapAction.GoToWaypoint(t.id, "w2")) // Back → w2

        assertThat(s.activeWaypointId).isEqualTo("w2")
        assertThat(targetId(s, t)).isEqualTo("w2")
        assertThat(s.taggedWaypointIds).containsExactly("w0", "w1")
    }

    /** **CLAIM.** Go to the last waypoint, then Skip it → task complete, no target. */
    @Test
    fun `skipping the final waypoint completes the task`() {
        val t = task()
        var s = mapReducer(stateWith(t), MapAction.GoToWaypoint(t.id, "w4"))
        assertThat(targetId(s, t)).isEqualTo("w4")

        s = mapReducer(s, MapAction.TagWaypoint("w4"))

        assertThat(targetId(s, t)).isNull()
    }
}
