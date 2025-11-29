package com.madanala.tern.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.RouteIOManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FAIEndToEndTest : BddTest() {

    // composeTestRule is inherited from BaseUITest via BddTest

    @Test
    fun scenarioFullFAILifecycle() {
        scenario("Full FAI Task Lifecycle") {
            var store: MapStore? = null
            var routeId: String? = null
            var initialRoute: Route? = null
            var waypoints: List<Waypoint>? = null
            var xctskContent: String? = null
            var importedRoute: Route? = null

            given("I have created a valid FAI task with Start, Turnpoints, and Goal") {
                runBlocking {
                    store = MapStore()
                    routeId = UUID.randomUUID().toString()
                    waypoints = listOf(
                        Waypoint(lat = 46.0, lon = 11.0, label = "Takeoff", routeId = routeId),
                        Waypoint(lat = 46.1, lon = 11.1, label = "Start", routeId = routeId),
                        Waypoint(lat = 46.2, lon = 11.2, label = "Turnpoint", routeId = routeId),
                        Waypoint(lat = 46.3, lon = 11.3, label = "Goal", routeId = routeId)
                    )
                    initialRoute = Route(id = routeId!!, name = "E2E Task", waypoints = waypoints!!)
                    
                    store!!.dispatch(MapAction.AddRoute(initialRoute!!))
                    store!!.dispatch(MapAction.SelectRoute(routeId!!))

                    // Wait for route to be added
                    store!!.state.first { it.routes.any { r -> r.id == routeId } }
                }
            }

            and("I have configured specific parameters (Radius, Altitude, Gates)") {
                runBlocking {
                    // Configure Start (Index 1)
                    val startId = waypoints!![1].id
                    store!!.dispatch(MapAction.UpdateWaypointType(routeId!!, startId, Waypoint.Type.SSS))
                    store!!.dispatch(MapAction.UpdateWaypointRadius(routeId!!, startId, 2000.0))
                    store!!.dispatch(MapAction.UpdateWaypointAltitude(routeId!!, startId, 1500.0))
                    store!!.dispatch(MapAction.UpdateWaypointTimeGates(routeId!!, startId, "12:00", "14:00"))

                    // Configure Goal (Index 3)
                    val goalId = waypoints!![3].id
                    store!!.dispatch(MapAction.UpdateWaypointType(routeId!!, goalId, Waypoint.Type.GOAL))
                    store!!.dispatch(MapAction.UpdateWaypointRadius(routeId!!, goalId, 1000.0))
                    store!!.dispatch(MapAction.UpdateWaypointTimeGates(routeId!!, goalId, null, "17:00")) // Close time for Goal

                    // Wait for updates to apply
                    val stateBeforeExport = store!!.state.first { state ->
                        val r = state.routes.find { it.id == routeId }
                        r?.waypoints?.get(1)?.type == Waypoint.Type.SSS &&
                        r?.waypoints?.get(3)?.type == Waypoint.Type.GOAL
                    }
                    
                    val route = stateBeforeExport.routes.find { it.id == routeId }!!
                    assertEquals(Waypoint.Type.SSS, route.waypoints[1].type)
                    assertEquals(2000.0, route.waypoints[1].radius!!, 0.1)
                    assertEquals("12:00", route.waypoints[1].openTime)
                }
            }

            `when`("I export the task to XCTSK format") {
                runBlocking {
                    val state = store!!.state.first()
                    val route = state.routes.find { it.id == routeId }!!
                    xctskContent = RouteIOManager.generateXctskContent(route)
                }
            }
            
            and("I clear the current route") {
                runBlocking {
                    store!!.dispatch(MapAction.ClearAllRoutes)
                    // Wait for routes to be cleared
                    store!!.state.first { it.routes.isEmpty() }
                }
            }

            and("I import the task from the exported XCTSK content") {
                runBlocking {
                    importedRoute = RouteIOManager.parseXctskContent(xctskContent!!)
                    assertNotNull(importedRoute)
                    
                    // Simulate loading the imported route into the app
                    store!!.dispatch(MapAction.AddRoute(importedRoute!!))
                    store!!.dispatch(MapAction.SelectRoute(importedRoute!!.id))
                    
                    // Wait for imported route to appear
                    store!!.state.first { it.routes.any { r -> r.id == importedRoute!!.id } }
                }
            }

            then("the imported route should match the original route parameters") {
                runBlocking {
                    val state = store!!.state.first()
                    val finalRoute = state.routes.find { it.id == importedRoute!!.id }!!
                    
                    assertEquals(4, finalRoute.waypoints.size)
                    
                    // Check Start
                    val startWp = finalRoute.waypoints[1]
                    assertEquals(Waypoint.Type.SSS, startWp.type)
                    assertEquals(2000.0, startWp.radius!!, 0.1)
                    assertEquals("12:00", startWp.openTime) 
                    
                    // Check Goal
                    val goalWp = finalRoute.waypoints[3]
                    assertEquals(Waypoint.Type.GOAL, goalWp.type)
                    assertEquals(1000.0, goalWp.radius!!, 0.1)
                    assertEquals("17:00", goalWp.closeTime)

                    // Validate Logcat
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE UPDATE STORM")
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
                }
            }
        }
    }
}
