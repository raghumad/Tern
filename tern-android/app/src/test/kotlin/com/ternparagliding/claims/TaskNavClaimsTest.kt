package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.task.TaskNavigator
import com.ternparagliding.sim.igc.IgcParser
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Claim **K6 · Next-waypoint guidance** — when a task is defined, the app shows
 * the next waypoint (buddy-style) and auto-advances it as the pilot flies
 * through each cylinder.
 *
 * Driven by the real Bir Billing flight track (richard's IGC) run through the
 * exact navigation logic the UI uses ([TaskNavigator]) — no emulator, no
 * screenshots. The cylinders are anchored *on* the flown track, so the recorded
 * positions genuinely pass through them; we assert the pilot-visible outcome
 * (the target advances, in order) rather than any Redux flag.
 */
class TaskNavClaimsTest {

    private fun loadRichard() = IgcParser.parseString(
        javaClass.getResourceAsStream("/igc/flights/in/2025-10-11-birbilling-richard.igc")!!
            .bufferedReader().use { it.readText() }
    )

    /** Place a task's cylinders on points the flight actually flew, in time order. */
    private fun coLocatedTask(fixes: List<GeoPoint>): Task {
        fun at(frac: Double) = fixes[(fixes.size * frac).toInt().coerceIn(0, fixes.lastIndex)]
        val centres = listOf(0.05, 0.35, 0.60, 0.90).map { at(it) }
        return Task(
            id = "bir-nav",
            name = "Bir Billing Nav Test",
            waypoints = centres.mapIndexed { i, p ->
                Waypoint(lat = p.latitude, lon = p.longitude, label = "B$i", radius = 400.0)
            },
        )
    }

    /**
     * **CLAIM.** Flying the recorded Bir Billing track through a task whose
     * cylinders sit on that track tags every waypoint exactly once, in task
     * order, and the active target is always the next un-tagged waypoint until
     * the task completes (then there's no target).
     */
    @Test
    fun `active waypoint advances in order as the flight enters each cylinder`() {
        val fixes = loadRichard().fixes.filter { it.fixValid }.map { GeoPoint(it.latitude, it.longitude) }
        assertThat(fixes.size).isGreaterThan(1000)

        val task = coLocatedTask(fixes)
        val expectedOrder = task.waypoints.map { it.id }

        var tagged = emptySet<String>()
        val tagOrder = mutableListOf<String>()

        for (own in fixes) {
            val next = TaskNavigator.nextWaypoint(task, tagged) ?: break // task complete
            // The "next waypoint" guidance always targets the first un-tagged point.
            assertThat(next.id).isEqualTo(expectedOrder[tagOrder.size])
            if (TaskNavigator.isReached(own, next)) {
                tagged = tagged + next.id
                tagOrder += next.id
            }
        }

        // Every cylinder was entered, and in order — the target advanced through
        // the whole task as the pilot flew it.
        assertThat(tagOrder).isEqualTo(expectedOrder)
        assertThat(TaskNavigator.nextWaypoint(task, tagged)).isNull() // no target once done
    }

    /**
     * **CLAIM.** A waypoint shows its human description in preference to the
     * terse code — "B4" reads as "Gold's Point" in the guidance.
     */
    @Test
    fun `waypoint displayName prefers description over the code`() {
        assertThat(Waypoint(lat = 0.0, lon = 0.0, label = "B4", description = "Gold's Point").displayName)
            .isEqualTo("Gold's Point")
        assertThat(Waypoint(lat = 0.0, lon = 0.0, label = "B4").displayName).isEqualTo("B4")
        assertThat(Waypoint(lat = 0.0, lon = 0.0, label = "B4", description = "  ").displayName)
            .isEqualTo("B4") // blank description falls back to the code
    }

    /**
     * **CLAIM.** Before a cylinder is reached, the target really is "ahead" — the
     * pilot can fly the bearing to it. (The off-screen chip draws this bearing.)
     */
    @Test
    fun `there is a finite bearing to the active waypoint while it is still ahead`() {
        val fixes = loadRichard().fixes.filter { it.fixValid }.map { GeoPoint(it.latitude, it.longitude) }
        val task = coLocatedTask(fixes)
        val firstTarget = TaskNavigator.nextWaypoint(task, emptySet())!!
        val start = fixes.first()
        val bearing = start.bearingTo(GeoPoint(firstTarget.lat, firstTarget.lon))
        assertThat(bearing).isFinite()
        assertThat(start.distanceToAsDouble(GeoPoint(firstTarget.lat, firstTarget.lon))).isGreaterThan(0.0)
    }
}
