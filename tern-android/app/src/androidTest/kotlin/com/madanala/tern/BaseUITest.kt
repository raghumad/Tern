package com.madanala.tern

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.madanala.tern.test.MockServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * Base class for Automated UI Tests.
 * Provides:
 * - ComposeTestRule for UI interaction
 * - MockServer for API mocking
 */
abstract class BaseUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    val mockServer = MockServer()

    @get:Rule
    val screenshotRule = object : TestWatcher() {
        override fun succeeded(description: Description) {
            captureScreenshot("success_${description.methodName}")
        }

        override fun failed(e: Throwable?, description: Description) {
            captureScreenshot("failure_${description.methodName}")
        }
    }

    private fun captureScreenshot(name: String) {
        try {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val dir = File("/sdcard/Pictures/screenshots")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "$name.png")
            device.takeScreenshot(file)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Before
    fun setup() {
        mockServer.server.start()
    }

    @After
    fun tearDown() {
        mockServer.server.shutdown()
    }
}
