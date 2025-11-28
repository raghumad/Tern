package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.model.FlightMode
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.ReportGenerator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlightModeLogicTest : BddTest() {

    // composeTestRule is inherited from BaseUITest via BddTest

    @Test
    fun testFlightModeStateTransition() {
        val store = MapStore()

        scenario("Flight Mode State Transition") {
            given("the initial flight mode is GROUND") {
                ReportGenerator.logStep("SETUP", "Initializing store")
                assertEquals("Initial mode should be GROUND", FlightMode.GROUND, store.state.value.currentFlightMode)
            }

            `when`("I set the flight mode to THERMAL") {
                ReportGenerator.logStep("ACTION", "Dispatching SetFlightMode(THERMAL)")
                store.dispatch(MapAction.SetFlightMode(FlightMode.THERMAL))
                
                ReportGenerator.logStep("WAIT", "Waiting for state update")
                composeTestRule.waitUntil { store.state.value.currentFlightMode == FlightMode.THERMAL }
            }

            then("the store should update the flight mode to THERMAL") {
                ReportGenerator.logStep("VERIFY", "Checking store state")
                assertEquals("Mode should be THERMAL", FlightMode.THERMAL, store.state.value.currentFlightMode)
            }

            `when`("I set the flight mode to FLIGHT") {
                ReportGenerator.logStep("ACTION", "Dispatching SetFlightMode(FLIGHT)")
                store.dispatch(MapAction.SetFlightMode(FlightMode.FLIGHT))
                
                ReportGenerator.logStep("WAIT", "Waiting for state update")
                composeTestRule.waitUntil { store.state.value.currentFlightMode == FlightMode.FLIGHT }
            }

            then("the store should update the flight mode to FLIGHT") {
                ReportGenerator.logStep("VERIFY", "Checking store state")
                assertEquals("Mode should be FLIGHT", FlightMode.FLIGHT, store.state.value.currentFlightMode)
            }
        }
    }
}
