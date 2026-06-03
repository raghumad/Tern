package com.ternparagliding.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Route
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.CacheManager
import com.ternparagliding.utils.RouteCache
import com.ternparagliding.utils.RouteIOManager
import com.ternparagliding.utils.MapVisualTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Persistence proven against a REAL competition task — SRS Skywalk Edition
 * 2026, Task 1 (Airtribune id 6873): a real .xctsk fixture imported through
 * the real parser, saved through the real app path (dispatch AddRoute → the
 * RoutePersistence observer → RouteCache), then retrieved offline.
 *
 * No synthetic waypoints, no direct cacheRoute(), no vacuous "no-stutter"
 * assertion — every step exercises what the app actually does.
 */
@RunWith(AndroidJUnit4::class)
class RoutePersistenceTest : MapVisualTest() {

    private lateinit var routeCache: RouteCache

    @Before
    fun initCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CacheManager.initialize(context)
        routeCache = CacheManager.routeCache
        routeCache.clearCache()
    }

    private fun loadRealTask(): Route {
        // Real fixture authored from the published SRS-2026-2 Task 1 (.wpt + task table).
        val xctsk = InstrumentationRegistry.getInstrumentation().context.assets
            .open("tasks/srs-2026-2-task1.xctsk").readBytes().decodeToString()
        return RouteIOManager.parseXctskContent(xctsk)
            ?: throw AssertionError("failed to parse the real SRS task .xctsk")
    }

    @Test
    fun testRoutePersistence() = scenario("Real competition task persists offline") {
        story("As a pilot who imports a published competition task at home, I want it saved to disk and available at launch with no internet.") {
            lateinit var task: Route

            given("I import the real SRS Skywalk 2026 Task 1 from its .xctsk file") {
                task = loadRealTask()

                // The import must reflect the real task (this also guards the
                // official-XCTSK parsing: goal = last turnpoint, top-level SSS gate).
                assertEquals("turnpoint count", 9, task.waypoints.size)
                assertEquals("turnpoints (TP1..TP5)", 5, task.waypoints.count { it.type == LocationType.TURNPOINT })

                val to = task.waypoints.first()
                assertEquals(LocationType.LAUNCH, to.type)
                assertEquals("D18", to.label)
                assertEquals(45.795589, to.lat, 1e-5)
                assertEquals(11.668911, to.lon, 1e-5)

                val sss = task.waypoints.first { it.type == LocationType.SSS }
                assertEquals("A02", sss.label)
                assertEquals(5500.0, sss.radius!!, 0.5)
                assertEquals("SSS start gate", "13:15", sss.openTime)

                val ess = task.waypoints.first { it.type == LocationType.ESS }
                assertEquals("B52", ess.label)
                assertEquals(45.778967, ess.lat, 1e-5)

                val goal = task.waypoints.last()
                assertEquals("goal = last turnpoint", LocationType.GOAL, goal.type)
                assertEquals(400.0, goal.radius!!, 0.5)
                assertEquals("goal deadline", "18:00", goal.closeTime)
            }

            `when`("I add the task in the app (the real save path: AddRoute → RoutePersistence → cache)") {
                val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                composeTestRule.runOnUiThread { store.dispatch(MapAction.AddRoute(task)) }
                // Persistence is async (observer on Dispatchers.IO) — poll for it.
                composeTestRule.waitUntil(timeoutMillis = 8000) {
                    routeCache.getCachedRoute(task.id) != null
                }
            }

            this.then("the task is retrievable offline with its coordinates intact") {
                val cached = routeCache.getCachedRoute(task.id)
                assertNotNull("route not persisted via the app path", cached)
                assertEquals(9, cached!!.waypoints.size)

                // Spot-check geometry survived the disk round-trip.
                val b21 = cached.waypoints.first { it.label == "B21" }
                assertEquals(45.705817, b21.lat, 1e-4)
                assertEquals(11.564150, b21.lon, 1e-4)
                assertEquals(5000.0, b21.radius!!, 0.5)
                assertEquals(LocationType.GOAL, cached.waypoints.last().type)
            }

            and("the FlexBuffer + index files exist on device storage") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val cacheDir = File(context.cacheDir, "route_cache")
                val flexFile = File(cacheDir, "${task.id.uppercase()}_route.flex")
                val idxFile = File(cacheDir, "${task.id.uppercase()}_route.idx")
                assertTrue("FlexBuffer file should exist", flexFile.exists())
                assertTrue("Index file should exist", idxFile.exists())
                assertTrue("FlexBuffer file should not be empty", flexFile.length() > 0)
            }

            and("the offline route collection includes this task") {
                val all = routeCache.getAllCachedRoutes()
                assertTrue(all.any { it.id.equals(task.id, ignoreCase = true) })
            }

            and("the imported real task is shown on the map, auto-framed", takeScreenshot = true) {
                // Visual evidence in the report: select the (already-added) task
                // so it auto-frames; the real SRS race renders end-to-end.
                val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                composeTestRule.runOnUiThread { store.dispatch(MapAction.SelectRoute(task.id)) }
                composeTestRule.waitForIdle()
                // Auto-fit + give the ONLINE basemap tiles time to fetch for a
                // fresh region (Bassano, IT) so the report screenshot isn't a
                // black base. Route overlays render immediately regardless.
                Thread.sleep(6000)
            }
        }
    }
}
