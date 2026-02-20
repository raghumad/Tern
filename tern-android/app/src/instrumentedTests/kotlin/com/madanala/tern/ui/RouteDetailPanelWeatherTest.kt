package com.madanala.tern.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayState
import com.madanala.tern.redux.WeatherState
import com.madanala.tern.ui.components.RouteDetailPanel
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.WeatherData
import com.madanala.tern.utils.WeatherForecast
import com.madanala.tern.utils.WindData
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule

@RunWith(AndroidJUnit4::class)
class RouteDetailPanelWeatherTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testWaypointWeatherDisplay() {
                
                val testRoute = Route(
                    id = "route_1",
                    name = "Test Route",
                    waypoints = listOf(
                        Waypoint(id = "wp_1", lat = 40.0, lon = -105.0, label = "Start"),
                        Waypoint(id = "wp_2", lat = 40.1, lon = -105.1, label = "Turnpoint 1")
                    )
                )

                val forecastWp1 = WeatherForecast(
                    current = WeatherData(
                        wind = WindData(speed = 10.0, direction = 180.0, gust = 15.0),
                        temperature = 20.0,
                        humidity = 50.0,
                        visibility = 10.0,
                        pressure = 1013.25,
                        cloudCover = 0.0,
                        timestamp = System.currentTimeMillis() / 1000
                    ),
                    hourly = emptyList(),
                    daily = emptyList()
                )

                val testStore = MapStore()
                testStore.dispatch(com.madanala.tern.redux.MapAction.AddRoute(testRoute))
                testStore.dispatch(com.madanala.tern.redux.MapAction.SelectRoute(testRoute.id))
                testStore.dispatch(com.madanala.tern.redux.WeatherActions.RouteWeatherFetched(testRoute.id, mapOf("wp_1" to forecastWp1)))

                composeTestRule.setContent {
                    RouteDetailPanel(
                        store = testStore,
                        isVisible = true,
                        onDismiss = {}
                    )
                }

                composeTestRule.onNodeWithText("🌬️ 10 kt @ 180° (G 15)").assertIsDisplayed()
    }
}
