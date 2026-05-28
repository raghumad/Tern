package com.ternparagliding.ui
import com.ternparagliding.model.LocationType

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.MapAction
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.MapTestHelper
import com.ternparagliding.utils.ReportGenerator
import com.ternparagliding.ui.components.MapViewModel
import androidx.compose.ui.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint

@RunWith(AndroidJUnit4::class)
class WaypointInteractionUXTest : MapVisualTest() {

    @Test
    fun testPremiumWaypointDragAndDrop() {
        scenario("Premium Waypoint Repositioning") {
            story("As a pilot adjusting my flight plan on the fly, I want to reposition my turnpoints with a single touch and feel immediate, high-quality feedback.") {
                val activity = composeTestRule.activity as com.ternparagliding.TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                val startLat = 40.0150
                val startLon = -105.2705
                val endLat = 40.0200
                val endLon = -105.2800

                val wp1 = Waypoint(lat = startLat, lon = startLon, label = "Start", type = LocationType.LAUNCH)
                val wp2 = Waypoint(lat = 40.0180, lon = -105.2750, label = "Turnpoint 1", type = LocationType.TURNPOINT)
                lateinit var route: Route

                given("I am viewing a 2-waypoint route on the map") {
                    MapTestHelper.grantLocationPermissions()
                    MapTestHelper.injectMockLocation(composeTestRule, startLat, startLon)

                    route = Route(name = "UX Test Route", waypoints = listOf(wp1, wp2))

                    store.dispatch(MapAction.AddRoute(route))
                    store.dispatch(MapAction.SelectRoute(route.id))
                    store.dispatch(MapAction.UpdateCenter(GeoPoint(startLat, startLon)))
                    store.dispatch(MapAction.UpdateZoom(17.0))

                    composeTestRule.waitForIdle()
                }

                `when`("I reposition 'Turnpoint 1' via Redux dispatch (physical drag not available)") {
                    // Select the waypoint, simulate drag via Redux
                    store.dispatch(MapAction.SelectWaypoint(route.id, wp2.id))
                    composeTestRule.waitForIdle()

                    store.dispatch(MapAction.UpdateWaypointDrag(endLat, endLon))
                    composeTestRule.waitForIdle()

                    store.dispatch(MapAction.EndWaypointDrag)
                    composeTestRule.waitForIdle()
                }

                then("the RouteDetailPanel shows the updated route", takeScreenshot = true) {
                    // Deselect waypoint to show route panel
                    store.dispatch(MapAction.DeselectWaypoint)
                    composeTestRule.waitForIdle()

                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isNotEmpty()
                    }
                    composeTestRule.onNodeWithTag("RouteDetailPanel").assertIsDisplayed()
                }

                and("the route is persisted to the cache") {
                    val cache = com.ternparagliding.utils.CacheManager.routeCache
                    val cachedRoute = cache.getCachedRoute(route.id)
                    assert(cachedRoute != null) { "Route not found in cache after drag!" }
                    val cachedWp = cachedRoute!!.waypoints.find { it.label == "Turnpoint 1" }
                    assert(cachedWp != null) { "Turnpoint 1 not found in cached route!" }
                    assert(kotlin.math.abs(cachedWp!!.lat - endLat) < 0.0001) { "Cached latitude mismatch: ${cachedWp.lat} vs $endLat" }
                }
            }
        }
    }
}
