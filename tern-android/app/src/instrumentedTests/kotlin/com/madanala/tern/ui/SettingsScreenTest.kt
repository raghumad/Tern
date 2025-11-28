package com.madanala.tern.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.ui.components.SettingsSheet
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.ReportGenerator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest : BddTest() {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testUnitPreferences() {
        val store = MapStore()

        scenario("testUnitPreferences") {
            given("I have the Settings Sheet open") {
                ReportGenerator.logStep("SETUP", "Initializing SettingsSheet with fresh store")
                composeTestRule.setContent {
                    SettingsSheet(
                        onDismiss = {},
                        store = store
                    )
                }
                ReportGenerator.logStep("VERIFY", "Checking for 'Units' header")
                composeTestRule.onNodeWithText("Units").assertIsDisplayed()
            }

            `when`("I change the Distance unit to 'mi'") {
                ReportGenerator.logStep("ACTION", "Clicking on 'mi' button")
                composeTestRule.onNodeWithTag("btn_Distance_mi").performClick()
            }

            then("the store should update the distance unit preference") {
                ReportGenerator.logStep("VERIFY", "Checking store state for distance unit")
                val currentUnit = store.state.value.settingsState.distanceUnit
                if (currentUnit != "mi") {
                    throw AssertionError("Expected distance unit to be 'mi' but was '$currentUnit'")
                }
            }

            `when`("I change the Speed unit to 'kph'") {
                ReportGenerator.logStep("ACTION", "Clicking on 'kph' button")
                composeTestRule.onNodeWithTag("btn_Speed_kph").performClick()
            }

            then("the store should update the speed unit preference") {
                ReportGenerator.logStep("VERIFY", "Checking store state for speed unit")
                val currentUnit = store.state.value.settingsState.speedUnit
                if (currentUnit != "kph") {
                    throw AssertionError("Expected speed unit to be 'kph' but was '$currentUnit'")
                }
            }
        }
    }

    @Test
    fun testLayerToggles() {
        val store = MapStore()

        scenario("testLayerToggles") {
            given("I have the Settings Sheet open") {
                ReportGenerator.logStep("SETUP", "Initializing SettingsSheet")
                composeTestRule.setContent {
                    SettingsSheet(
                        onDismiss = {},
                        store = store
                    )
                }
                ReportGenerator.logStep("VERIFY", "Checking for 'Map Layers' header")
                composeTestRule.onNodeWithText("Map Layers").assertIsDisplayed()
            }

            then("Airspaces should be enabled by default") {
                ReportGenerator.logStep("VERIFY", "Checking Airspaces toggle is ON")
                composeTestRule.onNodeWithTag("toggle_Airspaces").assertIsOn()
            }

            `when`("I toggle Airspaces off") {
                ReportGenerator.logStep("ACTION", "Clicking Airspaces toggle")
                composeTestRule.onNodeWithTag("toggle_Airspaces").performClick()
            }
            
            then("Airspaces should be disabled") {
                ReportGenerator.logStep("VERIFY", "Checking Airspaces toggle is OFF")
                composeTestRule.onNodeWithTag("toggle_Airspaces").assertIsOff()
                
                ReportGenerator.logStep("VERIFY", "Checking store state")
                if (store.state.value.overlayState.airspaces.enabled) {
                    throw AssertionError("Airspaces should be disabled in store")
                }
            }
        }
    }
}
