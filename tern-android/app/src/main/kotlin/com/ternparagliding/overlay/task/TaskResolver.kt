package com.ternparagliding.overlay.task

import com.ternparagliding.model.LibraryWaypoint
import com.ternparagliding.model.Task

/**
 * Stage B2 — resolves a task's **library references** against the waypoint library.
 *
 * A task point that carries a `libraryWaypointId` is a *reference*: its identity
 * (position, code, name, elevation) lives in the library, not the task. This
 * function overlays the live library identity onto such points, leaving the
 * task-specific properties (role/type, cylinder radius, gates, the point's id)
 * untouched. Ad-hoc points (no link) pass through unchanged; a link whose library
 * entry is missing falls back to the point's stored copy (B3 will flag these).
 *
 * The single chokepoint for "edit a library waypoint → every task using it
 * updates" — call it on the read path (rendering, nav, ribbon).
 */
object TaskResolver {

    fun resolve(task: Task, library: Map<String, LibraryWaypoint>): Task {
        if (task.waypoints.none { it.libraryWaypointId != null }) return task
        val resolved = task.waypoints.map { wp ->
            val lib = wp.libraryWaypointId?.let { library[it] } ?: return@map wp
            wp.copy(
                lat = lib.lat,
                lon = lib.lon,
                label = lib.code,
                description = lib.name,
                alt = lib.alt ?: wp.alt,
            )
        }
        return task.copy(waypoints = resolved)
    }

    fun resolveAll(tasks: List<Task>, library: List<LibraryWaypoint>): List<Task> {
        if (library.isEmpty()) return tasks
        val byId = library.associateBy { it.id }
        return tasks.map { resolve(it, byId) }
    }
}
