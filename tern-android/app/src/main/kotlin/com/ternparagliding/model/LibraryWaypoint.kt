package com.ternparagliding.model

import kotlinx.serialization.Serializable

/**
 * A **standalone waypoint** — identity only, independent of any task.
 *
 * In real competitions (PWC, airtribune…) the organiser issues the waypoint set
 * *first* (a .cup / .wpt / .gpx download); the day's task is published later as an
 * **ordered selection of these waypoints** with per-task properties (start gate,
 * cylinder radius, role). So waypoints must exist on their own — this is that
 * entity, held in the pilot's **waypoint library**.
 *
 * It deliberately carries no task-specific fields (no role/radius/gates): those
 * belong to the task that *references* this waypoint, not to the waypoint itself.
 *
 * @param id    stable identity — the comp [code] when present, else a UUID.
 * @param code  short comp tag, e.g. "B42".
 * @param name  human description, e.g. "Gold's Point" (often more useful in flight).
 * @param alt   elevation in metres, if the source provided it.
 */
@Serializable
data class LibraryWaypoint(
    val id: String,
    val code: String,
    val name: String? = null,
    val lat: Double,
    val lon: Double,
    val alt: Double? = null,
) {
    /** Best human-facing label: the description if set, else the terse code. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: code
}
