package com.madanala.tern.overlay.route

import com.madanala.tern.model.Waypoint
import com.madanala.tern.overlay.priority.OverlayCandidate
import com.madanala.tern.overlay.priority.OverlayKind
import com.madanala.tern.overlay.priority.Position

/**
 * Wraps a route [Waypoint] as an [OverlayCandidate] so the
 * [OverlayPrioritizer] can budget it alongside airspaces, peers, etc.
 *
 * Uses the default scoring formula (safetyWeight * distanceDecay).
 * Route waypoints are few (~5-10) and always needed during navigation,
 * so they naturally survive any reasonable budget cut.
 */
data class RouteWaypointCandidate(
    val waypoint: Waypoint,
) : OverlayCandidate {
    override val kind: OverlayKind = OverlayKind.ROUTE_WAYPOINT
    override val position: Position = Position(waypoint.lat, waypoint.lon)
}
