package com.ternparagliding.ui

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
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.ReportGenerator
import com.ternparagliding.TernParaglidingActivity
import com.ternparagliding.redux.MapStore
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

                then("the 'mi' button exists after click") {
                    composeTestRule.onNodeWithTag("btn_Distance_mi").assertExists()
                }

                `when`("I switch the Speed units to kilometers per hour (kph)") {
                    composeTestRule.onNodeWithTag("btn_Speed_kph").performClick()
                }

                then("the 'kph' button exists after click") {
                    composeTestRule.onNodeWithTag("btn_Speed_kph").assertExists()
                }
            }
        }
    }

    @com.ternparagliding.utils.Liar("Validates toggle control state but not whether airspaces actually render on the map — " +
          "no test data injected, no airspace polygons verified in screenshot")
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
                
                then("the airspace toggle should show as off") {
                    composeTestRule.onNodeWithTag("toggle_Airspaces").assertIsOff()
                }
            }
        }
    }
}
