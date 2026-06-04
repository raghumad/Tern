package com.ternparagliding.utils
import com.ternparagliding.utils.io.RouteIOManager
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
            var xctskContent: String? = null
            var importedRoute: Route? = null

            val routeId = UUID.randomUUID().toString()
            val src = listOf(
                Waypoint(lat = 46.0, lon = 11.0, type = LocationType.LAUNCH, label = "Takeoff", radius = 400.0, routeId = routeId),
                Waypoint(lat = 46.1, lon = 11.1, type = LocationType.SSS, label = "Start", radius = 2000.0, alt = 1000.0, openTime = "12:00", closeTime = "14:00", routeId = routeId),
                Waypoint(lat = 46.2, lon = 11.2, type = LocationType.TURNPOINT, label = "Turnpoint", radius = 400.0, routeId = routeId),
                Waypoint(lat = 46.3, lon = 11.3, type = LocationType.ESS, label = "End", radius = 400.0, routeId = routeId),
                Waypoint(lat = 46.4, lon = 11.4, type = LocationType.GOAL, label = "Goal", radius = 1000.0, closeTime = "17:00", routeId = routeId),
            )
            val route = Route(id = routeId, name = "Competition Task", waypoints = src)

            given("I have a route 'Competition Task' with waypoints") {
                // (route defined above so the THEN can assert against the source)
            }

            `when`("I export the route to XCTSK format") {
                xctskContent = RouteIOManager.generateXctskContent(route)
            }

            and("I import the route and show it on the map (auto-framed)") {
                importedRoute = RouteIOManager.parseXctskContent(xctskContent!!)
                showRouteOnMap(importedRoute!!)
            }

            this.then("every waypoint's coordinates, type, radius and gates survive the round-trip", takeScreenshot = true) {
                assertNotNull(importedRoute)
                val out = importedRoute!!.waypoints
                assertEquals(src.size, out.size)

                src.forEach { exp ->
                    val got = out.find { it.label == exp.label }
                        ?: throw AssertionError("waypoint '${exp.label}' lost in round-trip")
                    assertEquals("lat for ${exp.label}", exp.lat, got.lat, 1e-4)
                    assertEquals("lon for ${exp.label}", exp.lon, got.lon, 1e-4)
                    assertEquals("type for ${exp.label}", exp.type, got.type)
                    assertEquals("radius for ${exp.label}", exp.radius!!, got.radius!!, 0.5)
                }

                val startWp = out.first { it.label == "Start" }
                assertEquals("SSS open gate", "12:00", startWp.openTime)
                val goalWp = out.first { it.label == "Goal" }
                assertEquals("Goal deadline", "17:00", goalWp.closeTime)
            }
        }
    }
    
    @Test
    fun testXctskCompressedFlow() {
        scenario("XCTSK Compressed Flow (QR Code)") {
            var route: Route? = null
            var compressedJson: String? = null
            var importedRoute: Route? = null

            // Distinct coords/alts/radii so the polyline codec (which encodes
            // lat/lon/alt/radius into "z") is actually exercised, not assumed.
            val src = listOf(
                Waypoint(lat = 46.00, lon = 11.00, type = LocationType.LAUNCH, label = "Takeoff", radius = 400.0),
                Waypoint(lat = 46.10, lon = 11.12, type = LocationType.SSS, label = "Start", radius = 2000.0, alt = 1000.0, openTime = "12:00"),
                Waypoint(lat = 46.22, lon = 11.25, type = LocationType.TURNPOINT, label = "Turn", radius = 600.0),
                Waypoint(lat = 46.31, lon = 11.33, type = LocationType.ESS, label = "End", radius = 1000.0),
                Waypoint(lat = 46.40, lon = 11.44, type = LocationType.GOAL, label = "Goal", radius = 1000.0, closeTime = "17:00"),
            )

            given("I have a multi-waypoint competition task with gates") {
                route = Route(id = UUID.randomUUID().toString(), name = "Comp Task", waypoints = src)
            }

            `when`("I generate the compressed XCTSK JSON (via reflection)") {
                val method = RouteIOManager::class.java.getDeclaredMethod("generateXctskCompressed", Route::class.java)
                method.isAccessible = true
                compressedJson = method.invoke(RouteIOManager, route) as String
            }

            this.then("the JSON carries polyline-compressed waypoints") {
                assertTrue("compressed payload missing \"z\"", compressedJson!!.contains("\"z\":"))
            }

            and("I import the route back from the compressed JSON and show it (auto-framed)") {
                importedRoute = RouteIOManager.importRouteFromQrString(compressedJson!!)
                showRouteOnMap(importedRoute!!)
            }

            this.then("every waypoint's COORDINATES, alt, radius, type and gates survive the round-trip", takeScreenshot = true) {
                assertNotNull(importedRoute)
                val out = importedRoute!!.waypoints
                assertEquals("waypoint count changed", src.size, out.size)

                src.forEach { exp ->
                    val got = out.find { it.label == exp.label }
                        ?: throw AssertionError("waypoint '${exp.label}' lost in round-trip")
                    // The whole point of the polyline compression — coordinates:
                    assertEquals("lat for ${exp.label}", exp.lat, got.lat, 1e-4)
                    assertEquals("lon for ${exp.label}", exp.lon, got.lon, 1e-4)
                    assertEquals("radius for ${exp.label}", exp.radius!!, got.radius!!, 0.5)
                    assertEquals("type for ${exp.label}", exp.type, got.type)
                }

                val start = out.first { it.label == "Start" }
                assertEquals("SSS altitude lost", 1000.0, start.alt!!, 0.5)
                assertEquals("SSS open gate lost", "12:00", start.openTime)

                val goal = out.first { it.label == "Goal" }
                assertEquals("Goal deadline lost", "17:00", goal.closeTime)
            }
        }
    }
}
