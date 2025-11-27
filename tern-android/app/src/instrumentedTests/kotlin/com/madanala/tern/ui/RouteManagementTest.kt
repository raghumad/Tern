package com.madanala.tern.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.ui.screens.RouteListScreen
import com.madanala.tern.utils.ScreenshotHelper
import com.madanala.tern.utils.BddTest
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteManagementTest : BddTest() {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testCreateRenameDeleteRoute() {
        val store = MapStore()

        scenario("testCreateRenameDeleteRoute") {
            given("I have a fresh map store and RouteListScreen is visible") {
                composeTestRule.setContent {
                    RouteListScreen(
                        store = store,
                        onRouteSelected = { /* No-op for this test */ },
                        onDismiss = { /* No-op */ }
                    )
                }
                // Verify initial state
                composeTestRule.onNodeWithText("No nearby routes found").assertIsDisplayed()
            }

            `when`("I create a new route named 'New Route 1'") {
                val newRoute = com.madanala.tern.model.Route(name = "New Route 1")
                store.dispatch(MapAction.AddRoute(newRoute))

                // Wait for UI update
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("New Route 1").fetchSemanticsNodes().isNotEmpty()
                }
            }

            then("I see 'New Route 1' in the list") {
                composeTestRule.onNodeWithText("New Route 1").assertIsDisplayed()
            }

            `when`("I rename the route to 'My Awesome Flight'") {
                val route = store.state.value.routes.first()
                val renamedRoute = route.copy(name = "My Awesome Flight")
                store.dispatch(MapAction.UpdateRoute(renamedRoute))

                // Wait for UI update
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("My Awesome Flight").fetchSemanticsNodes().isNotEmpty()
                }
            }

            then("I see the route renamed to 'My Awesome Flight'") {
                composeTestRule.onNodeWithText("My Awesome Flight").assertIsDisplayed()
            }

            `when`("I toggle visibility of the route") {
                val route = store.state.value.routes.first()
                val hiddenRoute = route.copy(isVisible = false)
                store.dispatch(MapAction.UpdateRoute(hiddenRoute))

                // Wait for icon update
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithContentDescription("Show route").fetchSemanticsNodes().isNotEmpty()
                }
            }

            then("I see the 'Show route' icon indicating it is hidden") {
                composeTestRule.onNodeWithContentDescription("Show route").assertIsDisplayed()
            }

            `when`("I delete the route") {
                val route = store.state.value.routes.first()
                store.dispatch(MapAction.RemoveRoute(route.id))

                // Wait for UI update
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("No nearby routes found").fetchSemanticsNodes().isNotEmpty()
                }
            }

            then("I see the empty state 'No nearby routes found'") {
                composeTestRule.onNodeWithText("No nearby routes found").assertIsDisplayed()
            }
        }
    }

    @Test
    fun testReorderWaypoints() {
        val store = MapStore()
        val waypoint1 = com.madanala.tern.model.Waypoint(lat = 10.0, lon = 10.0, label = "Start")
        val waypoint2 = com.madanala.tern.model.Waypoint(lat = 11.0, lon = 11.0, label = "End")
        val route = com.madanala.tern.model.Route(
            name = "Reorder Test Route",
            waypoints = listOf(waypoint1, waypoint2)
        )
        store.dispatch(MapAction.AddRoute(route))
        store.dispatch(MapAction.SelectRoute(route.id))

        scenario("testReorderWaypoints") {
            given("I have a route with 2 waypoints and RouteDetailPanel is visible") {
                composeTestRule.setContent {
                    com.madanala.tern.ui.components.RouteDetailPanel(
                        store = store,
                        isVisible = true,
                        onDismiss = {}
                    )
                }
                composeTestRule.onNodeWithText("1. Start").assertIsDisplayed()
                composeTestRule.onNodeWithText("2. End").assertIsDisplayed()
            }

            `when`("I move 'Start' down to the second position") {
                store.dispatch(MapAction.ReorderWaypoint(route.id, 0, 1))

                // Wait for UI update
                composeTestRule.waitUntil(timeoutMillis = 5000) {
                    composeTestRule.onAllNodesWithText("1. End").fetchSemanticsNodes().isNotEmpty()
                }
            }

            then("I see 'End' at position 1 and 'Start' at position 2") {
                composeTestRule.onNodeWithText("1. End").assertIsDisplayed()
                composeTestRule.onNodeWithText("2. Start").assertIsDisplayed()
            }
        }
    }
}
