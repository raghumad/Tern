package com.ternparagliding.overlay.task

import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.priority.OverlayCandidate
import com.ternparagliding.overlay.priority.OverlayKind
import com.ternparagliding.overlay.priority.Position

/**
 * Wraps a task [Waypoint] as an [OverlayCandidate] so the
 * [OverlayPrioritizer] can budget it alongside airspaces, peers, etc.
 *
 * Uses the default scoring formula (safetyWeight * distanceDecay).
 * Task waypoints are few (~5-10) and always needed during navigation,
 * so they naturally survive any reasonable budget cut.
 */
data class TaskWaypointCandidate(
    val waypoint: Waypoint,
) : OverlayCandidate {
    override val kind: OverlayKind = OverlayKind.ROUTE_WAYPOINT
    override val position: Position = Position(waypoint.lat, waypoint.lon)
}
