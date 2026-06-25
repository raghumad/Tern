package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.SpotSource
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.mapReducer
import com.ternparagliding.utils.cache.MapOverlayCacheUtils
import com.ternparagliding.utils.cache.TaskCacheCodec
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Claim **K12 · Spot-reference model** (Stage C) — a task point references a [com.ternparagliding.model.Spot]
 * by `spotId` and persists that reference, so library links survive an app restart
 * (the latent bug: `libraryWaypointId` was never written to disk). Every task point
 * references a spot — ad-hoc map drops auto-create a USER spot; an imported task
 * seeds the library and binds its points.
 */
class TaskSpotModelClaimsTest {

    @Test
    fun `spotId and description survive the persistence round-trip`() {
        val task = Task(
            id = "t", name = "Day 3",
            waypoints = listOf(
                Waypoint(id = "p1", lat = 32.21, lon = 76.43, type = LocationType.SSS,
                    label = "B42", description = "Gold's Point", spotId = "B42",
                    radius = 1000.0, openTime = "13:00"),
            ),
        )
        val round = TaskCacheCodec.reconstructTaskFromFeatures("t", TaskCacheCodec.convertTaskToFeatures(task))!!
        val wp = round.waypoints.first()
        assertThat(wp.spotId).isEqualTo("B42")          // the reference survives — the core fix
        assertThat(wp.description).isEqualTo("Gold's Point")
        assertThat(wp.type).isEqualTo(LocationType.SSS)
        assertThat(wp.radius).isEqualTo(1000.0)
        assertThat(wp.openTime).isEqualTo("13:00")
    }

    @Test
    fun `a v0 cached task (no spotId) reconstructs as a snapshot-only point, still flyable`() {
        // A feature written before Stage C: properties.waypoints lack spotId/description.
        val v0 = MapOverlayCacheUtils.OverlayFeature(
            internalId = "t",
            feature = mapOf(
                "type" to "Feature",
                "properties" to mapOf(
                    "taskId" to "t", "taskName" to "Legacy", "isVisible" to true,
                    "waypoints" to listOf(
                        mapOf("id" to "p1", "lat" to 32.0, "lon" to 76.0, "type" to "TURNPOINT", "label" to "B42"),
                    ),
                ),
            ),
            centroid = GeoPoint(32.0, 76.0),
            hilbertIndex = 0L,
        )
        val wp = TaskCacheCodec.reconstructTaskFromFeatures("t", listOf(v0))!!.waypoints.first()
        assertThat(wp.spotId).isNull()                  // legacy point: no reference yet
        assertThat(wp.lat).isWithin(1e-9).of(32.0)      // flies from the snapshot
        assertThat(wp.label).isEqualTo("B42")
    }

    @Test
    fun `a long-press drop auto-creates a USER spot the new point references`() {
        val s = mapReducer(MapState(), MapAction.LongPressMap(GeoPoint(46.0, 7.0)))
        val wp = s.tasks.single().waypoints.single()
        assertThat(wp.spotId).isNotNull()
        val spot = s.waypointLibrary.single { it.id == wp.spotId }
        assertThat(spot.source).isEqualTo(SpotSource.USER)
        assertThat(spot.lat).isWithin(1e-9).of(46.0)
        assertThat(spot.lon).isWithin(1e-9).of(7.0)
    }

    @Test
    fun `importing a task seeds IMPORTED spots and binds its points`() {
        // A task parsed from a file: points carry codes (label) but no spotId yet.
        val imported = Task(
            id = "day1", name = "Day 1",
            waypoints = listOf(
                Waypoint(id = "p1", lat = 32.07, lon = 76.70, label = "B01", description = "Bir Takeoff"),
                Waypoint(id = "p2", lat = 32.21, lon = 76.43, label = "B42"),
            ),
        )
        val s = mapReducer(MapState(), MapAction.AddImportedTask(imported))

        // Library seeded with IMPORTED spots keyed by uppercased code.
        assertThat(s.waypointLibrary.map { it.id }).containsAtLeast("B01", "B42")
        assertThat(s.waypointLibrary.all { it.source == SpotSource.IMPORTED }).isTrue()
        // Task points now reference those spots.
        assertThat(s.tasks.single().waypoints.map { it.spotId }).containsExactly("B01", "B42").inOrder()
    }
}
