package com.ternparagliding.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.hazard.RED_HAZARD
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.WeatherTestHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Weather-hazard halo (RFC 005) — honest on-map render verification.
 *
 * `StormRiskValidationTest` proves the *panel* banner; `HazardRenderTest`
 * renders the bitmap to an offline montage. Neither proves the halo paints
 * the live map. This drives the real path — mock storm weather → AddRoute →
 * WeatherMiddleware fetch → RouteWeatherFetched → HazardOverlay → HazardLayer
 * — and asserts the exact red hazard ring is on the GPU surface.
 */
class HazardRenderOnMapTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Test
    fun testHazardHaloRendersOnMap() {
        val lat = 19.0433
        val lon = -99.9142
        scenario("Storm-hazard halo renders on the map") {
            story("As a pilot, I want a storm warning halo drawn on a route waypoint so I can avoid flying into convective danger.") {
                lateinit var route: Route

                given("the app is on the map and the forecast is stormy everywhere") {
                    givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "mx")
                    // RFC 005 thresholds: lightning > 60 → thunderstorm (red).
                    WeatherTestHelper.setMockWeatherResponse(
                        latitude = lat, longitude = lon, cape = 1800.0, lightningPotential = 75.0,
                    )
                }

                `when`("a route with a waypoint here is added and its weather is fetched") {
                    val wp = Waypoint(lat = lat, lon = lon, type = LocationType.TURNPOINT, label = "Storm TP")
                    route = Route(id = "hazard-test-route", name = "Hazard Test", waypoints = listOf(wp))
                    composeTestRule.runOnUiThread {
                        val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                        store.dispatch(MapAction.AddRoute(route))
                    }
                    // AddRoute → RoutePlanningMiddleware → FetchWeatherForRoute →
                    // WeatherMiddleware → RouteWeatherFetched. Poll until the
                    // waypoint weather (stormy) lands in Redux.
                    composeTestRule.waitUntil(timeoutMillis = 20000) {
                        var ready = false
                        composeTestRule.runOnUiThread {
                            val store = ViewModelProvider(composeTestRule.activity)[MapStore::class.java]
                            ready = store.state.value.weatherState.waypointWeathers.isNotEmpty()
                        }
                        ready
                    }
                    // Recentre on the waypoint so the halo sits in the scan box.
                    zoomTo(lat, lon, 12.0)
                    waitForMapToRender(2000)
                }

                thenExpectHazardColorOnMap("thunderstorm", RED_HAZARD)

                and("the map is still alive (no crash)") {
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                }
            }
        }
    }
}
