package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.RouteIOManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FAIEndToEndTest : MapVisualTest() {

    @Test
    fun completeFAIPreFlightSequence() {
        scenario("Full FAI Task Lifecycle") {
            val activity = composeTestRule.activity as com.madanala.tern.TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            val routeId = UUID.randomUUID().toString()
            var xctskContent: String? = null
            var importedRoute: Route? = null

            given("I have created a route with FAI Turnpoints") {
                val waypoints = listOf(
                    Waypoint(lat = 46.0, lon = 11.0, label = "Takeoff", routeId = routeId, type = Waypoint.Type.LAUNCH),
                    Waypoint(lat = 46.1, lon = 11.1, label = "Start", routeId = routeId, type = Waypoint.Type.SSS),
                    Waypoint(lat = 46.2, lon = 11.2, label = "Turnpoint", routeId = routeId, type = Waypoint.Type.TURNPOINT),
                    Waypoint(lat = 46.3, lon = 11.3, label = "Goal", routeId = routeId, type = Waypoint.Type.GOAL)
                )
                val initialRoute = Route(id = routeId, name = "E2E Task", waypoints = waypoints)
                
                store.dispatch(MapAction.AddRoute(initialRoute))
                store.dispatch(MapAction.SelectRoute(routeId))

                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.routes.any { r -> r.id == routeId }
                }
            }

            and("I have configured specific parameters (Radius, Altitude, Gates)") {
                // Configure Start (Index 1)
                val startId = store.state.value.routes.find { it.id == routeId }?.waypoints?.get(1)?.id ?: ""
                store.dispatch(MapAction.UpdateWaypointRadius(routeId, startId, 2000.0))
                store.dispatch(MapAction.UpdateWaypointAltitude(routeId, startId, 1500.0))
                store.dispatch(MapAction.UpdateWaypointTimeGates(routeId, startId, "12:00", "14:00"))

                // Configure Goal (Index 3)
                val goalId = store.state.value.routes.find { it.id == routeId }?.waypoints?.get(3)?.id ?: ""
                store.dispatch(MapAction.UpdateWaypointRadius(routeId, goalId, 1000.0))
                store.dispatch(MapAction.UpdateWaypointTimeGates(routeId, goalId, null, "17:00"))

                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    val r = store.state.value.routes.find { it.id == routeId }
                    r?.waypoints?.get(1)?.radius == 2000.0 &&
                    r?.waypoints?.get(3)?.radius == 1000.0
                }
            }

            `when`("I export the task to XCTSK format") {
                val route = store.state.value.routes.find { it.id == routeId }!!
                xctskContent = RouteIOManager.generateXctskContent(route)
                assertNotNull(xctskContent)
            }
            
            and("I clear the current route") {
                store.dispatch(MapAction.ClearAllRoutes)
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.routes.isEmpty()
                }
            }

            and("I import the task from the exported XCTSK content") {
                importedRoute = RouteIOManager.parseXctskContent(xctskContent!!)
                assertNotNull(importedRoute)
                
                store.dispatch(MapAction.AddRoute(importedRoute!!))
                store.dispatch(MapAction.SelectRoute(importedRoute!!.id))
                
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.routes.any { r -> r.id == importedRoute!!.id }
                }
            }

            this.then("the imported route should match the original route parameters") {
                val finalRoute = store.state.value.routes.find { it.id == importedRoute!!.id }!!
                
                assertEquals(4, finalRoute.waypoints.size)
                
                // Check Start
                val startWp = finalRoute.waypoints[1]
                assertEquals(2000.0, startWp.radius ?: 0.0, 0.1)
                assertEquals("12:00", startWp.openTime) 
                
                // Check Goal
                val goalWp = finalRoute.waypoints[3]
                assertEquals(1000.0, goalWp.radius ?: 0.0, 0.1)
                assertEquals("17:00", goalWp.closeTime)

                // Validate Logcat
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE UPDATE STORM")
            }
        }
    }
}
