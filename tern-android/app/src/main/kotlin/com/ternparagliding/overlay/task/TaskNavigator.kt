package com.ternparagliding.overlay.task

import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import org.osmdroid.util.GeoPoint

/**
 * Pure active-task navigation logic — the rules behind the next-waypoint
 * guidance, with no Compose/Redux dependencies so they can be driven directly
 * by a claim test over a real flight track.
 *
 * Shared by [TaskProgressOverlay] (which feeds the result into Redux) so the
 * test exercises the exact code the pilot depends on, not a parallel copy.
 */
object TaskNavigator {
    /** Fallback cylinder radius when a waypoint doesn't carry one (m). */
    const val DEFAULT_CYLINDER_RADIUS_M = 400.0

    /** The next waypoint to fly to: the first not-yet-tagged point in task order
     *  (null once every waypoint has been reached — the task is complete). */
    fun nextWaypoint(task: Task, tagged: Set<String>): Waypoint? =
        task.waypoints.firstOrNull { it.id !in tagged }

    /** The cylinder radius used for reach detection (falls back to a default). */
    fun cylinderRadiusM(wp: Waypoint): Double = wp.radius?.takeIf { it > 0 } ?: DEFAULT_CYLINDER_RADIUS_M

    /** True when [own] is inside the waypoint's cylinder — i.e. the pilot has
     *  reached/tagged it and the target should advance. */
    fun isReached(own: GeoPoint, wp: Waypoint): Boolean =
        own.distanceToAsDouble(GeoPoint(wp.lat, wp.lon)) <= cylinderRadiusM(wp)
}
