package com.madanala.tern.test

import androidx.compose.ui.test.*
import com.madanala.tern.model.*
import com.madanala.tern.utils.*
import com.madanala.tern.redux.*
import org.junit.Test
import org.osmdroid.util.GeoPoint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import androidx.lifecycle.ViewModelProvider

/**
 * High-fidelity algorithmic audit of aviation hazard indicators.
 * Verifies that safety-critical visuals (RFC 005) are active, correctly colored, 
 * and animating as expected using pixel-delta analysis.
 */
@RunWith(AndroidJUnit4::class)
class ResourceAuditTest : MapVisualTest() {

    private val AMBER_HALO = 0xFFFFBF00.toInt()
    private val RED_STORM = 0xFFE53935.toInt()

    @Test
    fun auditHazardVisualFidelity() {
        scenario("Aviation Hazard Visual Fidelity Audit") {
            
            val testLocation = GeoPoint(45.9237, 6.8694) // Chamonix

            given("A Waypoint with Convective Danger (Amber Halo)") {
                val wpId = "audit_wp"
                val waypoint = Waypoint(
                    id = wpId,
                    lat = testLocation.latitude,
                    lon = testLocation.longitude,
                    type = LocationType.TURNPOINT,
                    label = "Convective Peak"
                )
                
                val route = Route.fromWaypoints("Audit Route", listOf(waypoint))
                
                zoomTo(testLocation.latitude, testLocation.longitude, 15.0)
                showRouteOnMap(route)
                
                // Inject weather hazard via Redux
                injectWaypointWeather(route.id, wpId, hasConvective = true, hasStorm = false)
                
                waitForMapToRender()
            }

            thenExpectHazardFidelity("HazardHalo_Convective Peak", AMBER_HALO)

            given("A PG Spot with Thunderstorm Risk (Flashing Red Bolt)") {
                clearState()
                
                val wpId = "audit_spot"
                val spot = Waypoint(
                    id = wpId,
                    lat = testLocation.latitude,
                    lon = testLocation.longitude,
                    type = LocationType.LAUNCH,
                    label = "Stormy Launch"
                )
                
                val route = Route.fromWaypoints("Storm Audit", listOf(spot))
                
                zoomTo(testLocation.latitude, testLocation.longitude, 15.0)
                showRouteOnMap(route)
                
                // Inject weather hazard via Redux (PG Spot behavior is simulated via Waypoint for this audit)
                injectWaypointWeather(route.id, wpId, hasConvective = false, hasStorm = true)
                
                waitForMapToRender()
            }

            thenExpectHazardFidelity("HazardBolt_Stormy Launch", RED_STORM, waitMillis = 500)

            given("Low Zoom Transition (Adaptive Scaling Audit)") {
                zoomTo(testLocation.latitude, testLocation.longitude, 5.0)
                waitForMapToRender()
            }

            then("The marker should be in 'Pin-Prick' mode (Label GONE)") {
                composeTestRule.onNodeWithText("STORMY LAUNCH").assertDoesNotExist()
                Log.i("ResourceAuditTest", "Verified: Label correctly decluttered at Low Zoom")
            }
        }
    }

    private fun injectWaypointWeather(routeId: String, wpId: String, hasConvective: Boolean, hasStorm: Boolean) {
        val forecast = createMockForecast(hasConvective, hasStorm)
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            store.dispatch(WeatherActions.RouteWeatherFetched(
                routeId = routeId,
                waypointForecasts = mapOf(wpId to forecast)
            ))
        }
    }

    private fun createMockForecast(hasConvective: Boolean, hasStorm: Boolean): WeatherForecast {
        val weather = WeatherData(
            wind = WindData(10.0, 180.0, 15.0),
            temperature = 20.0,
            humidity = if (hasConvective) 90.0 else 50.0,
            visibility = 10.0,
            pressure = 1013.25,
            cloudCover = if (hasConvective) 90.0 else 20.0,
            timestamp = System.currentTimeMillis(),
            cape = if (hasConvective) 1000.0 else 100.0,
            lightningPotential = if (hasStorm) 80.0 else 10.0
        )
        
        val period = ForecastPeriod(
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 3600000,
            weather = weather,
            shortForecast = if (hasStorm) "Thunderstorms" else if (hasConvective) "Overdevelopment" else "Clear"
        )
        
        return WeatherForecast(
            current = weather,
            daily = emptyList(),
            hourly = listOf(period)
        )
    }

    private fun clearState() {
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            store.dispatch(MapAction.ClearAllRoutes)
            store.dispatch(WeatherActions.ClearWeatherCache)
        }
        composeTestRule.waitForIdle()
    }
}
