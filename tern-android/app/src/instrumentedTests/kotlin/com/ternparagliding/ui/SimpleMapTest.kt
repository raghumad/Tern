package com.ternparagliding.ui

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.utils.MapVisualTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleMapTest : MapVisualTest() {

    @Test
    fun testActivityLaunches() {
        scenario("Activity Launches and Map is Visible") {
            story("As a pilot, I want the app to start reliably and immediately show the map so I can begin my pre-flight checks without delay.") {
                then("The main flight activity launches and the map interface is ready") {
                    composeTestRule.onNode(hasTestTag("map_view")).assertExists()
                }
                and("The Launch lifecycle executes without exceeding maximum GC retention SLAs (250 MB Peak Heap)") {
                    com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                    com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "MEMORY_PRESSURE")
                }
            }
        }
    }
}
