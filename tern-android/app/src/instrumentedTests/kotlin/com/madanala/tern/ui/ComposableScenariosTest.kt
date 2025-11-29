package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.utils.BddTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposableScenariosTest : BddTest() {

    // --- Reusable Scenario Logic ---
    
    private fun scenarioAppLaunch() {
        scenario("App Launch Setup") {
            givenAppIsLaunchedOnMap()
        }
    }

    private fun scenarioCreateRoute(routeName: String) {
        scenario("Create Route: $routeName") {
            // Note: In a real reusable scenario, we might check if we are already on the map
            // or if we need to navigate there. Since we disabled Orchestrator, 
            // state persists, so we should be careful.
            
            `when`("I create a route named '$routeName'") {
                // Logic to create route (mocked for this demo or using actual store)
                // For demo, we just log
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Creating route: $routeName")
            }
            then("the route '$routeName' is visible") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Route $routeName exists")
            }
        }
    }

    // --- Compound Scenario ---

    @Test
    fun testComplexWorkflow() {
        // This test composes multiple scenarios
        
        // 1. Run the Launch Scenario
        scenarioAppLaunch()

        // 2. Run a Create Route Scenario
        scenarioCreateRoute("Morning Flight")

        // 3. Run a dependent scenario that requires the previous ones
        scenario("Modify Morning Flight") {
            given("I have completed the 'Create Route' scenario") {
                // We could call the function here if it wasn't already called,
                // but since we are running sequentially in this test, we just assert the state.
                // OR, if we want to treat it as a nested requirement:
                com.madanala.tern.utils.ReportGenerator.logStep("INFO", "Pre-requisite met by previous steps")
            }
            
            `when`("I rename it to 'Evening Flight'") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Renaming route")
            }
            
            then("it is updated") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Route renamed")
            }
        }
    }
    
    @Test
    fun testNestedScenarioCall() {
        // Demonstrating calling a scenario INSIDE a given block
        scenario("Delete Route Workflow") {
            given("I have a route created (using reusable scenario)") {
                // Nesting the scenario execution here
                scenarioCreateRoute("Route To Delete")
            }
            
            `when`("I delete the route") {
                com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Deleting route")
            }
            
            then("it is gone") {
                com.madanala.tern.utils.ReportGenerator.logStep("VERIFY", "Route deleted")
            }
        }
    }
}
