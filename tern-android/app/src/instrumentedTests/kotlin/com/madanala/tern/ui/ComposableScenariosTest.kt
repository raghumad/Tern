package com.madanala.tern.ui
import com.madanala.tern.model.LocationType

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.MapAction
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposableScenariosTest : MapVisualTest() {

    // --- Reusable Scenario Logic ---
    
    private fun scenarioAppLaunch() {
        scenario("App Launch Setup") {
            givenAppIsLaunchedOnMap()
        }
    }

    private fun scenarioCreateRoute(routeName: String) {
        scenario("Create Route: $routeName") {
            `when`("I create a physical map route named '$routeName'") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Creating route: $routeName")
                
                // Inject physical Route into Redux Store instead of just logging a stub
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                
                // Generate a waypoint near Boulder so it renders visibly
                val waypoint = Waypoint(
                    id = "wp_1",
                    lat = 40.015,
                    lon = -105.27,
                    type = LocationType.TURNPOINT,
                    label = "Launch Point"
                )
                
                val route = Route(
                    id = "route_1_$routeName",
                    name = routeName,
                    waypoints = listOf(waypoint),
                    isVisible = true
                )
                
                store.dispatch(MapAction.AddRoute(route))
                store.dispatch(MapAction.SelectRoute(route.id))
                
                // Yield to Compose UI thread
                composeTestRule.waitForIdle()
            }
            then("the route '$routeName' appears in the route detail panel") {
                composeTestRule.onNodeWithText(routeName, substring = true).assertIsDisplayed()
            }
        }
    }

    // --- Compound Scenario ---

    @Test
    fun testCrossCountryRoutePlanningLifecycle() {
        // This test composes multiple sequential testing blocks to track a route lifecycle
        
        // 1. Run the Launch Scenario
        scenarioAppLaunch()

        // 2. Run a Create Route Scenario
        scenarioCreateRoute("Morning Flight")

        // Scenario 3 ("Modify Route Details") deleted — had zero assertions
        // and no actual rename dispatch. Route renaming is tested in
        // RouteManagementTest.testCreateRenameDeleteRoute.
    }
    
    @Test
    fun testRouteCreationAndDeletionLifecycle() {
        // Demonstrating a full route lifecycle scenario composed of reusable blocks
        scenario("Route Creation and Deletion Lifecycle") {
            given("I have an active flight route plotted on the map") {
                // Nesting the scenario execution here physically creates the route
                scenarioCreateRoute("Route To Delete")
            }
            
            `when`("I execute the command to delete the active route") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Deleting route physically via Redux")
                
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                // Dismiss route from MapStore
                store.dispatch(MapAction.RemoveRoute("route_1_Route To Delete"))
                composeTestRule.waitForIdle()
            }
            
            then("the route is no longer visible in the UI") {
                composeTestRule.onNodeWithText("Route To Delete").assertDoesNotExist()
            }
        }
    }
}
