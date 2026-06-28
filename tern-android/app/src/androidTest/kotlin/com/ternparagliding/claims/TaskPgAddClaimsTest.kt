package com.ternparagliding.claims

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.ternparagliding.map.MapDriver
import com.ternparagliding.model.SpotSource
import com.ternparagliding.model.Task
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.WeatherActions
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.spatialk.geojson.Point
import org.osmdroid.util.GeoPoint

/**
 * Claim **K15 · L1 (pilot outcome)** — adding a **live PG spot** to a task via its
 * weather sheet. This one genuinely needs live data: it moves the map to a PG-dense
 * region (triggering the country preload), waits for real spots to load, then drives
 * the real "Add to task" affordance and asserts the spot joins the task as a
 * PG_SPOT-provenance [com.ternparagliding.model.Spot].
 *
 * If no live PG data loads (offline / unsupported region), the precondition isn't met
 * and the test **skips** (JUnit assumption) rather than failing falsely — an honest
 * representation of a data-dependent journey.
 */
@RunWith(AndroidJUnit4::class)
class TaskPgAddClaimsTest {

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
        Thread.sleep(2_000)
        map.onUi { it.dispatch(MapAction.ClearAllTasks) }
        map.waitForStore("tasks cleared") { it.tasks.isEmpty() }
    }

    @After
    fun tearDown() = map.close()

    @Test
    fun adding_a_live_pg_spot_to_a_task_via_the_weather_sheet() {
        // Nudge the map to a PG-dense region (Annecy / French Alps) → triggers the
        // country preload + the PG-spot overlay query. (If the device re-centres to its
        // own GPS, spots load for that region instead — either is fine.)
        map.onUi {
            it.dispatch(MapAction.UpdateCenter(GeoPoint(45.92, 6.87)))
            it.dispatch(MapAction.UpdateZoom(11.0))
        }

        val loaded = waitForPgSpots(45_000)
        Assume.assumeTrue("no live PG data loaded for the test region (offline?)", loaded)

        // Pick a real loaded spot and build the id the map click would ("name|lat|lon").
        val f = map.state.pgSpotGeoJson!!.features.first()
        val name = (f.properties?.get("name") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "Site"
        val pt = f.geometry as Point
        val lat = pt.coordinates.latitude
        val lon = pt.coordinates.longitude
        val pgId = "$name|$lat|$lon"

        map.onUi {
            it.dispatch(MapAction.AddTask(Task(id = "tpg", name = "PG Task", waypoints = emptyList())))
            it.dispatch(MapAction.SelectTask("tpg"))
            // Open the weather sheet for this real spot (what tapping it on the map does).
            it.dispatch(WeatherActions.ShowWeatherDetails(pgId, name, null, null))
        }

        map.tapText("Add to task") // the contextual affordance (only shown with a task selected)

        map.waitForStore("the live PG spot to join the task as a PG_SPOT spot") { st ->
            val wp = st.tasks.firstOrNull { it.id == "tpg" }?.waypoints?.firstOrNull()
            wp != null && st.waypointLibrary.any {
                it.id == wp.spotId && it.source == SpotSource.PG_SPOT && it.sourceId == pgId
            }
        }
        assertThat(map.appAlive()).isTrue()
    }

    private fun waitForPgSpots(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if ((map.state.pgSpotGeoJson?.features?.size ?: 0) > 0) return true
            Thread.sleep(500)
        }
        return false
    }
}
