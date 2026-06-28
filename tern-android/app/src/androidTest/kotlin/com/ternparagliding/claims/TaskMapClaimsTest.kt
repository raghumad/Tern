package com.ternparagliding.claims

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.ternparagliding.map.MapDriver
import com.ternparagliding.redux.MapAction
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Claim **K6 · L1 (pilot outcome)** — honest, on-device proof of the task/waypoint
 * map surface, driven by real screen gestures via [MapDriver] (the GL surface the
 * JVM claims can't see). Asserts the *outcome the pilot depends on*, not Redux flags
 * — this is the layer that long-press died in and the delete-crash shipped through.
 */
@RunWith(AndroidJUnit4::class)
class TaskMapClaimsTest {

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
        Thread.sleep(2_000) // let the map finish first paint so gestures are live
        // Clean task slate (library spots may persist; the journeys don't depend on it).
        map.onUi { it.dispatch(MapAction.ClearAllTasks) }
        map.waitForStore("tasks cleared") { it.tasks.isEmpty() }
    }

    @After
    fun tearDown() = map.close()

    /** #1 Long-press is inert: it must NOT drop a task/waypoint. Creation is explicit
     *  now — "Create New Task" (task list) and the "Add from map" crosshair — because
     *  auto-creating on every long-press was redundant with those and the source of
     *  accidental stray tasks (the recurring "1" near Thornton). The pilot invariant:
     *  a long-press on empty map changes nothing. */
    @Test
    fun longPress_does_not_create_a_waypoint() {
        repeat(3) { map.longPressCenter() } // hammer it; none should create
        Thread.sleep(1_000) // give any (unwanted) dispatch time to land
        assertThat(map.state.tasks.any { it.waypoints.isNotEmpty() }).isFalse()
        assertThat(map.appAlive()).isTrue()
    }

    /** The deliberate creation path still works: the crosshair's forceCreate drop mints
     *  a waypoint with its auto USER spot. (Driven via the store — the crosshair action
     *  bar is L2 chrome; see ux/validation-checklist.md.) */
    @Test
    fun deliberate_drop_creates_a_waypoint_with_an_auto_USER_spot() {
        map.createWaypointAtCenter()
        val s = map.state
        val wp = s.tasks.first { it.waypoints.isNotEmpty() }.waypoints.first()
        assertThat(wp.spotId).isNotNull()
        assertThat(s.waypointLibrary.any { it.id == wp.spotId }).isTrue()
        assertThat(map.appAlive()).isTrue()
    }

    /** Tapping a waypoint selects it — the gesture that opens the per-point editor
     *  (the entry to the rename / clear-gate journeys).
     *
     *  IGNORED (2026-06): on the Ulefone this session, **no** synthetic single-tap
     *  reaches the MapLibre GL SurfaceView — `device.click`, `input tap`, and short
     *  zero-/tiny-distance swipes all fail to fire the gesture detector's
     *  onSingleTapConfirmed; only the 800 ms long-press swipe registers (onLongPress
     *  fires mid-gesture). This is an injection limitation, not an app regression:
     *  the tap→select wiring is unchanged. Re-enable if/when a tap injection that
     *  reaches the surface is found. Pilot-verified meanwhile via ux/validation-checklist.md. */
    @Ignore("Single-tap can't be injected into the GL SurfaceView on this device; see KDoc.")
    @Test
    fun tapping_a_waypoint_selects_it() {
        map.createWaypointAtCenter()
        // The drop selects the point as "new" (no editor); clear it, then prove tap→select.
        map.onUi { it.dispatch(MapAction.DeselectWaypoint) }
        map.waitForStore("selection cleared") { it.selectedWaypoint == null }

        map.tapUntil("the tap to select the waypoint",
            map.device.displayWidth / 2, map.device.displayHeight / 2) { it.selectedWaypoint != null }
        assertThat(map.appAlive()).isTrue()
    }

    /** #7 Move a waypoint (move-mode): arm the move, then a single map tap drops the
     *  point at the tapped spot — no press-and-hold drag. Proves the onMapClick commit
     *  path (UpdateWaypointDrag → EndWaypointDrag) fires on a real tap and the point
     *  actually moves, without a crash. (Arming via the store; the editor "Move on Map"
     *  button is L2 — see ux/validation-checklist.md.)
     *
     *  IGNORED (2026-06): the commit is an onMapClick (single tap), which can't be
     *  injected into the GL SurfaceView on this device (see [tapping_a_waypoint_selects_it]).
     *  The move *logic* is proven by `TaskMutationClaimsTest` (Start→Update→End moves the
     *  spot; Cancel restores it) and the commit wiring by click-dispatch-order analysis
     *  (onMapClick fires first, consumes only in move-mode). Pilot-verified via the L2 checklist. */
    @Ignore("Move commit is a single map tap, which can't be injected on this device; see KDoc.")
    @Test
    fun moveMode_relocates_a_waypoint_on_a_single_tap() {
        map.createWaypointAtCenter()
        val task = map.state.tasks.first { it.waypoints.isNotEmpty() }
        val wp = task.waypoints.first()
        val before = wp.lat to wp.lon

        // Arm move-mode for that point (what the editor's "Move on Map" button dispatches).
        map.onUi {
            it.dispatch(MapAction.SelectWaypoint(task.id, wp.id))
            it.dispatch(MapAction.StartWaypointDrag(task.id, wp.id))
        }
        map.waitForStore("move-mode armed") { it.selectedWaypoint?.isDragging == true }

        // A single tap well away from centre → the point relocates there and the move ends.
        map.tapUntil("the waypoint to relocate (move committed)",
            map.device.displayWidth / 2, map.device.displayHeight / 4) { st ->
            val now = st.tasks.firstOrNull { it.id == task.id }?.waypoints?.firstOrNull { it.id == wp.id }
            now != null && (now.lat != before.first || now.lon != before.second) &&
                st.selectedWaypoint?.isDragging != true
        }
        assertThat(map.appAlive()).isTrue()
    }

    /** #8 Delete a waypoint → no crash. The deleted point's USER spot is no longer in
     *  a visible task, so it renders via the library overlay — the exact path that
     *  crashed on a NaN alt before the render-safety invariant. */
    @Test
    fun deleting_a_waypoint_does_not_crash_the_map() {
        map.createWaypointAtCenter()
        val task = map.state.tasks.first { it.waypoints.isNotEmpty() }
        val wp = task.waypoints.first()

        map.onUi { it.dispatch(MapAction.RemoveWaypoint(task.id, wp.id)) }
        map.waitForStore("the waypoint to be removed") { st ->
            st.tasks.firstOrNull { it.id == task.id }?.waypoints?.isEmpty() ?: true
        }
        Thread.sleep(700) // let the map recompose & render the now-orphaned spot

        assertThat(map.appAlive()).isTrue() // survived the re-render → no crash (P2)
        assertThat(map.state.waypointLibrary.any { it.id == wp.spotId }).isTrue()
    }
}
