package com.ternparagliding.overlay.task

import com.ternparagliding.model.Spot
import com.ternparagliding.model.SpotSource
import com.ternparagliding.model.Task

/**
 * Resolves a task's **spot references** against the spot library.
 *
 * A task point references a [Spot] by `spotId`: its identity (position, code,
 * name, elevation) lives in the library, not the task. This function overlays the
 * live spot identity onto such points, leaving the task-specific features
 * (role/type, cylinder radius, gates, the point's id) untouched. A point whose
 * spot is missing (deleted, or wrong set imported) falls back to its stored
 * snapshot and is flagged stale by [isStale].
 *
 * The single chokepoint for "edit a spot → every task using it updates" — call it
 * on the read path (rendering, nav, ribbon).
 */
object TaskResolver {

    fun resolve(task: Task, library: Map<String, Spot>): Task {
        if (task.waypoints.none { it.spotId != null }) return task
        val resolved = task.waypoints.map { wp ->
            val spot = wp.spotId?.let { library[it] } ?: return@map wp
            wp.copy(
                lat = spot.lat,
                lon = spot.lon,
                label = spot.code,
                description = spot.name,
                alt = spot.alt ?: wp.alt,
            )
        }
        return task.copy(waypoints = resolved)
    }

    fun resolveAll(tasks: List<Task>, library: List<Spot>): List<Task> {
        if (library.isEmpty()) return tasks
        val byId = library.associateBy { it.id }
        return tasks.map { resolve(it, byId) }
    }

    /**
     * Bind an *imported* task's points to the library by **code** (the comp-day
     * flow: load the issued waypoints, then load the day's task → it links to
     * them). An unlinked point whose code matches a library entry gets that
     * `spotId` stamped; everything else (already-linked, or no code match) is left
     * as-is so it stays flyable from the snapshot.
     */
    fun bindToLibrary(task: Task, library: List<Spot>): Task {
        if (library.isEmpty()) return task
        val byCode = library.associateBy { it.code.trim().uppercase() }
        var changed = false
        val bound = task.waypoints.map { wp ->
            if (wp.spotId != null) return@map wp
            val code = (wp.label ?: wp.description)?.trim()?.uppercase()
            val spot = code?.let { byCode[it] }
            if (spot != null) { changed = true; wp.copy(spotId = spot.id) } else wp
        }
        return if (changed) task.copy(waypoints = bound) else task
    }

    /** Derive IMPORTED [Spot]s from a freshly-imported task's points, so a task
     *  loaded on its own (no separate waypoint file) still seeds the library and
     *  its points can reference spots. Keyed by uppercased code (the id), so it
     *  merges cleanly with a separately-imported waypoint set of the same comp. */
    fun spotsFromTask(task: Task): List<Spot> =
        task.waypoints.mapNotNull { wp ->
            val code = (wp.label ?: wp.description)?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Spot(
                id = code.uppercase(),
                code = code,
                name = wp.description?.trim()?.takeIf { it.isNotBlank() && it != code },
                lat = wp.lat,
                lon = wp.lon,
                alt = wp.alt,
                source = SpotSource.IMPORTED,
            )
        }.distinctBy { it.id }

    /** A point that *references* the library but whose spot is gone (deleted /
     *  wrong set imported). The resolver still flies it from the snapshot, but the
     *  UI should flag it so the pilot knows the position may be stale. */
    fun isStale(wp: com.ternparagliding.model.Waypoint, library: List<Spot>): Boolean =
        wp.spotId != null && library.none { it.id == wp.spotId }

    /** @deprecated kept transitionally; use [isStale]. */
    fun isMissingLink(wp: com.ternparagliding.model.Waypoint, library: List<Spot>): Boolean =
        isStale(wp, library)
}
