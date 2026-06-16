package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.LibraryWaypoint
import com.ternparagliding.model.Task
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.mapReducer

/**
 * Claim **K9 · Tasks are built from the library** (Stage B1) — the pilot picks
 * waypoints from the standalone library and they join the task, in pick order,
 * each linked back to its library entry (libraryWaypointId) with identity carried.
 * Driven through the exact reducer the picker dispatches ([mapReducer]).
 */
class TaskFromLibraryClaimsTest {

    private val library = listOf(
        LibraryWaypoint(id = "B01", code = "B01", name = "Bir Takeoff", lat = 32.07, lon = 76.70, alt = 2400.0),
        LibraryWaypoint(id = "B42", code = "B42", name = "Gold's Point", lat = 32.21, lon = 76.43, alt = 2438.0),
        LibraryWaypoint(id = "B03", code = "B03", name = "Chamera Ridge", lat = 32.17, lon = 76.50, alt = 3000.0),
    )

    private fun state() = MapState(
        tasks = listOf(Task(id = "t", name = "Day 3")),
        selectedTaskId = "t",
        waypointLibrary = library,
    )

    @org.junit.Test
    fun `picked library waypoints join the task in order, linked, with identity carried`() {
        // Pilot picks B42 then B01 (deliberate order → that's the task sequence).
        val s = mapReducer(state(), MapAction.AddLibraryWaypointsToTask("t", listOf("B42", "B01")))

        val wps = s.tasks.first { it.id == "t" }.waypoints
        assertThat(wps.map { it.label }).containsExactly("B42", "B01").inOrder()
        assertThat(wps.map { it.libraryWaypointId }).containsExactly("B42", "B01").inOrder()

        val b42 = wps.first()
        assertThat(b42.description).isEqualTo("Gold's Point")
        assertThat(b42.lat).isWithin(1e-9).of(32.21)
        assertThat(b42.lon).isWithin(1e-9).of(76.43)
        assertThat(b42.alt!!).isWithin(1e-9).of(2438.0)
        // displayName prefers the description (the in-flight readout reads "Gold's Point").
        assertThat(b42.displayName).isEqualTo("Gold's Point")
    }

    @org.junit.Test
    fun `unknown ids are skipped, known ones still added`() {
        val s = mapReducer(state(), MapAction.AddLibraryWaypointsToTask("t", listOf("B01", "NOPE", "B03")))
        assertThat(s.tasks.first().waypoints.map { it.label }).containsExactly("B01", "B03").inOrder()
    }
}
