package com.ternparagliding.overlay.task

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.resolvedTasks
import kotlinx.coroutines.flow.conflate

/**
 * Drives active-task navigation: which waypoint is "next" and when the pilot has
 * reached one. No map layer — it only feeds Redux (`activeWaypointId` /
 * `taggedWaypointIds`), which `TaskLayer` (on-map target highlight) and
 * `OffScreenWaypointIndicator` (the buddy-style edge chip) then render.
 *
 * Rules (auto-advance on cylinder entry):
 *  - The active task is the **selected** task. Switching/clearing it resets
 *    progress.
 *  - The next waypoint is the first one not yet tagged.
 *  - When the pilot's position falls within the next waypoint's cylinder radius,
 *    it's tagged and the engine advances to the following waypoint.
 *
 * Mirrors [TaskProximityOverlay]'s layer-less, position-driven pattern: a
 * conflated snapshot flow guarantees forward progress without re-keying on every
 * fix. Keyed on position + selection + tagged set so a fresh tag re-evaluates
 * immediately (cascading through any cylinders the pilot is already inside).
 */
@Composable
fun TaskProgressOverlay(store: MapStore) {
    val state by store.state.collectAsState()
    val latestState = rememberUpdatedState(state)

    LaunchedEffect(Unit) {
        var lastTaskId: String? = null

        snapshotFlow {
            val s = latestState.value
            Triple(s.userLocation, s.selectedTaskId, s.taggedWaypointIds)
        }
            .conflate()
            .collect { (own, taskId, tagged) ->
                // Task switched (or cleared) → wipe progress and re-derive next tick.
                if (taskId != lastTaskId) {
                    lastTaskId = taskId
                    store.dispatch(MapAction.ResetTaskProgress)
                    return@collect
                }

                val st = latestState.value
                // Resolve library references so reach detection uses the live library
                // position (Stage B2), not a possibly-stale stored copy.
                val task = taskId?.let { id -> st.resolvedTasks().find { it.id == id } } ?: return@collect
                if (own == null || task.waypoints.isEmpty()) return@collect

                val next = TaskNavigator.nextWaypoint(task, tagged)
                if (next == null) {
                    // Task complete — no active target.
                    if (st.activeWaypointId != null) store.dispatch(MapAction.SetActiveWaypoint(null))
                    return@collect
                }

                if (TaskNavigator.isReached(own, next)) {
                    store.dispatch(MapAction.TagWaypoint(next.id)) // re-emits → advances
                } else if (st.activeWaypointId != next.id) {
                    store.dispatch(MapAction.SetActiveWaypoint(next.id))
                }
            }
    }
}
