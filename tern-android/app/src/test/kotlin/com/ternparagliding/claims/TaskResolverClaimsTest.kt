package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.LibraryWaypoint
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.task.TaskResolver
import org.junit.Test

/**
 * Claim **K10 · Reference resolution** (Stage B2) — a task point that references a
 * library waypoint draws its identity (position/code/name/alt) from the *current*
 * library, while keeping its own task-specific properties (role, cylinder, gates).
 * So editing/re-importing a library waypoint flows into every task using it.
 */
class TaskResolverClaimsTest {

    private fun lib(vararg wps: LibraryWaypoint) = wps.toList()

    @Test
    fun `linked point takes live library identity, keeps task properties`() {
        val task = Task(
            id = "t", name = "T",
            waypoints = listOf(
                Waypoint(id = "p1", lat = 0.0, lon = 0.0, type = LocationType.SSS, radius = 1000.0,
                    label = "OLD", description = "old", libraryWaypointId = "B42", openTime = "13:00"),
            ),
        )
        // Library now has B42 at the corrected position/name (e.g. organiser re-issued).
        val library = lib(LibraryWaypoint(id = "B42", code = "B42", name = "Gold's Point", lat = 32.21, lon = 76.43, alt = 2438.0))

        val resolved = TaskResolver.resolveAll(listOf(task), library).first().waypoints.first()

        // Identity comes from the library…
        assertThat(resolved.lat).isWithin(1e-9).of(32.21)
        assertThat(resolved.lon).isWithin(1e-9).of(76.43)
        assertThat(resolved.label).isEqualTo("B42")
        assertThat(resolved.description).isEqualTo("Gold's Point")
        assertThat(resolved.alt!!).isWithin(1e-9).of(2438.0)
        // …task properties are preserved.
        assertThat(resolved.type).isEqualTo(LocationType.SSS)
        assertThat(resolved.radius).isEqualTo(1000.0)
        assertThat(resolved.openTime).isEqualTo("13:00")
        assertThat(resolved.id).isEqualTo("p1")
    }

    @Test
    fun `ad-hoc point (no link) is unchanged`() {
        val task = Task(id = "t", name = "T", waypoints = listOf(
            Waypoint(id = "p1", lat = 45.8, lon = 6.5, label = "LP1"), // no libraryWaypointId
        ))
        val resolved = TaskResolver.resolveAll(listOf(task), lib()).first().waypoints.first()
        assertThat(resolved.lat).isWithin(1e-9).of(45.8)
        assertThat(resolved.label).isEqualTo("LP1")
    }

    @Test
    fun `missing library entry falls back to the stored copy`() {
        val task = Task(id = "t", name = "T", waypoints = listOf(
            Waypoint(id = "p1", lat = 32.0, lon = 76.0, label = "B42", libraryWaypointId = "B42"),
        ))
        // Library no longer has B42 (deleted) → keep the point's stored copy, don't drop it.
        val resolved = TaskResolver.resolveAll(listOf(task), lib()).first().waypoints.first()
        assertThat(resolved.lat).isWithin(1e-9).of(32.0)
        assertThat(resolved.label).isEqualTo("B42")
    }
}
