package com.madanala.tern.ui

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.utils.MapVisualTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleMapTest : MapVisualTest() {

    @Test
    fun testActivityLaunches() {
        scenario("Activity Launches and Map is Visible") {
            composeTestRule.onNode(hasTestTag("map_view")).assertExists()
        }
    }
}
