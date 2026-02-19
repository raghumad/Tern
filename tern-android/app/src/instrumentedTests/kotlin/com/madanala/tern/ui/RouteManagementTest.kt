package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.ReportGenerator

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.TernParaglidingActivity
import com.madanala.tern.redux.MapAction
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
            val activity = composeTestRule.activity as TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]
            given("I have a fresh map store and RouteListScreen is visible") {
                com.madanala.tern.utils.ReportGenerator.logStep("SETUP", "Opening RouteListScreen in real activity")
                
                // Navigate to Route List
                composeTestRule.onNodeWithContentDescription("Route Management").performClick()
                composeTestRule.waitForIdle()
                
                // Verify initial state
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Checking for empty state message")
                composeTestRule.onNodeWithText("No nearby routes found").assertIsDisplayed()
            }

            `when`("I create a new route named 'New Route 1'") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Dispatching AddRoute action")
                val newRoute = com.madanala.tern.model.Route(name = "New Route 1")
                store.dispatch(MapAction.AddRoute(newRoute))

                // Wait for UI update
                com.madanala.tern.utils.ReportGenerator.logStep("WAIT", "Waiting for 'New Route 1' to appear")
                composeTestRule.onNodeWithText("New Route 1").assertIsDisplayed()
            }

            then("I see 'New Route 1' in the list") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Asserting 'New Route 1' is displayed")
                composeTestRule.onNodeWithText("New Route 1").assertIsDisplayed()
            }

            `when`("I rename the route to 'My Awesome Flight'") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Dispatching UpdateRoute action (Rename)")
                val route = store.state.value.routes.first()
                val renamedRoute = route.copy(name = "My Awesome Flight")
                store.dispatch(MapAction.UpdateRoute(renamedRoute))

                // Wait for UI update
                com.madanala.tern.utils.ReportGenerator.logStep("WAIT", "Waiting for 'My Awesome Flight' to appear")
                composeTestRule.onNodeWithText("My Awesome Flight").assertIsDisplayed()
            }

            then("I see the route renamed to 'My Awesome Flight'") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Asserting 'My Awesome Flight' is displayed")
                composeTestRule.onNodeWithText("My Awesome Flight").assertIsDisplayed()
            }

            `when`("I toggle visibility of the route") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Dispatching UpdateRoute action (Visibility)")
                val route = store.state.value.routes.first()
                val hiddenRoute = route.copy(isVisible = false)
                store.dispatch(MapAction.UpdateRoute(hiddenRoute))

                // Wait for icon update
                com.madanala.tern.utils.ReportGenerator.logStep("WAIT", "Waiting for visibility icon update")
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithContentDescription("Show route").fetchSemanticsNodes().isNotEmpty()
                }
            }

            then("I see the 'Show route' icon indicating it is hidden") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Asserting 'Show route' icon is displayed")
                composeTestRule.onNodeWithContentDescription("Show route").assertIsDisplayed()
            }

            `when`("I delete the route") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Dispatching RemoveRoute action")
                val route = store.state.value.routes.first()
                store.dispatch(MapAction.RemoveRoute(route.id))

                // Wait for UI update
                com.madanala.tern.utils.ReportGenerator.logStep("WAIT", "Waiting for empty state message")
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("No nearby routes found").fetchSemanticsNodes().isNotEmpty()
                }
            }

            then("I see the empty state 'No nearby routes found'") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Asserting empty state message is displayed")
                composeTestRule.onNodeWithText("No nearby routes found").assertIsDisplayed()

                // Validate Logcat
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE UPDATE STORM")
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
            }
        }
    }

    @Test
    fun testReorderWaypoints() {
        scenario("testReorderWaypoints") {
            val activity = composeTestRule.activity as TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]

            val waypoint1 = com.madanala.tern.model.Waypoint(lat = 10.0, lon = 10.0, label = "Start")
            val waypoint2 = com.madanala.tern.model.Waypoint(lat = 11.0, lon = 11.0, label = "End")
            val route = com.madanala.tern.model.Route(
                name = "Reorder Test Route",
                waypoints = listOf(waypoint1, waypoint2)
            )
            store.dispatch(MapAction.AddRoute(route))
            store.dispatch(MapAction.SelectRoute(route.id))
            given("I have a route with 2 waypoints and RouteDetailPanel is visible") {
                com.madanala.tern.utils.ReportGenerator.logStep("SETUP", "Ensuring Route Detail Panel is visible")
                // In the real activity, selecting a route should show the panel
                composeTestRule.onNodeWithText("1. Start").assertIsDisplayed()
                composeTestRule.onNodeWithText("2. End").assertIsDisplayed()
            }

            `when`("I move 'Start' down to the second position") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Dispatching ReorderWaypoint action")
                store.dispatch(MapAction.ReorderWaypoint(route.id, 0, 1))

                // Wait for UI update
                com.madanala.tern.utils.ReportGenerator.logStep("WAIT", "Waiting for reorder update")
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("1. End").fetchSemanticsNodes().isNotEmpty()
                }
            }

            then("I see 'End' at position 1 and 'Start' at position 2") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Asserting new order: End, Start")
                composeTestRule.onNodeWithText("1. End").assertIsDisplayed()
                composeTestRule.onNodeWithText("2. Start").assertIsDisplayed()

                // Validate Logcat
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE UPDATE STORM")
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
            }
        }
    }

    @Test
    fun testCreateRouteFromWaypoints() {
        scenario("testCreateRouteFromWaypoints") {
            val activity = composeTestRule.activity as TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]

            val waypoint1 = com.madanala.tern.model.Waypoint(lat = 40.0, lon = -105.0, label = "Boulder")
            val waypoint2 = com.madanala.tern.model.Waypoint(lat = 40.1, lon = -105.1, label = "Longmont")
            
            given("I have a fresh map store") {
                com.madanala.tern.utils.ReportGenerator.logStep("SETUP", "Opening Route List")
                composeTestRule.onNodeWithContentDescription("Route Management").performClick()
                composeTestRule.waitForIdle()
            }

            `when`("I create a route from a list of waypoints") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Creating route from waypoints")
                val route = com.madanala.tern.model.Route.fromWaypoints("Front Range Task", listOf(waypoint1, waypoint2))
                store.dispatch(MapAction.AddRoute(route))

                // Wait for UI update
                com.madanala.tern.utils.ReportGenerator.logStep("WAIT", "Waiting for route to appear")
                composeTestRule.onNodeWithText("Front Range Task").assertIsDisplayed()
            }

            then("I see the route with the correct waypoints") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Asserting route and waypoint count")
                composeTestRule.onNodeWithText("Front Range Task").assertIsDisplayed()
                
                // Verify internal state
                val createdRoute = store.state.value.routes.find { it.name == "Front Range Task" }
                assert(createdRoute != null)
                assert(createdRoute!!.waypoints.size == 2)
                assert(createdRoute.waypoints[0].label == "Boulder")
                assert(createdRoute.waypoints[1].label == "Longmont")
            }
        }
    }

    @Test
    fun testShareRoute() {
        scenario("testShareRoute") {
            val activity = composeTestRule.activity as TernParaglidingActivity
            val store = ViewModelProvider(activity)[MapStore::class.java]

            val route = com.madanala.tern.model.Route(name = "Share Test Route")
            store.dispatch(MapAction.AddRoute(route))
            store.dispatch(MapAction.SelectRoute(route.id))
            
            given("I have a route and the RouteDetailPanel is visible") {
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithTag("RouteDetailPanel").fetchSemanticsNodes().isNotEmpty()
                }
            }

            `when`("I click the Share button") {
                composeTestRule.onNodeWithContentDescription("Share Route").performClick()
            }

            then("The RouteIOManager should be called to share the route") {
                com.madanala.tern.utils.ReportGenerator.waitForLog("RouteIOManager", "Sharing route: Share Test Route")
            }
        }
    }
}
