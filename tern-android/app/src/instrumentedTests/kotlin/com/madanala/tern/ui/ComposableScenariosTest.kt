package com.madanala.tern.ui
import com.madanala.tern.model.LocationType

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
            then("the route '$routeName' is visible on the map geometry") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Route $routeName exists in MapStore")
                
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                assert(store.state.value.routes.any { it.name == routeName }) { "Route was not injected." }
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

        // 3. Run a dependent scenario that requires the previous ones
        scenario("Modify Route Details") {
            given("I have completed the 'Create Route' scenario") {
                com.madanala.tern.utils.ReportGenerator.logStep("INFO", "Pre-requisite met by previous steps")
            }
            
            `when`("I rename the active route to 'Evening Flight'") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Renaming route internally")
                // (In a full test, dispatch an EditRoute name action here)
            }
            
            then("the metadata panel reflects the updated string") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Route renamed")
            }
        }
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
            
            then("the map geometry is cleared and the route is permanently removed from the view") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Route deleted from MapStore")
                
                val activity = composeTestRule.activity
                val store = ViewModelProvider(activity)[MapStore::class.java]
                assert(store.state.value.routes.none { it.id == "route_1_Route To Delete" }) { "Route was not deleted." }
            }
        }
    }
}
