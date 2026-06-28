package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Spot
import com.ternparagliding.model.SpotSource
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.mapReducer
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Claim **K6 · Create on the map snaps to an existing spot** — dropping within ~150 m of an
 * existing library spot *references* that spot instead of minting a near-duplicate. The radius
 * is real-world metres (zoom-independent), and the snap runs even for a forced add-from-map drop,
 * because placing the crosshair over a marker means "pick it". A drop in open space still mints a
 * fresh USER spot. Backs the K6 "create / move on the map" line in claims.md.
 */
class TaskSnapClaimsTest {

    private val spot = Spot(id = "B42", code = "B42", name = "Butte", lat = 32.0, lon = 76.0, source = SpotSource.IMPORTED)
    private fun state() = MapState(
        tasks = listOf(Task(id = "t1", name = "Task 1", waypoints = listOf(
            Waypoint(id = "t1p1", lat = 32.0, lon = 76.0, label = "B42", spotId = "B42")))),
        waypointLibrary = listOf(spot),
        selectedTaskId = "t1",
    )

    // ~111 m north of the spot — comfortably inside the 150 m snap radius.
    private val near = GeoPoint(32.001, 76.0)
    // ~1.1 km away — well outside it.
    private val far = GeoPoint(32.01, 76.0)

    @Test
    fun `a drop near an existing spot references it, no duplicate`() {
        val s = mapReducer(state(), MapAction.LongPressMap(near))
        // No new spot minted — the library still holds exactly the one spot.
        assertThat(s.waypointLibrary).hasSize(1)
        // The selected task gained a second point that references the SAME spot.
        val t1 = s.tasks.single { it.id == "t1" }
        assertThat(t1.waypoints).hasSize(2)
        assertThat(t1.waypoints.last().spotId).isEqualTo("B42")
    }

    @Test
    fun `a forced add-from-map drop still snaps to an existing spot`() {
        val s = mapReducer(state(), MapAction.LongPressMap(near, forceCreate = true))
        assertThat(s.waypointLibrary).hasSize(1)
        assertThat(s.tasks.single { it.id == "t1" }.waypoints.last().spotId).isEqualTo("B42")
    }

    @Test
    fun `a drop in open space mints a fresh USER spot`() {
        val s = mapReducer(state(), MapAction.LongPressMap(far, forceCreate = true))
        assertThat(s.waypointLibrary).hasSize(2)
        val newSpot = s.waypointLibrary.single { it.id != "B42" }
        assertThat(newSpot.source).isEqualTo(SpotSource.USER)
        // …and the new point references the new spot, not the old one.
        assertThat(s.tasks.single { it.id == "t1" }.waypoints.last().spotId).isEqualTo(newSpot.id)
    }

    /** B2: a fresh drop selects the new point as **new**, so the UI highlights it but does
     *  NOT force the full editor open — the pilot keeps dropping; tapping a point edits it. */
    @Test
    fun `a fresh drop flags the selection as new so the editor stays closed`() {
        val s = mapReducer(state(), MapAction.LongPressMap(far, forceCreate = true))
        val sel = s.selectedWaypoint
        assertThat(sel).isNotNull()
        assertThat(sel!!.isNew).isTrue()
        // It still points at the just-created waypoint (highlighted on the map).
        assertThat(sel.waypointId).isEqualTo(s.tasks.single { it.id == "t1" }.waypoints.last().id)
    }
}
