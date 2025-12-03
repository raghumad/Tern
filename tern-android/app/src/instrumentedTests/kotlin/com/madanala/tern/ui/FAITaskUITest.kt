package com.madanala.tern.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.redux.MapStore
import com.madanala.tern.ui.components.EditWaypointScreen
import com.madanala.tern.ui.components.RouteDetailPanel
import com.madanala.tern.redux.MapAction
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.utils.BddTest
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FAITaskUITest : BddTest() {

    private val store = MapStore()

    @Test
    fun scenario1_configureFAITaskParameters() {
        scenario("Configure FAI Task Parameters") {
            val routeId = UUID.randomUUID().toString()
            val waypointId = UUID.randomUUID().toString()
            var showEditScreen by mutableStateOf(true)

            given("I have created a route with a waypoint") {
                val route = Route(
                    id = routeId,
                    name = "Test Route",
                    waypoints = listOf(
                        Waypoint(
                            id = waypointId,
                            lat = 0.0,
                            lon = 0.0,
                            routeId = routeId,
                            label = "WP1"
                        )
                    )
                )
                
                store.dispatch(MapAction.AddRoute(route))
                store.dispatch(MapAction.SelectRoute(routeId))
                store.dispatch(MapAction.SelectWaypoint(routeId, waypointId))

                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.routes.isNotEmpty() && store.state.value.selectedWaypoint != null
                }
            }

            `when`("I open the edit screen for the waypoint") {
                composeTestRule.setContent {
                    if (showEditScreen) {
                        EditWaypointScreen(store = store, onDismiss = { showEditScreen = false })
                    } else {
                        RouteDetailPanel(store = store, isVisible = true, onDismiss = {})
                    }
                }
            }

            and("I set the radius to 3000m") {
                composeTestRule.onNodeWithText("Radius (m)").assertExists()
                store.dispatch(MapAction.UpdateWaypointRadius(routeId, waypointId, 3000.0))
            }

            and("I set the altitude to 2000m") {
                composeTestRule.onNodeWithText("Altitude (m)").assertExists()
                store.dispatch(MapAction.UpdateWaypointAltitude(routeId, waypointId, 2000.0))
            }

            and("I set the open time to 12:00") {
                composeTestRule.onNodeWithText("Open (HH:mm)").assertExists()
                store.dispatch(MapAction.UpdateWaypointTimeGates(routeId, waypointId, "12:00", null))
            }

            and("I click Done") {
                composeTestRule.onNode(hasText("Done") and hasClickAction()).assertExists()
                showEditScreen = false
                composeTestRule.waitForIdle()
            }

            then("The waypoint details should display the configured parameters") {
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    val r = store.state.value.routes.find { it.id == routeId }
                    val wp = r?.waypoints?.find { it.id == waypointId }
                    wp?.radius == 3000.0
                }

                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("r3000m", substring = true))
                composeTestRule.onAllNodesWithText("r3000m", substring = true).onFirst().assertExists()
                
                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("A2000m", substring = true))
                composeTestRule.onAllNodesWithText("A2000m", substring = true).onFirst().assertExists()
                
                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("O:12:00", substring = true))
                composeTestRule.onAllNodesWithText("O:12:00", substring = true).onFirst().assertExists()
            }
        }
    }

    @Test
    fun scenario2_changeWaypointTypeToSpeedSection() {
        scenario("Change Waypoint Type to Speed Section") {
            val routeId = UUID.randomUUID().toString()
            val waypointId = UUID.randomUUID().toString()
            var showEditScreen by mutableStateOf(true)

            given("I have created a route with a waypoint") {
                val route = Route(
                    id = routeId,
                    name = "Test Route",
                    waypoints = listOf(
                        Waypoint(
                            id = waypointId,
                            lat = 0.0,
                            lon = 0.0,
                            routeId = routeId,
                            label = "WP1"
                        )
                    )
                )
                
                store.dispatch(MapAction.AddRoute(route))
                store.dispatch(MapAction.SelectRoute(routeId))
                store.dispatch(MapAction.SelectWaypoint(routeId, waypointId))

                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.routes.isNotEmpty() && store.state.value.selectedWaypoint != null
                }
            }

            `when`("I open the edit screen and select Start Speed Section") {
                composeTestRule.setContent {
                    if (showEditScreen) {
                        EditWaypointScreen(store = store, onDismiss = { showEditScreen = false })
                    } else {
                        RouteDetailPanel(store = store, isVisible = true, onDismiss = {})
                    }
                }

                composeTestRule.onNodeWithText("Start Speed Section").performScrollTo().assertExists()
                store.dispatch(MapAction.UpdateWaypointType(routeId, waypointId, Waypoint.Type.SSS))
            }

            and("I click Done") {
                composeTestRule.onNode(hasText("Done") and hasClickAction()).assertExists()
                showEditScreen = false
                composeTestRule.waitForIdle()
            }

            then("The waypoint details should display Start Speed Section") {
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    val r = store.state.value.routes.find { it.id == routeId }
                    val wp = r?.waypoints?.find { it.id == waypointId }
                    wp?.type == Waypoint.Type.SSS
                }

                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("Start Speed Section", substring = true))
                composeTestRule.onAllNodesWithText("Start Speed Section", substring = true).onFirst().assertExists()
            }
        }
    }

    @Test
    fun scenario3_createCompleteCompetitionTask() {
        scenario("Create Complete Competition Task") {
            val routeId = UUID.randomUUID().toString()

            given("I have created a new route") {
                val route = Route(id = routeId, name = "Comp Task")
                store.dispatch(MapAction.AddRoute(route))
                
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.routes.any { it.id == routeId }
                }
            }

            `when`("I add waypoints for Takeoff, Start Gate, Turnpoint, and Goal") {
                // Add Takeoff
                store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.0, 0.0, Waypoint.Type.LAUNCH, "Takeoff"))
                
                // Add Start Gate
                store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.1, 0.1, Waypoint.Type.TURNPOINT, "Start Gate"))
                
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    val r = store.state.value.routes.find { it.id == routeId }
                    r != null && r.waypoints.size >= 2
                }
                
                val startGateId = store.state.value.routes.first().waypoints.last().id
                store.dispatch(MapAction.UpdateWaypointType(routeId, startGateId, Waypoint.Type.SSS))
                store.dispatch(MapAction.UpdateWaypointRadius(routeId, startGateId, 2000.0))
                
                // Add Turnpoint 1
                store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.2, 0.2, Waypoint.Type.TURNPOINT, "Turnpoint 1"))
                
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.routes.first().waypoints.size >= 3
                }
                val tp1Id = store.state.value.routes.first().waypoints.last().id
                store.dispatch(MapAction.UpdateWaypointRadius(routeId, tp1Id, 400.0))
                
                // Add Goal
                store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.3, 0.3, Waypoint.Type.TURNPOINT, "Goal"))
                
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    store.state.value.routes.first().waypoints.size >= 4
                }
                val goalId = store.state.value.routes.first().waypoints.last().id
                store.dispatch(MapAction.UpdateWaypointType(routeId, goalId, Waypoint.Type.GOAL))
                store.dispatch(MapAction.UpdateWaypointRadius(routeId, goalId, 1000.0))
            }

            then("The route waypoint list should show all task parameters") {
                store.dispatch(MapAction.SelectRoute(routeId))
                
                composeTestRule.setContent {
                    RouteDetailPanel(store = store, isVisible = true, onDismiss = {})
                }
                
                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("Start Speed Section", substring = true))
                composeTestRule.onAllNodesWithText("Start Speed Section", substring = true).onFirst().assertExists()
                
                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("Goal", substring = true))
                composeTestRule.onAllNodesWithText("Goal", substring = true).onFirst().assertExists()
                
                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("r2000m", substring = true))
                composeTestRule.onAllNodesWithText("r2000m", substring = true).onFirst().assertExists()
                
                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("r1000m", substring = true))
                composeTestRule.onAllNodesWithText("r1000m", substring = true).onFirst().assertExists()
            }
        }
    }
}
