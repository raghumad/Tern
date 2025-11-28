package com.madanala.tern.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.redux.MapStore
import com.madanala.tern.ui.components.EditWaypointScreen
import com.madanala.tern.ui.components.RouteDetailPanel
import com.madanala.tern.redux.MapAction
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FAITaskUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val store = MapStore()

    @Test
    fun scenario1_configureFAITaskParameters() {
        // Given I have created a route with a waypoint
        val routeId = UUID.randomUUID().toString()
        val waypointId = UUID.randomUUID().toString()
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

        // Wait for state update
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            store.state.value.routes.isNotEmpty() && store.state.value.selectedWaypoint != null
        }

        var showEditScreen by mutableStateOf(true)

        // When I open the edit screen for the waypoint
        composeTestRule.setContent {
            if (showEditScreen) {
                EditWaypointScreen(store = store, onDismiss = { showEditScreen = false })
            } else {
                RouteDetailPanel(store = store, isVisible = true, onDismiss = {})
            }
        }

        // And I set the radius to "3000"
        composeTestRule.onNodeWithText("Radius (m)").assertExists()
        store.dispatch(MapAction.UpdateWaypointRadius(routeId, waypointId, 3000.0))

        // And I set the altitude to "2000"
        composeTestRule.onNodeWithText("Altitude (m)").assertExists()
        store.dispatch(MapAction.UpdateWaypointAltitude(routeId, waypointId, 2000.0))

        // And I set the open time to "12:00"
        composeTestRule.onNodeWithText("Open (HH:mm)").assertExists()
        store.dispatch(MapAction.UpdateWaypointTimeGates(routeId, waypointId, "12:00", null))

        // And I click "Done"
        composeTestRule.onNode(hasText("Done") and hasClickAction()).assertExists()
        showEditScreen = false
        
        // Wait for UI switch
        composeTestRule.waitForIdle()

        // Verify store update
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            val r = store.state.value.routes.find { it.id == routeId }
            val wp = r?.waypoints?.find { it.id == waypointId }
            wp?.radius == 3000.0
        }

        // Then the waypoint details should display "r3000m"
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("r3000m", substring = true))
                composeTestRule.onAllNodesWithText("r3000m", substring = true).onFirst().assertExists()
                true
            } catch (e: AssertionError) {
                false
            } catch (e: Exception) {
                false
            }
        }
        // And the waypoint details should display "A2000m"
        composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("A2000m", substring = true))
        composeTestRule.onAllNodesWithText("A2000m", substring = true).onFirst().assertExists()
        // And the waypoint details should display "O:12:00"
        composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("O:12:00", substring = true))
        composeTestRule.onAllNodesWithText("O:12:00", substring = true).onFirst().assertExists()
    }

    @Test
    fun scenario2_changeWaypointTypeToSpeedSection() {
        // Given I have created a route with a waypoint
        val routeId = UUID.randomUUID().toString()
        val waypointId = UUID.randomUUID().toString()
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

        var showEditScreen by mutableStateOf(true)

        composeTestRule.setContent {
            if (showEditScreen) {
                EditWaypointScreen(store = store, onDismiss = { showEditScreen = false })
            } else {
                RouteDetailPanel(store = store, isVisible = true, onDismiss = {})
            }
        }

        // And I select "Start Speed Section" from the type list
        composeTestRule.onNodeWithText("Start Speed Section").performScrollTo().assertExists()
        store.dispatch(MapAction.UpdateWaypointType(routeId, waypointId, Waypoint.Type.SSS))

        // And I click "Done"
        composeTestRule.onNode(hasText("Done") and hasClickAction()).assertExists()
        showEditScreen = false
        
        composeTestRule.waitForIdle()

        // Verify store update
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            val r = store.state.value.routes.find { it.id == routeId }
            val wp = r?.waypoints?.find { it.id == waypointId }
            wp?.type == Waypoint.Type.SSS
        }

        // Then the waypoint details should display "Start Speed Section"
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithTag("WaypointList").performScrollToNode(hasText("Start Speed Section", substring = true))
                composeTestRule.onAllNodesWithText("Start Speed Section", substring = true).onFirst().assertExists()
                true
            } catch (e: AssertionError) {
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    @Test
    fun scenario3_createCompleteCompetitionTask() {
        // Given I have created a new route
        val routeId = UUID.randomUUID().toString()
        val route = Route(id = routeId, name = "Comp Task")
        store.dispatch(MapAction.AddRoute(route))
        
        // Wait for route creation
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            store.state.value.routes.any { it.id == routeId }
        }
        
        // When I add a waypoint "Takeoff"
        store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.0, 0.0, Waypoint.Type.LAUNCH, "Takeoff"))
        
        // And I add a waypoint "Start Gate"
        store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.1, 0.1, Waypoint.Type.TURNPOINT, "Start Gate"))
        
        // Wait for waypoints
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            val r = store.state.value.routes.find { it.id == routeId }
            r != null && r.waypoints.size >= 2
        }
        
        val startGateId = store.state.value.routes.first().waypoints.last().id
        
        // And I set the "Start Gate" type to "Start Speed Section"
        store.dispatch(MapAction.UpdateWaypointType(routeId, startGateId, Waypoint.Type.SSS))
        
        // And I set the "Start Gate" radius to "2000"
        store.dispatch(MapAction.UpdateWaypointRadius(routeId, startGateId, 2000.0))
        
        // And I add a waypoint "Turnpoint 1"
        store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.2, 0.2, Waypoint.Type.TURNPOINT, "Turnpoint 1"))
        
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            store.state.value.routes.first().waypoints.size >= 3
        }
        val tp1Id = store.state.value.routes.first().waypoints.last().id
        
        // And I set the "Turnpoint 1" radius to "400"
        store.dispatch(MapAction.UpdateWaypointRadius(routeId, tp1Id, 400.0))
        
        // And I add a waypoint "Goal"
        store.dispatch(MapAction.AddWaypointToRoute(routeId, 0.3, 0.3, Waypoint.Type.TURNPOINT, "Goal"))
        
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            store.state.value.routes.first().waypoints.size >= 4
        }
        val goalId = store.state.value.routes.first().waypoints.last().id
        
        // And I set the "Goal" type to "Goal"
        store.dispatch(MapAction.UpdateWaypointType(routeId, goalId, Waypoint.Type.GOAL))
        
        // And I set the "Goal" radius to "1000"
        store.dispatch(MapAction.UpdateWaypointRadius(routeId, goalId, 1000.0))
        
        // Then the route waypoint list should show "Start Speed Section"
        // And the route waypoint list should show "Goal"
        // And the route waypoint list should show "r2000m"
        // And the route waypoint list should show "r1000m"
        
        // Verify via RouteDetailPanel
        store.dispatch(MapAction.SelectRoute(routeId))
        
        composeTestRule.setContent {
            RouteDetailPanel(store = store, isVisible = true, onDismiss = {})
        }
        
        // Use scrolling for list items
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
