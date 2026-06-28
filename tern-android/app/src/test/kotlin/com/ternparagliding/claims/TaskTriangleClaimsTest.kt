package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import org.junit.Test

/**
 * Claim **K6 · task-type / FAI-triangle detection.** A planned task is classified as open
 * distance, a flat triangle, or an **FAI triangle** (each side ≥ 28% of the perimeter) — the
 * read that drives the points multiplier and the comp-task summary. Detection must survive the
 * real shapes a triangle takes: not only the bare 4-point `[A,B,C,A]`, but the competition
 * `[SSS, TP1, TP2, TP3, GOAL]` where the start is snapped onto a corner and the goal returns
 * to the start. (Previously hardcoded to exactly four waypoints, so every comp triangle —
 * which has five — was mis-read as open distance.)
 */
class TaskTriangleClaimsTest {

    // An (almost) equilateral triangle near Annecy: each side ≈ 7.7 km.
    private val A = Waypoint(lat = 46.0000, lon = 6.0000, label = "A")
    private val B = Waypoint(lat = 46.0000, lon = 6.1000, label = "B")
    private val C = Waypoint(lat = 46.0602, lon = 6.0500, label = "C")

    private fun task(vararg wps: Waypoint) = Task(name = "t", waypoints = wps.toList())

    @Test
    fun `an open course is open distance, not a triangle`() {
        val open = task(A, B, C) // never returns to the start
        assertThat(open.taskType).isEqualTo(Task.TaskType.OPEN_DISTANCE)
        assertThat(open.faiPoints).isWithin(1e-6).of(open.totalDistanceKm)
    }

    @Test
    fun `a closed near-equilateral triangle is an FAI triangle`() {
        val fai = task(A, B, C, A.copy()) // [A,B,C,A]
        assertThat(fai.taskType).isEqualTo(Task.TaskType.FAI_TRIANGLE)
        assertThat(fai.faiPoints).isWithin(1e-6).of(fai.totalDistanceKm * 2.0)
    }

    @Test
    fun `a long thin closed triangle is a FLAT triangle (28 percent rule fails)`() {
        // Long base, apex tucked near A → the shortest side is far under 28% of the perimeter.
        val far = Waypoint(lat = 46.0000, lon = 6.3000, label = "B2")
        val near = Waypoint(lat = 46.0003, lon = 6.0200, label = "C2")
        val flat = task(A, far, near, A.copy())
        assertThat(flat.taskType).isEqualTo(Task.TaskType.FLAT_TRIANGLE)
        assertThat(flat.faiPoints).isWithin(1e-6).of(flat.totalDistanceKm * 1.5)
    }

    /** The headline fix: a 5-point competition triangle (start snapped onto the first corner,
     *  goal returning to the start) is still an FAI triangle. */
    @Test
    fun `a 5-point competition triangle is detected as FAI`() {
        // SSS at A, TP1 B, TP2 C, TP3 back at A, GOAL at A — five waypoints, three corners.
        val comp = task(A.copy(label = "SSS"), B, C, A.copy(label = "TP3"), A.copy(label = "GOAL"))
        assertThat(comp.taskType).isEqualTo(Task.TaskType.FAI_TRIANGLE)
    }

    /** A start cylinder snapped onto the first corner (consecutive duplicate) collapses away. */
    @Test
    fun `a start co-located with the first corner still reads as a triangle`() {
        val comp = task(A.copy(label = "SSS"), A.copy(label = "TP1"), B, C, A.copy(label = "GOAL"))
        assertThat(comp.taskType).isEqualTo(Task.TaskType.FAI_TRIANGLE)
    }

    /** A genuinely 4-cornered closed loop is NOT a triangle — guard against false positives. */
    @Test
    fun `a closed quadrilateral is open distance, not a triangle`() {
        val d = Waypoint(lat = 46.0602, lon = 5.9500, label = "D")
        val quad = task(A, B, C, d, A.copy()) // four distinct corners
        assertThat(quad.taskType).isEqualTo(Task.TaskType.OPEN_DISTANCE)
    }
}
