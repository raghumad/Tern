package com.ternparagliding.claims

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.ternparagliding.map.MapDriver
import com.ternparagliding.model.Spot
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.task.TaskResolver
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.resolvedTasks
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Claim **K6 · L1 (pilot outcome)** for the **sheet / editor** journeys — the parts of
 * the task surface that are ordinary Compose (bottom sheets, the per-point editor), NOT
 * the GL map. UiAutomator can drive these via the accessibility tree (text / desc /
 * testTag-as-resource-id), so unlike the GL single-tap journeys these get a *real
 * gesture → real outcome* proof. State is seeded through the live store (the entry to
 * each sheet is itself a real tap); the assertion is the outcome the pilot depends on.
 */
@RunWith(AndroidJUnit4::class)
class TaskEditorClaimsTest {

    @get:Rule
    val perms: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val map = MapDriver()

    @Before
    fun setUp() {
        map.launch()
        map.waitForMapReady()
        Thread.sleep(2_000) // let first paint settle so the dock/sheets are live
        map.onUi {
            it.dispatch(MapAction.ClearAllTasks)
            it.dispatch(MapAction.ClearWaypointLibrary)
        }
        map.waitForStore("a clean task/library slate") { it.tasks.isEmpty() && it.waypointLibrary.isEmpty() }
    }

    @After
    fun tearDown() = map.close()

    /** #2 Build a task from the library: open the ribbon → "Add from library" → tick a
     *  spot → "Add 1 to task". The picked spot joins the task, referencing the library
     *  entry (spotId). Real sheet taps, not Redux pokes. */
    @Test
    fun adding_from_library_appends_the_picked_spot_to_the_task() {
        val spot = Spot(id = "PICK1", code = "PICK1", name = "Picked Peak", lat = 47.0, lon = -120.0)
        map.onUi {
            it.dispatch(MapAction.SetWaypointLibrary(listOf(spot)))
            it.dispatch(MapAction.AddTask(Task(id = "tpick", name = "Pick Task", waypoints = emptyList())))
            it.dispatch(MapAction.SelectTask("tpick"))
        }
        map.waitForStore("the task to be selected") { it.selectedTaskId == "tpick" }

        map.tapDesc("Tasks & waypoints") // dock → ribbon (a task is selected)
        map.tapText("Add from library")  // ribbon → picker sheet
        map.tapText("PICK1")             // tick the row (pick order = task order)
        map.tapText("Add 1 to task")     // commit

        map.waitForStore("the picked spot to join the task") { st ->
            st.tasks.firstOrNull { it.id == "tpick" }?.waypoints?.any { it.spotId == "PICK1" } == true
        }
        assertThat(map.appAlive()).isTrue()
    }

    /** #5 Edit a spot's identity → flows to every task using it. Identity now lives in
     *  Workflow A: from the per-point editor (B2) drill into "Edit waypoint…" (Workflow A),
     *  rename there; a second task referencing the same spot shows the new name (resolver). */
    @Test
    fun editing_a_spot_name_flows_to_the_other_task() {
        val spot = Spot(id = "SH", code = "SH", name = "Old", lat = 47.0, lon = -120.0)
        val t1 = Task(id = "t1", name = "T1",
            waypoints = listOf(Waypoint(id = "t1p", lat = 47.0, lon = -120.0, label = "SH", spotId = "SH")))
        val t2 = Task(id = "t2", name = "T2",
            waypoints = listOf(Waypoint(id = "t2p", lat = 47.0, lon = -120.0, label = "SH", spotId = "SH")))
        map.onUi {
            it.dispatch(MapAction.SetWaypointLibrary(listOf(spot)))
            it.dispatch(MapAction.AddTask(t1))
            it.dispatch(MapAction.AddTask(t2))
            it.dispatch(MapAction.SelectWaypoint("t1", "t1p")) // opens the per-point editor (B2)
        }
        map.waitForStore("the per-point editor armed on t1") { it.selectedWaypoint?.taskId == "t1" }

        map.tapRes("edit_waypoint_link")        // B2 → Workflow A (edit the spot)
        map.setField("spot_name_field", "Gold's Point")

        map.waitForStore("the rename to flow to the other task") { st ->
            st.resolvedTasks().firstOrNull { it.id == "t2" }
                ?.waypoints?.firstOrNull()?.description == "Gold's Point"
        }
        assertThat(map.appAlive()).isTrue()
    }

    /** #6 Edit task features incl. clearing: set the cylinder radius, then blank it →
     *  the field clears (falls back to the default), proving the clearable path. */
    @Test
    fun cylinder_radius_can_be_set_then_cleared_in_the_editor() {
        val spot = Spot(id = "CY", code = "CY", name = "Cyl", lat = 47.0, lon = -120.0)
        val t = Task(id = "tc", name = "TC",
            waypoints = listOf(Waypoint(id = "tcp", lat = 47.0, lon = -120.0, label = "CY", spotId = "CY")))
        map.onUi {
            it.dispatch(MapAction.SetWaypointLibrary(listOf(spot)))
            it.dispatch(MapAction.AddTask(t))
            it.dispatch(MapAction.SelectWaypoint("tc", "tcp"))
        }
        map.waitForStore("the editor to be armed") { it.selectedWaypoint?.waypointId == "tcp" }

        map.setField("wp_radius_field", "400")
        map.waitForStore("the radius to be set to 400") { st ->
            st.tasks.firstOrNull { it.id == "tc" }?.waypoints?.firstOrNull()?.radius == 400.0
        }

        map.clearField("wp_radius_field")
        map.waitForStore("the radius to be cleared (default)") { st ->
            st.tasks.firstOrNull { it.id == "tc" }?.waypoints?.firstOrNull()?.radius == null
        }
        assertThat(map.appAlive()).isTrue()
    }

    /** #10 Resolver: an edited spot flows to the resolved task; deleting that spot from
     *  the library leaves the task point *flyable from its snapshot* and flagged stale —
     *  and the live map survives the re-render of the now-orphaned reference (P2). */
    @Test
    fun deleting_a_referenced_spot_leaves_points_flyable_and_stale() {
        val spot = Spot(id = "ST", code = "ST", name = "Stale", lat = 47.0, lon = -120.0)
        val t = Task(id = "ts", name = "TS",
            waypoints = listOf(Waypoint(id = "tsp", lat = 47.0, lon = -120.0, label = "ST", spotId = "ST")))
        map.onUi {
            it.dispatch(MapAction.SetWaypointLibrary(listOf(spot)))
            it.dispatch(MapAction.AddTask(t))
            it.dispatch(MapAction.SelectTask("ts"))
        }
        map.waitForStore("the task to be present") { st -> st.tasks.any { it.id == "ts" } }

        map.onUi { it.dispatch(MapAction.UpdateSpot("ST", name = "Renamed")) }
        map.waitForStore("the rename to flow to the resolved task") { st ->
            st.resolvedTasks().firstOrNull { it.id == "ts" }?.waypoints?.firstOrNull()?.description == "Renamed"
        }

        map.onUi { it.dispatch(MapAction.RemoveLibraryWaypoint("ST")) }
        Thread.sleep(700) // let the map recompose with the orphaned reference

        val wp = map.state.resolvedTasks().firstOrNull { it.id == "ts" }?.waypoints?.firstOrNull()
        assertThat(wp).isNotNull()
        assertThat(wp!!.lat).isWithin(1e-9).of(47.0) // still flyable from the snapshot
        assertThat(TaskResolver.isMissingLink(wp, map.state.waypointLibrary)).isTrue()
        assertThat(map.appAlive()).isTrue() // survived the orphaned re-render → no crash
    }
}
