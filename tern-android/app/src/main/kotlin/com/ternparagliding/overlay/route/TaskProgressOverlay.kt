package com.ternparagliding.overlay.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import kotlinx.coroutines.flow.conflate
import org.osmdroid.util.GeoPoint

/** Fallback cylinder radius when a waypoint doesn't carry one (m). */
private const val DEFAULT_CYLINDER_RADIUS_M = 400.0

/**
 * Drives active-task navigation: which waypoint is "next" and when the pilot has
 * reached one. No map layer — it only feeds Redux (`activeWaypointId` /
 * `taggedWaypointIds`), which `RouteLayer` (on-map target highlight) and
 * `OffScreenWaypointIndicator` (the buddy-style edge chip) then render.
 *
 * Rules (auto-advance on cylinder entry):
 *  - The active task is the **selected** route. Switching/clearing it resets
 *    progress.
 *  - The next waypoint is the first one not yet tagged.
 *  - When the pilot's position falls within the next waypoint's cylinder radius,
 *    it's tagged and the engine advances to the following waypoint.
 *
 * Mirrors [RouteProximityOverlay]'s layer-less, position-driven pattern: a
 * conflated snapshot flow guarantees forward progress without re-keying on every
 * fix. Keyed on position + selection + tagged set so a fresh tag re-evaluates
 * immediately (cascading through any cylinders the pilot is already inside).
 */
@Composable
fun TaskProgressOverlay(store: MapStore) {
    val state by store.state.collectAsState()
    val latestState = rememberUpdatedState(state)

    LaunchedEffect(Unit) {
        var lastRouteId: String? = null

        snapshotFlow {
            val s = latestState.value
            Triple(s.userLocation, s.selectedRouteId, s.taggedWaypointIds)
        }
            .conflate()
            .collect { (own, routeId, tagged) ->
                // Task switched (or cleared) → wipe progress and re-derive next tick.
                if (routeId != lastRouteId) {
                    lastRouteId = routeId
                    store.dispatch(MapAction.ResetTaskProgress)
                    return@collect
                }

                val st = latestState.value
                val route = routeId?.let { id -> st.routes.find { it.id == id } } ?: return@collect
                if (own == null || route.waypoints.isEmpty()) return@collect

                val next = route.waypoints.firstOrNull { it.id !in tagged }
                if (next == null) {
                    // Task complete — no active target.
                    if (st.activeWaypointId != null) store.dispatch(MapAction.SetActiveWaypoint(null))
                    return@collect
                }

                val distM = own.distanceToAsDouble(GeoPoint(next.lat, next.lon))
                val radiusM = next.radius?.takeIf { it > 0 } ?: DEFAULT_CYLINDER_RADIUS_M
                if (distM <= radiusM) {
                    store.dispatch(MapAction.TagWaypoint(next.id)) // re-emits → advances
                } else if (st.activeWaypointId != next.id) {
                    store.dispatch(MapAction.SetActiveWaypoint(next.id))
                }
            }
    }
}
