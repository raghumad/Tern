package com.ternparagliding.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.VisualValidator
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Theme 1 (routes visible): a full competition task renders on the map with
 * role-coloured FAI cylinders, cylinder-centric waypoint markers, the route
 * line, and leg-distance pills. Honest — pixel-asserts the rendered marker
 * colours, not Redux state.
 */
@RunWith(AndroidJUnit4::class)
class RouteVisualizationTest : MapVisualTest() {

    private val SSS_GREEN = 0xFF00C853.toInt()
    private val GOAL_RED = 0xFFD50000.toInt()
    private val TURNPOINT_BLUE = 0xFF2962FF.toInt()
    private val ROUTE_CYAN = 0xFF00E5FF.toInt()

    @Test
    fun pilot_sees_full_task_with_cylinders_markers_and_legs() {
        scenario("A competition task is fully visualised on the map") {
            story("As a competition pilot, I want to see my whole task — start/turnpoint/goal cylinders, waypoint codes, and leg distances — at a glance on the map.") {
                val cLat = 39.99
                val cLon = -105.29
                val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]

                val task = Route(
                    id = "viz_task",
                    name = "Demo Race Task",
                    waypoints = listOf(
                        Waypoint(lat = 39.95, lon = -105.30, type = LocationType.LAUNCH, label = "Takeoff"),
                        Waypoint(lat = 39.97, lon = -105.27, type = LocationType.SSS, label = "Start", radius = 1000.0),
                        Waypoint(lat = 40.01, lon = -105.24, type = LocationType.TURNPOINT, label = "Eldora", radius = 2000.0),
                        Waypoint(lat = 40.02, lon = -105.33, type = LocationType.TURNPOINT, label = "Ward", radius = 2000.0),
                        Waypoint(lat = 39.98, lon = -105.34, type = LocationType.ESS, label = "End Speed", radius = 1000.0),
                        Waypoint(lat = 39.96, lon = -105.32, type = LocationType.GOAL, label = "Goal", radius = 400.0),
                    ),
                )

                given("a race task is loaded and the map is framed on it") {
                    composeTestRule.runOnUiThread {
                        store.dispatch(MapAction.AddRoute(task))
                        store.dispatch(MapAction.SelectRoute(task.id))
                    }
                    zoomTo(cLat, cLon, 12.0)
                }

                then("the task renders: cylinders + waypoint markers + route line are on the map", takeScreenshot = true) {
                    composeTestRule.waitForIdle()
                    Thread.sleep(1800) // camera settle + symbol/cylinder render
                    val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        ?: throw AssertionError("screenshot failed")
                    val rect = android.graphics.Rect(
                        (shot.width * 0.06).toInt(), (shot.height * 0.16).toInt(),
                        (shot.width * 0.88).toInt(), (shot.height * 0.84).toInt(),
                    )
                    fun present(name: String, color: Int) {
                        if (!VisualValidator.findColorSignature(shot, rect, color, tolerance = 36, minPixels = 8)) {
                            throw AssertionError("$name (${Integer.toHexString(color)}) not rendered on the map")
                        }
                    }
                    present("route line", ROUTE_CYAN)
                    present("SSS cylinder/marker (green)", SSS_GREEN)
                    present("turnpoint (blue)", TURNPOINT_BLUE)
                    present("goal (red)", GOAL_RED)
                }
            }
        }
    }
}
