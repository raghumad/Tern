package com.madanala.tern

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
 */
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.madanala.tern.ui.theme.TernTheme

@org.junit.Ignore
abstract class BaseUITest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val permissionRule: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    )

    /**
     * Set content wrapped in TernTheme(darkTheme = true) for aviation-standard testing.
     */
    fun setThemeContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            TernTheme(darkTheme = true) {
                content()
            }
        }
    }



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

    @org.junit.Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.madanala.tern.utils.CacheManager.initialize(context)
    }

    @After
    fun tearDown() {
    }
}
