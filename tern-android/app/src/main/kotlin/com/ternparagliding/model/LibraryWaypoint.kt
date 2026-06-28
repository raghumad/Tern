package com.ternparagliding.model

import kotlinx.serialization.Serializable

/**
 * A **standalone spot** — identity only, independent of any task.
 *
 * In real competitions (PWC, airtribune…) the organiser issues the waypoint set
 * *first* (a .cup / .wpt / .gpx download); the day's task is published later as an
 * **ordered selection of these spots** with per-task properties (start gate,
 * cylinder radius, role). So spots must exist on their own — this is that entity,
 * held in the pilot's **spot library** (the unified spot store: imported comp
 * waypoints, ad-hoc dropped points, and PG spots pulled into a task all live here).
 *
 * It deliberately carries no task-specific fields (no role/radius/gates): those
 * belong to the [TaskPoint] that *references* this spot, not to the spot itself.
 *
 * @param id        stable identity — the comp [code] (uppercased) when present, else a UUID.
 * @param code      short comp tag, e.g. "B42".
 * @param name      human description, e.g. "Gold's Point" (often more useful in flight).
 * @param alt       elevation in metres, if the source provided it.
 * @param source    provenance — how this spot entered the library ([SpotSource]).
 * @param sourceId  optional foreign id for the provenance (e.g. the PG-spot id this
 *                  spot was captured from), so it can be matched/refreshed later.
 */
@Serializable
data class Spot(
    val id: String,
    val code: String,
    val name: String? = null,
    val lat: Double,
    val lon: Double,
    val alt: Double? = null,
    val source: SpotSource = SpotSource.USER,
    val sourceId: String? = null,
) {
    /** Best human-facing label: the description if set, else the terse code. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: code
}

/** How a [Spot] entered the library. */
@Serializable
enum class SpotSource {
    /** Dropped by the pilot (map long-press) or otherwise hand-created. */
    USER,
    /** Parsed from an issued comp file (.cup / .wpt / .gpx). */
    IMPORTED,
    /** Captured from a ParaglidingEarth PG spot pulled into a task. */
    PG_SPOT,
}

/**
 * Back-compat alias. The library type was historically `LibraryWaypoint`; it is now
 * the unified [Spot]. Kept so existing references keep compiling during the Stage C
 * rollout (removed in the final cosmetic sub-stage).
 */
typealias LibraryWaypoint = Spot
