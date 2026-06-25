package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.SpotSource
import com.ternparagliding.model.Task
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.mapReducer
import org.junit.Test

/**
 * Claim **K15 · PG spots are spots too** (Stage C) — pulling a PG spot into a task
 * find-or-creates a PG_SPOT-provenance spot in the library (so it's identity in one
 * place, like any other spot) and references it from the task. Picking the same PG
 * spot again reuses the one spot (matched by provenance), never duplicating it.
 */
class TaskPgSpotClaimsTest {

    private fun state() = MapState(tasks = listOf(Task(id = "t1", name = "Day 1")))

    private fun pick(taskId: String) = MapAction.AddPgSpotToTask(
        taskId = taskId, pgSpotId = "pge:12345", code = "BIR", name = "Bir Takeoff",
        lat = 32.07, lon = 76.70, alt = 2400.0,
    )

    @Test
    fun `picking a PG spot creates a PG_SPOT spot the task references`() {
        val s = mapReducer(state(), pick("t1"))
        val spot = s.waypointLibrary.single()
        assertThat(spot.source).isEqualTo(SpotSource.PG_SPOT)
        assertThat(spot.sourceId).isEqualTo("pge:12345")
        assertThat(spot.code).isEqualTo("BIR")
        val wp = s.tasks.single().waypoints.single()
        assertThat(wp.spotId).isEqualTo(spot.id)
        assertThat(wp.description).isEqualTo("Bir Takeoff")
    }

    @Test
    fun `picking the same PG spot twice reuses the one spot`() {
        val once = mapReducer(state(), pick("t1"))
        val twice = mapReducer(once, pick("t1"))
        // One spot in the library (matched by provenance), two task references to it.
        assertThat(twice.waypointLibrary).hasSize(1)
        val spotId = twice.waypointLibrary.single().id
        assertThat(twice.tasks.single().waypoints.map { it.spotId }).containsExactly(spotId, spotId)
    }
}
