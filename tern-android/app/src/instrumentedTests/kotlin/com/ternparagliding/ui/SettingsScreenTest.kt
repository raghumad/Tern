package com.ternparagliding.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
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
                    // The Units section sits below Mezulla + Map Layers in the
                    // settings LazyColumn, so scroll it into view first (offscreen
                    // lazy items aren't composed — that's the blanket-run flake).
                    composeTestRule.onNodeWithTag("settings_list")
                        .performScrollToNode(hasText("Units"))
                    composeTestRule.onNodeWithText("Units").assertIsDisplayed()
                }

                and("Distance defaults to km and Speed to knots") {
                    // Honest baseline: the defaults are the *selected* choices, so
                    // the clicks below genuinely change the selection.
                    composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("btn_Distance_km"))
                    composeTestRule.onNodeWithTag("btn_Distance_km").assertIsSelected()
                    composeTestRule.onNodeWithTag("btn_Distance_mi").assertIsNotSelected()
                    assertThat(store.state.value.settingsState.distanceUnit).isEqualTo("km")
                    assertThat(store.state.value.settingsState.speedUnit).isEqualTo("kn")
                }

                `when`("I switch the Distance units to miles (mi)") {
                    ReportGenerator.logStep("ACTION", "Clicking on 'mi' button")
                    composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("btn_Distance_mi"))
                    composeTestRule.onNodeWithTag("btn_Distance_mi").performClick()
                    composeTestRule.waitForIdle()
                }

                then("'mi' becomes the selected distance unit, in both the UI and the app's preferences") {
                    // Pilot-visible: the 'mi' button is now highlighted (selected),
                    // 'km' is not — and the underlying preference that drives every
                    // distance readout in the app actually changed.
                    composeTestRule.onNodeWithTag("btn_Distance_mi").assertIsSelected()
                    composeTestRule.onNodeWithTag("btn_Distance_km").assertIsNotSelected()
                    assertThat(store.state.value.settingsState.distanceUnit).isEqualTo("mi")
                }

                `when`("I switch the Speed units to kilometers per hour (kph)") {
                    composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("btn_Speed_kph"))
                    composeTestRule.onNodeWithTag("btn_Speed_kph").performClick()
                    composeTestRule.waitForIdle()
                }

                then("'kph' becomes the selected speed unit, in both the UI and the app's preferences") {
                    composeTestRule.onNodeWithTag("btn_Speed_kph").assertIsSelected()
                    composeTestRule.onNodeWithTag("btn_Speed_kn").assertIsNotSelected()
                    assertThat(store.state.value.settingsState.speedUnit).isEqualTo("kph")
                }
            }
        }
    }

    // Note: this test verifies the toggle control state (Compose Switch node), which is what the
    // pilot interacts with. It does NOT verify that MapLibre actually hides/shows the airspace
    // FillLayer -- airspace polygons are GPU-drawn and not accessible via Compose semantics.
    // Verifying the downstream MapLibre render requires a human test or screenshot comparison.
    @Test
    fun testLayerToggles() {
        scenario("testLayerToggles") {
            story("As a pilot who wants to minimize distractions on the map during a leisure flight, I want to toggle off specific layers like airspace boundaries when I'm flying in a well-known, simple area.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                given("I am reviewing my map overlay settings") {
                    composeTestRule.onNodeWithContentDescription("Settings").performClick()
                    composeTestRule.waitForIdle()
                    composeTestRule.onNodeWithTag("settings_list")
                        .performScrollToNode(hasText("Map Layers"))
                    composeTestRule.onNodeWithText("Map Layers").assertIsDisplayed()
                }

                then("I should see that specialized aviation layers like Airspaces are active by default") {
                    ReportGenerator.logStep("VERIFY", "Checking Airspaces toggle is ON")
                    composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("toggle_Airspaces"))
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
