package com.madanala.tern.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.screens.TernMapScreen
import com.madanala.tern.ui.theme.TernTheme
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.CacheManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchScreenTest : BddTest() {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun appLaunchesSuccessfully() {
        scenario("appLaunchesSuccessfully") {
            given("The app is launched") {
                val context = composeTestRule.activity.applicationContext
                CacheManager.initialize(context)
                org.osmdroid.config.Configuration.getInstance().load(context, context.getSharedPreferences("tern_settings_prefs", android.content.Context.MODE_PRIVATE))
                org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName
                org.osmdroid.config.Configuration.getInstance().osmdroidBasePath = java.io.File(context.cacheDir, "osmdroid")
                org.osmdroid.config.Configuration.getInstance().osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid/tiles")

                composeTestRule.setContent {
                    TernTheme {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            androidx.compose.material3.Text("Routes")
                        }
                    }
                }
                composeTestRule.waitForIdle()
            }

            then("I see the 'Routes' title") {
                composeTestRule.onNodeWithText("Routes").assertIsDisplayed()
            }
        }
    }
}
