package com.madanala.tern.ui

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.MapAction
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.utils.MapVisualTest
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FAITaskUITest : MapVisualTest() {

    @Test
    fun scenario1_configureFAITaskParameters() {
        scenario("Configure FAI Task Parameters") {
            story("As a competition pilot, I want to precisely configure my waypoint parameters—such as cylinder radius and start gate times—so that my flight task exactly matches the competition briefing and I avoid score penalties.") {
                // Ensure app is launched and ready
                givenAppIsLaunchedOnMap()
                
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                val routeId = UUID.randomUUID().toString()
                val waypointId = UUID.randomUUID().toString()

                given("I have a flight task with a single waypoint 'WP1'") {
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

                `when`("I open the pilot-optimized waypoint editor") {
                    store.dispatch(MapAction.SelectWaypoint(routeId, waypointId))
                    composeTestRule.waitForIdle()
                }

                and("I set the cylinder radius to 3000m for a large start gate") {
                    composeTestRule.onNodeWithText("Radius (m)").assertExists()
                    store.dispatch(MapAction.UpdateWaypointRadius(routeId, waypointId, 3000.0))
                }

                and("I adjust the required altitude to 2000m to clear terrain") {
                    composeTestRule.onNodeWithText("Altitude (m)").assertExists()
                    store.dispatch(MapAction.UpdateWaypointAltitude(routeId, waypointId, 2000.0))
                }

                and("I set the gate opening time to 12:00 according to the task sheet") {
                    composeTestRule.onNodeWithText("Open (HH:mm)").assertExists()
                    store.dispatch(MapAction.UpdateWaypointTimeGates(routeId, waypointId, "12:00", null))
                }

                and("I save my adjustments") {
                    composeTestRule.onNode(hasText("Done") and hasClickAction()).performClick()
                    composeTestRule.waitForIdle()
                }

                then("The task list should reflect my precise flight parameters") {
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
    }

    @Test
    fun scenario2_changeWaypointTypeToSpeedSection() {
        scenario("Change Waypoint Type to Speed Section") {
            story("As a competition pilot during a race, I need to designate specific waypoints as Start or End Speed Sections so that my flight computer accurately tracks my time-to-goal performance.") {
                givenAppIsLaunchedOnMap()
                
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                val routeId = UUID.randomUUID().toString()
                val waypointId = UUID.randomUUID().toString()

                given("I have a generic waypoint in my flight plan") {
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

                `when`("I modify the waypoint type to 'Start Speed Section'") {
                    store.dispatch(MapAction.SelectWaypoint(routeId, waypointId))
                    composeTestRule.waitForIdle()

                    composeTestRule.onNodeWithText("Start Speed Section").performScrollTo().assertExists()
                    store.dispatch(MapAction.UpdateWaypointType(routeId, waypointId, Waypoint.Type.SSS))
                }

                and("I confirm the racing role of this waypoint") {
                    composeTestRule.onNode(hasText("Done") and hasClickAction()).performClick()
                    composeTestRule.waitForIdle()
                }

                then("The task overview should clearly identify it as the Start Speed Section") {
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
    }

    @Test
    fun scenario3_createCompleteCompetitionTask() {
        scenario("Create Complete Competition Task") {
            story("As a competition pilot, I want to build a full racing task from scratch—including takeoff, start gates, and goals—so I can be fully prepared before the launch window opens.") {
                givenAppIsLaunchedOnMap()
                
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                val routeId = UUID.randomUUID().toString()

                given("I am starting a new race task 'Comp Task'") {
                    val route = Route(id = routeId, name = "Comp Task")
                    store.dispatch(MapAction.AddRoute(route))
                    
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        store.state.value.routes.any { it.id == routeId }
                    }
                }

                `when`("I meticulously assemble my mission: Takeoff, Start Gate, Turnpoint, and Goal") {
                    // Add Takeoff
                    store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.0, 0.0, Waypoint.Type.LAUNCH, "Takeoff"))
                    
                    // Add Start Gate
                    store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.1, 0.1, Waypoint.Type.TURNPOINT, "Start Gate"))
                    
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        val r = store.state.value.routes.find { it.id == routeId }
                        r != null && r.waypoints.size >= 2
                    }
                    
                    val startGateId = store.state.value.routes.first { it.id == routeId }.waypoints.last().id
                    store.dispatch(MapAction.UpdateWaypointType(routeId, startGateId, Waypoint.Type.SSS))
                    store.dispatch(MapAction.UpdateWaypointRadius(routeId, startGateId, 2000.0))
                    
                    // Add Turnpoint 1
                    store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.2, 0.2, Waypoint.Type.TURNPOINT, "Turnpoint 1"))
                    
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        store.state.value.routes.first { it.id == routeId }.waypoints.size >= 3
                    }
                    val tp1Id = store.state.value.routes.first { it.id == routeId }.waypoints.last().id
                    store.dispatch(MapAction.UpdateWaypointRadius(routeId, tp1Id, 400.0))
                    
                    // Add Goal
                    store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.3, 0.3, Waypoint.Type.TURNPOINT, "Goal"))
                    
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        store.state.value.routes.first { it.id == routeId }.waypoints.size >= 4
                    }
                    val goalId = store.state.value.routes.first { it.id == routeId }.waypoints.last().id
                    store.dispatch(MapAction.UpdateWaypointType(routeId, goalId, Waypoint.Type.GOAL))
                    store.dispatch(MapAction.UpdateWaypointRadius(routeId, goalId, 1000.0))
                }

                this.then("The final task overview should show my complete mission profile with all racing parameters") {
                    store.dispatch(MapAction.SelectRoute(routeId))
                    
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isNotEmpty()
                    }
                    
                    composeTestRule.onNodeWithTag("RouteDetailPanel").assertExists()
                    
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
}
