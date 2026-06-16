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

    /**
     * Stage B3 — bind an *imported* task's points to the library by **code** (the
     * comp-day flow: load the issued waypoints, then load the day's task → it links
     * to them). An unlinked point whose code matches a library entry gets that
     * link stamped; everything else (already-linked, or no code match) is left
     * as-is so it stays flyable from the task file's own coordinates.
     */
    fun bindToLibrary(task: Task, library: List<LibraryWaypoint>): Task {
        if (library.isEmpty()) return task
        val byCode = library.associateBy { it.code.trim().uppercase() }
        var changed = false
        val bound = task.waypoints.map { wp ->
            if (wp.libraryWaypointId != null) return@map wp
            val code = (wp.label ?: wp.description)?.trim()?.uppercase()
            val lib = code?.let { byCode[it] }
            if (lib != null) { changed = true; wp.copy(libraryWaypointId = lib.id) } else wp
        }
        return if (changed) task.copy(waypoints = bound) else task
    }

    /** A point that *references* the library but whose entry is gone (deleted /
     *  wrong set imported). The resolver still flies it from the stored copy, but
     *  the UI should flag it so the pilot knows the position may be stale. */
    fun isMissingLink(wp: com.ternparagliding.model.Waypoint, library: List<LibraryWaypoint>): Boolean =
        wp.libraryWaypointId != null && library.none { it.id == wp.libraryWaypointId }
}
