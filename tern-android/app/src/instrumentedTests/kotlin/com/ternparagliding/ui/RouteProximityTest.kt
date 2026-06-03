package com.ternparagliding.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.CacheManager
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.VisualValidator
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline-first "your preplanned routes resurface near you" behavior:
 * a route cached at home (here, the Swiss Alps) should appear in the active
 * route set automatically once the pilot is in the area — driven by
 * RouteProximityOverlay querying the spatial RouteCache by centroid.
 */
@RunWith(AndroidJUnit4::class)
class RouteProximityTest : MapVisualTest() {

    @Test
    fun pilot_arriving_in_the_alps_sees_their_preplanned_route() {
        scenario("Preplanned routes resurface near the pilot") {
            story("As a pilot who planned an Alpine route at home, when I arrive in the Alps my route should appear automatically — no internet, no manual reload.") {
                val routeId = "eu_preplanned_alpine"
                val euLat = 46.5
                val euLon = 8.5

                val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]

                given("a preplanned Alpine route is cached and it is not in the active set") {
                    val euRoute = Route(
                        id = routeId,
                        name = "Fiesch Alpine Tour",
                        waypoints = listOf(
                            Waypoint(lat = 46.5, lon = 8.5, label = "Fiesch Launch", type = LocationType.LAUNCH),
                            Waypoint(lat = 46.8, lon = 9.5, label = "Eggishorn", type = LocationType.TURNPOINT),
                            Waypoint(lat = 46.5, lon = 8.5, label = "Fiesch Goal", type = LocationType.GOAL),
                        ),
                    )
                    CacheManager.routeCache.cacheRoute(euRoute)
                    assert(store.state.value.routes.none { it.id == routeId }) {
                        "Precondition failed: route already in active set before arriving"
                    }
                }

                `when`("I arrive in the Swiss Alps") {
                    // Moving the map centre triggers RouteProximityOverlay's query.
                    zoomTo(euLat, euLon, 11.0)
                }

                then("my preplanned Alpine route is drawn on the map automatically", takeScreenshot = true) {
                    // Precondition (clearer diagnostics): the route surfaced into state.
                    composeTestRule.waitUntil(timeoutMillis = 8000) {
                        store.state.value.routes.any { it.id == routeId }
                    }

                    // The real, pilot-visible assertion: the route's neon line is
                    // actually RENDERED on the map — not merely present in Redux.
                    // Scan the map area for the route colour (0xFF00E5FF),
                    // excluding the right-edge control dock (the route tool button
                    // is the same cyan, so it would false-positive).
                    composeTestRule.waitForIdle()
                    Thread.sleep(1500) // camera settle + line render
                    val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        ?: throw AssertionError("screenshot failed")
                    val rect = android.graphics.Rect(
                        (shot.width * 0.08).toInt(), (shot.height * 0.18).toInt(),
                        (shot.width * 0.88).toInt(), (shot.height * 0.82).toInt(),
                    )
                    val rendered = VisualValidator.findColorSignature(
                        shot, rect, 0xFF00E5FF.toInt(), tolerance = 40, minPixels = 40,
                    )
                    assert(rendered) {
                        "Preplanned route is in state but its neon line is NOT rendered on the map (region $rect)"
                    }
                }
            }
        }
    }
}
