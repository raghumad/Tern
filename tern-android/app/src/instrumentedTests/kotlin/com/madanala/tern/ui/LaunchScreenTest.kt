package com.madanala.tern.ui

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.madanala.tern.TernParaglidingActivity
import com.madanala.tern.utils.ScreenshotHelper
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchScreenTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(TernParaglidingActivity::class.java)

    @Test
    fun appLaunchesSuccessfully() {
        // Wait for app to settle
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle(3000)

        // Take screenshot
        ScreenshotHelper.takeScreenshot("launch_screen_baseline")
    }
}
