package com.madanala.tern.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.screens.TernMapScreen
import com.madanala.tern.ui.theme.TernTheme
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.CacheManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest : BddTest() {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun verifyNavigationToMap() {
        scenario("verifyNavigationToMap") {
            given("the app is initialized") {
                // Initialize CacheManager
                com.madanala.tern.utils.CacheManager.initialize(composeTestRule.activity.applicationContext)
                
                // Initialize OSMDroid Configuration
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                org.osmdroid.config.Configuration.getInstance().load(context, androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
                org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName
            }

            and("I have location permissions") {
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                val uiAutomation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
                uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            and("I inject Mock Location (Boulder, CO)") {
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                try {
                    locationManager.addTestProvider(
                        android.location.LocationManager.GPS_PROVIDER,
                        false, false, false, false, true, true, true, 1, 1
                    )
                    locationManager.setTestProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
                    
                    val mockLocation = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                        latitude = 40.0150
                        longitude = -105.2705
                        altitude = 1600.0
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        accuracy = 1.0f
                    }
                    locationManager.setTestProviderLocation(android.location.LocationManager.GPS_PROVIDER, mockLocation)
                } catch (e: SecurityException) {
                    println("Warning: Could not set mock location: ${e.message}")
                }
            }

            `when`("the app content is set") {
                composeTestRule.setContent {
                    TernTheme {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            TernMapScreen()
                        }
                    }
                }
                composeTestRule.onNodeWithTag("map_view").assertExists()
            }

            `when`("I interact with the map") {
                // Placeholder for future interactions
            }

            then("I see the Map screen") {
                composeTestRule.onNodeWithTag("map_view").assertExists()
                
                // Wait for tiles to load so screenshot is not empty
                com.madanala.tern.utils.MapTestHelper.waitForMapTiles()
            }
        }
    }
}
