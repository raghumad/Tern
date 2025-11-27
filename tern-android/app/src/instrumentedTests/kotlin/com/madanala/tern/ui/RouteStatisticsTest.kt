package com.madanala.tern.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.ReportGenerator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteStatisticsTest : BddTest() {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testRouteStatisticsDisplay() {
        val store = MapStore()
        
        // Create a route with known distance
        // Waypoint 1: 0,0
        // Waypoint 2: 0,1 (~111km)
        val waypoint1 = com.madanala.tern.model.Waypoint(lat = 0.0, lon = 0.0, label = "Start")
        val waypoint2 = com.madanala.tern.model.Waypoint(lat = 0.0, lon = 1.0, label = "End")
        
        val route = com.madanala.tern.model.Route(
            name = "Stats Test Route",
            waypoints = listOf(waypoint1, waypoint2)
        )
        
        store.dispatch(MapAction.AddRoute(route))
        store.dispatch(MapAction.SelectRoute(route.id))

        scenario("testRouteStatisticsDisplay") {
            given("I have a route selected and RouteDetailPanel is visible") {
                ReportGenerator.logStep("SETUP", "Initializing RouteDetailPanel with selected route")
                composeTestRule.setContent {
                    com.madanala.tern.ui.components.RouteDetailPanel(
                        store = store,
                        isVisible = true,
                        onDismiss = {}
                    )
                }
                ReportGenerator.logStep("VERIFY", "Route name is displayed")
                composeTestRule.onNodeWithText("Stats Test Route").assertIsDisplayed()
            }

            then("I see the route statistics") {
                ReportGenerator.logStep("VERIFY", "Checking for distance display")
                // Distance should be approx 111km. The UI might show "111.2 km" or similar.
                // We check for substring match or specific format depending on implementation.
                // Since "km" might appear multiple times, we check that at least one instance is visible.
                val kmNodes = composeTestRule.onAllNodesWithText("km", substring = true)
                if (kmNodes.fetchSemanticsNodes().isEmpty()) {
                    throw AssertionError("Expected to find text containing 'km' but found none")
                }
                
                // Assert the first one is displayed
                kmNodes.onFirst().assertIsDisplayed()
                
                ReportGenerator.logStep("VERIFY", "Checking for waypoint count")
                composeTestRule.onNodeWithText("Waypoints (2)", substring = true).assertIsDisplayed()
            }
        }
    }
}
