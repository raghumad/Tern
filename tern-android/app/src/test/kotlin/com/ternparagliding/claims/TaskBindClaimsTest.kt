package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.LibraryWaypoint
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.task.TaskResolver
import org.junit.Test

/**
 * Claim **K11 · Comp-day round-trip** (Stage B3) — an imported task binds its
 * points to the library by code, so the day's task references the issued
 * waypoints; a point whose code isn't in the library stays flyable from the file;
 * a link whose entry later disappears is flagged missing.
 */
class TaskBindClaimsTest {

    private val library = listOf(
        LibraryWaypoint(id = "LW049", code = "LW049", name = null, lat = 54.77, lon = -2.55, alt = 578.0),
        LibraryWaypoint(id = "BS001", code = "BS001", name = "Grasmere LZ", lat = 54.46, lon = -3.02, alt = 69.0),
    )

    /** **CLAIM.** Importing a task (codes in `label`) links matching points to the
     *  library and leaves unmatched points unlinked but intact. */
    @Test
    fun `import binds points to library by code`() {
        val task = Task(id = "day3", name = "Day 3", waypoints = listOf(
            Waypoint(id = "p1", lat = 0.0, lon = 0.0, type = LocationType.SSS, label = "LW049"),  // in library
            Waypoint(id = "p2", lat = 1.0, lon = 1.0, type = LocationType.GOAL, label = "ZZZ99"), // not in library
        ))

        val bound = TaskResolver.bindToLibrary(task, library)

        assertThat(bound.waypoints[0].spotId).isEqualTo("LW049") // linked
        assertThat(bound.waypoints[1].spotId).isNull()           // unmatched, left as-is
        // Roles (task-specific) are preserved through binding.
        assertThat(bound.waypoints[0].type).isEqualTo(LocationType.SSS)
    }

    /** **CLAIM.** After binding, resolution flows the library identity in — the
     *  imported task flies the *issued* positions, not the file's placeholders. */
    @Test
    fun `bound task resolves to library positions`() {
        val task = Task(id = "t", name = "T", waypoints = listOf(
            Waypoint(id = "p1", lat = 0.0, lon = 0.0, label = "BS001"),
        ))
        val bound = TaskResolver.bindToLibrary(task, library)
        val resolved = TaskResolver.resolveAll(listOf(bound), library).first().waypoints.first()
        assertThat(resolved.lat).isWithin(1e-9).of(54.46)
        assertThat(resolved.description).isEqualTo("Grasmere LZ")
    }

    /** **CLAIM.** A link whose library entry is gone is flagged missing (but kept). */
    @Test
    fun `missing link is detected`() {
        val present = Waypoint(id = "p1", lat = 0.0, lon = 0.0, label = "LW049", spotId = "LW049")
        val gone = Waypoint(id = "p2", lat = 0.0, lon = 0.0, label = "OLD", spotId = "DELETED")
        val adhoc = Waypoint(id = "p3", lat = 0.0, lon = 0.0, label = "FREE")

        assertThat(TaskResolver.isMissingLink(present, library)).isFalse()
        assertThat(TaskResolver.isMissingLink(gone, library)).isTrue()
        assertThat(TaskResolver.isMissingLink(adhoc, library)).isFalse()
    }
}
