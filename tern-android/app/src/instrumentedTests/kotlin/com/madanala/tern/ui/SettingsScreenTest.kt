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
import androidx.lifecycle.ViewModelProvider
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.ReportGenerator
import com.madanala.tern.TernParaglidingActivity
import com.madanala.tern.redux.MapStore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest : MapVisualTest() {

    // composeTestRule is inherited from MapVisualTest

    @Test
    fun testUnitPreferences() {
        scenario("testUnitPreferences") {
            story("As a pilot from a region that uses Imperial units, I want to change my distance and speed preferences to miles and mph so that I can intuitively understand my altitude and groundspeed during flight without mental math.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                given("I have opened the pre-flight settings panel") {
                    composeTestRule.onNodeWithContentDescription("Settings").performClick()
                    composeTestRule.waitForIdle()
                    composeTestRule.onNodeWithText("Units").assertIsDisplayed()
                }

                `when`("I switch the Distance units to miles (mi)") {
                    ReportGenerator.logStep("ACTION", "Clicking on 'mi' button")
                    composeTestRule.onNodeWithTag("btn_Distance_mi").performClick()
                }

                then("the flight computer should update its distance preference to Miles") {
                    ReportGenerator.logStep("VERIFY", "Checking store state for distance unit")
                    val currentUnit = store.state.value.settingsState.distanceUnit
                    if (currentUnit != "mi") {
                        throw AssertionError("Expected distance unit to be 'mi' but was '$currentUnit'")
                    }
                }

                `when`("I switch the Speed units to kilometers per hour (kph) for better resolution") {
                    ReportGenerator.logStep("ACTION", "Clicking on 'kph' button")
                    composeTestRule.onNodeWithTag("btn_Speed_kph").performClick()
                }

                then("the groundspeed display should immediately reflect the metric preference") {
                    ReportGenerator.logStep("VERIFY", "Checking store state for speed unit")
                    val currentUnit = store.state.value.settingsState.speedUnit
                    if (currentUnit != "kph") {
                        throw AssertionError("Expected speed unit to be 'kph' but was '$currentUnit'")
                    }
                }
            }
        }
    }

    @Test
    fun testLayerToggles() {
        scenario("testLayerToggles") {
            story("As a pilot who wants to minimize distractions on the map during a leisure flight, I want to toggle off specific layers like airspace boundaries when I'm flying in a well-known, simple area.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                given("I am reviewing my map overlay settings") {
                    composeTestRule.onNodeWithContentDescription("Settings").performClick()
                    composeTestRule.waitForIdle()
                    composeTestRule.onNodeWithText("Map Layers").assertIsDisplayed()
                }

                then("I should see that specialized aviation layers like Airspaces are active by default") {
                    ReportGenerator.logStep("VERIFY", "Checking Airspaces toggle is ON")
                    composeTestRule.onNodeWithTag("toggle_Airspaces").assertIsOn()
                }

                `when`("I choose to declutter the map by disabling the Airspace overlay") {
                    ReportGenerator.logStep("ACTION", "Clicking Airspaces toggle")
                    composeTestRule.onNodeWithTag("toggle_Airspaces").performClick()
                }
                
                then("the map should instantly remove the airspace polygons from view") {
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
}
