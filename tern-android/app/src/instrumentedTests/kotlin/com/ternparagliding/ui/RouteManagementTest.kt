package com.ternparagliding.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.ternparagliding.utils.ReportGenerator

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.TernParaglidingActivity
import com.ternparagliding.redux.MapAction
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteManagementTest : MapVisualTest() {

    // mapComposeTestRule is inherited from MapVisualTest

    @Test
    fun testCreateRenameDeleteRoute() {
        scenario("testCreateRenameDeleteRoute") {
            story("As a pilot planning a cross-country flight, I want to manage my flight tasks (create, rename, delete) so I can stay organized before takeoff.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]
                given("I am in the Flight Task Manager with a clean slate") {
                    com.ternparagliding.utils.ReportGenerator.logStep("SETUP", "Opening RouteListScreen in real activity")
                    
                    // Navigate to Route List
                    composeTestRule.onNodeWithContentDescription("Route Management").performClick()
                    composeTestRule.waitForIdle()
                    
                    // Verify initial state
                    com.ternparagliding.utils.ReportGenerator.logStep("VERIFY", "Checking for empty state message")
                    composeTestRule.onNodeWithText("No nearby routes found").assertIsDisplayed()
                }

                `when`("I create a new flight task named 'New Route 1'") {
                    com.ternparagliding.utils.ReportGenerator.logStep("ACTION", "Dispatching AddRoute action")
                    val newRoute = com.ternparagliding.model.Route(name = "New Route 1")
                    store.dispatch(MapAction.AddRoute(newRoute))

                    // Wait for UI update
                    com.ternparagliding.utils.ReportGenerator.logStep("WAIT", "Waiting for 'New Route 1' to appear")
                    composeTestRule.onNodeWithText("New Route 1").assertIsDisplayed()
                }

                then("I see 'New Route 1' ready in my task list") {
                    com.ternparagliding.utils.ReportGenerator.logStep("VERIFY", "Asserting 'New Route 1' is displayed")
                    composeTestRule.onNodeWithText("New Route 1").assertIsDisplayed()
                }

                `when`("I rename the task to 'My Awesome Flight' to reflect the mission") {
                    com.ternparagliding.utils.ReportGenerator.logStep("ACTION", "Dispatching UpdateRoute action (Rename)")
                    val route = store.state.value.routes.first()
                    val renamedRoute = route.copy(name = "My Awesome Flight")
                    store.dispatch(MapAction.UpdateRoute(renamedRoute))

                    // Wait for UI update
                    com.ternparagliding.utils.ReportGenerator.logStep("WAIT", "Waiting for 'My Awesome Flight' to appear")
                    composeTestRule.onNodeWithText("My Awesome Flight").assertIsDisplayed()
                }

                then("The task is updated to 'My Awesome Flight'") {
                    com.ternparagliding.utils.ReportGenerator.logStep("VERIFY", "Asserting 'My Awesome Flight' is displayed")
                    composeTestRule.onNodeWithText("My Awesome Flight").assertIsDisplayed()
                }

                `when`("I temporarily hide the task from my primary map view") {
                    com.ternparagliding.utils.ReportGenerator.logStep("ACTION", "Dispatching UpdateRoute action (Visibility)")
                    val route = store.state.value.routes.first()
                    val hiddenRoute = route.copy(isVisible = false)
                    store.dispatch(MapAction.UpdateRoute(hiddenRoute))

                    // Wait for icon update
                    com.ternparagliding.utils.ReportGenerator.logStep("WAIT", "Waiting for visibility icon update")
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithContentDescription("Show route").fetchSemanticsNodes().isNotEmpty()
                    }
                }

                then("The task management UI shows the 'Hidden' icon") {
                    com.ternparagliding.utils.ReportGenerator.logStep("VERIFY", "Asserting 'Show route' icon is displayed")
                    composeTestRule.onNodeWithContentDescription("Show route").assertIsDisplayed()
                }

                `when`("I decide to scrub the flight task entirely") {
                    com.ternparagliding.utils.ReportGenerator.logStep("ACTION", "Dispatching RemoveRoute action")
                    val route = store.state.value.routes.first()
                    store.dispatch(MapAction.RemoveRoute(route.id))

                    // Wait for UI update
                    com.ternparagliding.utils.ReportGenerator.logStep("WAIT", "Waiting for empty state message")
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithText("No nearby routes found").fetchSemanticsNodes().isNotEmpty()
                    }
                }

                then("The task is removed and my list is clean") {
                    com.ternparagliding.utils.ReportGenerator.logStep("VERIFY", "Asserting empty state message is displayed")
                    composeTestRule.onNodeWithText("No nearby routes found").assertIsDisplayed()

                    // Validate Logcat
                    com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                    com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                    com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
                }
            }
        }
    }

    @Test
    fun testReorderWaypoints() {
        scenario("testReorderWaypoints") {
            story("As a competition pilot, I need to easily reorder my turning points to optimize my flight path during the task briefing.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]

                val waypoint1 = com.ternparagliding.model.Waypoint(lat = 10.0, lon = 10.0, label = "Start")
                val waypoint2 = com.ternparagliding.model.Waypoint(lat = 11.0, lon = 11.0, label = "End")
                val route = com.ternparagliding.model.Route(
                    name = "Reorder Test Route",
                    waypoints = listOf(waypoint1, waypoint2)
                )
                store.dispatch(MapAction.AddRoute(route))
                store.dispatch(MapAction.SelectRoute(route.id))
                given("I have a flight task with 'Start' as point 1 and 'End' as point 2") {
                    com.ternparagliding.utils.ReportGenerator.logStep("SETUP", "Ensuring Route Detail Panel is visible")
                    // In the real activity, selecting a route should show the panel
                    composeTestRule.onNodeWithText("1. Start").assertIsDisplayed()
                    composeTestRule.onNodeWithText("2. End").assertIsDisplayed()
                }

                `when`("I swap the order, moving 'Start' to the end of the sequence") {
                    com.ternparagliding.utils.ReportGenerator.logStep("ACTION", "Dispatching ReorderWaypoint action")
                    store.dispatch(MapAction.ReorderWaypoint(route.id, 0, 1))

                    // Wait for UI update
                    com.ternparagliding.utils.ReportGenerator.logStep("WAIT", "Waiting for reorder update")
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithText("1. End").fetchSemanticsNodes().isNotEmpty()
                    }
                }

                then("The task sequence is updated: 'End' is now point 1 and 'Start' is point 2") {
                    com.ternparagliding.utils.ReportGenerator.logStep("VERIFY", "Asserting new order: End, Start")
                    composeTestRule.onNodeWithText("1. End").assertIsDisplayed()
                    composeTestRule.onNodeWithText("2. Start").assertIsDisplayed()

                    // Validate Logcat
                    com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                    com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                    com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
                }
            }
        }
    }

    @Test
    fun testCreateRouteFromWaypoints() {
        scenario("testCreateRouteFromWaypoints") {
            story("As a pilot planning a complex journey across multiple landmarks, I want to batch-create a route from a list of waypoints.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]

                val waypoint1 = com.ternparagliding.model.Waypoint(lat = 40.0, lon = -105.0, label = "Boulder")
                val waypoint2 = com.ternparagliding.model.Waypoint(lat = 40.1, lon = -105.1, label = "Longmont")
                
                given("I am preparing a new cross-country mission in the task manager") {
                    com.ternparagliding.utils.ReportGenerator.logStep("SETUP", "Opening Route List")
                    composeTestRule.onNodeWithContentDescription("Route Management").performClick()
                    composeTestRule.waitForIdle()
                }

                `when`("I assemble a new flight task named 'Front Range Task' from my selected landmarks") {
                    com.ternparagliding.utils.ReportGenerator.logStep("ACTION", "Creating route from waypoints")
                    val route = com.ternparagliding.model.Route.fromWaypoints("Front Range Task", listOf(waypoint1, waypoint2))
                    store.dispatch(MapAction.AddRoute(route))

                    // Wait for UI update
                    com.ternparagliding.utils.ReportGenerator.logStep("WAIT", "Waiting for route to appear")
                    composeTestRule.onNodeWithText("Front Range Task").assertIsDisplayed()
                }

                then("the route 'Front Range Task' with both waypoints is visible in the UI") {
                    composeTestRule.onNodeWithText("Front Range Task").assertIsDisplayed()
                    composeTestRule.onNodeWithText("Boulder", substring = true).assertExists()
                    composeTestRule.onNodeWithText("Longmont", substring = true).assertExists()
                }
            }
        }
    }

    @Test
    fun testShareRoute() {
        scenario("testShareRoute") {
            story("As a pilot, I want to share my flight task with my friends or ground crew via a QR code or standard Android sharing.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[com.ternparagliding.redux.MapStore::class.java]

                val route = com.ternparagliding.model.Route(name = "Share Test Route")
                store.dispatch(MapAction.AddRoute(route))
                store.dispatch(MapAction.SelectRoute(route.id))
                
                given("I have selected my 'Share Test Route' in the task manager") {
                    composeTestRule.waitUntil(timeoutMillis = 5000) {
                        composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isNotEmpty()
                    }
                }

                `when`("I initiate the sharing process") {
                    composeTestRule.onNodeWithContentDescription("Share Route").performClick()
                }

                then("The system prepares the task data for external sharing") {
                    com.ternparagliding.utils.ReportGenerator.waitForLog("RouteIOManager", "Sharing route: Share Test Route")
                }
            }
        }
    }
}
