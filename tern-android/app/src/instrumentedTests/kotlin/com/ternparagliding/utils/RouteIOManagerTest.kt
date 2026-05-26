package com.ternparagliding.utils
import com.ternparagliding.model.LocationType

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import com.ternparagliding.utils.MapVisualTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RouteIOManagerTest : MapVisualTest() {

    @Test
    fun scenarioExportAndImportFAITask() {
        scenario("Export and Import FAI Task (Verbose)") {
            var route: Route? = null
            var xctskContent: String? = null
            var importedRoute: Route? = null

            given("I have a route 'Competition Task' with waypoints") {
                val routeId = UUID.randomUUID().toString()
                val waypoints = listOf(
                    Waypoint(
                        lat = 46.0, lon = 11.0, 
                        type = LocationType.LAUNCH, 
                        label = "Takeoff", 
                        radius = 400.0,
                        routeId = routeId
                    ),
                    Waypoint(
                        lat = 46.1, lon = 11.1, 
                        type = LocationType.SSS, 
                        label = "Start", 
                        radius = 2000.0, 
                        alt = 1000.0, 
                        openTime = "12:00", 
                        closeTime = "14:00",
                        routeId = routeId
                    ),
                    Waypoint(
                        lat = 46.2, lon = 11.2, 
                        type = LocationType.TURNPOINT, 
                        label = "Turnpoint", 
                        radius = 400.0,
                        routeId = routeId
                    ),
                    Waypoint(
                        lat = 46.3, lon = 11.3, 
                        type = LocationType.ESS, 
                        label = "End", 
                        radius = 400.0,
                        routeId = routeId
                    ),
                    Waypoint(
                        lat = 46.4, lon = 11.4, 
                        type = LocationType.GOAL, 
                        label = "Goal", 
                        radius = 1000.0, 
                        closeTime = "17:00",
                        routeId = routeId
                    )
                )
                route = Route(id = routeId, name = "Competition Task", waypoints = waypoints)
            }

            `when`("I export the route to XCTSK format") {
                xctskContent = RouteIOManager.generateXctskContent(route!!)
            }

            and("I import the route from the exported XCTSK content") {
                importedRoute = RouteIOManager.parseXctskContent(xctskContent!!)
                showRouteOnMap(importedRoute!!)
                zoomTo(importedRoute!!.waypoints.first().lat, importedRoute!!.waypoints.first().lon)
                waitForMapToRender()
            }

            this.then("the imported route should have correct waypoints and parameters") {
                assertNotNull(importedRoute)
                assertEquals(5, importedRoute!!.waypoints.size)

                // Check Start Waypoint
                val startWp = importedRoute!!.waypoints.find { it.label == "Start" }
                assertNotNull(startWp)
                assertEquals(LocationType.SSS, startWp!!.type)
                assertEquals(2000.0, startWp!!.radius!!, 0.1)
                assertEquals("12:00", startWp!!.openTime) // Now supported in verbose export

                // Check Goal Waypoint
                val goalWp = importedRoute!!.waypoints.find { it.label == "Goal" }
                assertNotNull(goalWp)
                assertEquals(LocationType.GOAL, goalWp!!.type)
                assertEquals(1000.0, goalWp!!.radius!!, 0.1)
                assertEquals("17:00", goalWp!!.closeTime)
            }
        }
    }
    
    @Test
    fun testXctskCompressedFlow() {
        scenario("XCTSK Compressed Flow (QR Code)") {
            var route: Route? = null
            var compressedJson: String? = null
            var importedRoute: Route? = null

            given("I have a route with SSS and Time Gates") {
                val routeId = UUID.randomUUID().toString()
                val waypoints = listOf(
                    Waypoint(lat = 46.1, lon = 11.1, type = LocationType.SSS, label = "Start", radius = 2000.0, alt = 1000.0, openTime = "12:00")
                )
                route = Route(id = routeId, name = "Comp Task", waypoints = waypoints)
            }

            `when`("I generate the compressed XCTSK JSON (via reflection)") {
                val method = RouteIOManager::class.java.getDeclaredMethod("generateXctskCompressed", Route::class.java)
                method.isAccessible = true
                compressedJson = method.invoke(RouteIOManager, route) as String
            }

            this.then("the JSON should contain compressed data") {
                assertTrue(compressedJson!!.contains("\"z\":"))
            }

            and("I import the route from the compressed JSON") {
                importedRoute = RouteIOManager.importRouteFromQrString(compressedJson!!)
                showRouteOnMap(importedRoute!!)
                zoomTo(importedRoute!!.waypoints.first().lat, importedRoute!!.waypoints.first().lon)
                waitForMapToRender()
            }

            this.then("the imported route should preserve SSS parameters") {
                assertNotNull(importedRoute)
                val startWp = importedRoute!!.waypoints.first()
                assertEquals(LocationType.SSS, startWp.type)
                assertEquals(2000.0, startWp.radius!!, 0.1)
                assertEquals("12:00", startWp.openTime)
            }
        }
    }
}
