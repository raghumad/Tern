package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.TernParaglidingActivity
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.ReportGenerator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.ViewModelProvider

@RunWith(AndroidJUnit4::class)
class RouteStatisticsTest : MapVisualTest() {

    // mapComposeTestRule is inherited from MapVisualTest

    @Test
    fun testRouteStatisticsDisplay() {
        scenario("testRouteStatisticsDisplay") {
            story("As a pilot planning a cross-country adventure, I want to see the total distance and waypoint count of my route so I can estimate my flight time and ensure I have enough battery and daylight for the journey.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                
                // Create a route with known distance (~111km)
                val waypoint1 = com.madanala.tern.model.Waypoint(lat = 0.0, lon = 0.0, label = "Start")
                val waypoint2 = com.madanala.tern.model.Waypoint(lat = 0.0, lon = 1.0, label = "End")
                val route = com.madanala.tern.model.Route(
                    name = "Stats Test Route",
                    waypoints = listOf(waypoint1, waypoint2)
                )

                given("I have selected a route for my upcoming flight") {
                    store.dispatch(MapAction.AddRoute(route))
                    store.dispatch(MapAction.SelectRoute(route.id))
                    ReportGenerator.logStep("SETUP", "Selected route should trigger panel in real activity")
                    composeTestRule.waitForIdle()
                }

                `when`("I open the route detail panel to review the flight plan") {
                    composeTestRule.onNodeWithText("Stats Test Route").assertIsDisplayed()
                }

                then("I should see the total calculated distance in kilometers") {
                    ReportGenerator.logStep("VERIFY", "Checking for distance display")
                    val kmNodes = composeTestRule.onAllNodesWithText("km", substring = true)
                    if (kmNodes.fetchSemanticsNodes().isEmpty()) {
                        throw AssertionError("Expected to find text containing 'km' but found none")
                    }
                    kmNodes.onFirst().assertIsDisplayed()
                }
                
                and("the total number of waypoints is correctly displayed") {
                    ReportGenerator.logStep("VERIFY", "Checking for waypoint count")
                    composeTestRule.onNodeWithText("Waypoints (2)", substring = true).assertIsDisplayed()
                }
            }
        }
    }
}
