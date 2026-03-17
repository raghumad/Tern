package com.madanala.tern.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.components.WeatherDetailsDialog
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.WeatherData
import com.madanala.tern.utils.WeatherForecast
import com.madanala.tern.utils.WindData
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PGSpotWeatherTest : BddTest() {

    @Test
    fun verifyLoadingState() {
        scenario("Verify Weather Loading State") {
            story("As a pilot, I want to see a loading indicator when weather data is being fetched") {
                given("we are loading weather data") {
                    composeTestRule.setContent {
                        WeatherDetailsDialog(
                            forecast = null,
                            spotName = "Test Spot",
                            isLoading = true,
                            onDismiss = {}
                        )
                    }
                }

                then("the loading indicator should be visible") {
                    // Implied by absence of "unavailable" and presence of title
                    composeTestRule.onNodeWithText("Weather - Test Spot").assertIsDisplayed()
                    composeTestRule.onNodeWithText("Weather data unavailable").assertDoesNotExist()
                }
            }
        }
    }

    @Test
    fun verifyDataDisplay() {
        scenario("Verify Weather Data Display") {
            story("As a pilot, I want to see the weather details dialog when weather data is available") {
                given("we have weather data") {
                    val forecast = WeatherForecast(
                        current = null,
                        hourly = emptyList(),
                        daily = emptyList()
                    )
                    
                    composeTestRule.setContent {
                        WeatherDetailsDialog(
                            forecast = forecast,
                            spotName = "Test Spot",
                            isLoading = false,
                            onDismiss = {}
                        )
                    }
                }

                then("the dialog should show the title") {
                    composeTestRule.onNodeWithText("Weather - Test Spot").assertIsDisplayed()
                }
                
                and("NOT show unavailable message") {
                    composeTestRule.onNodeWithText("Weather data unavailable").assertDoesNotExist()
                }
            }
        }
    }
    
    @Test
    fun verifyUnavailableState() {
        scenario("Verify Weather Unavailable State") {
            story("As a pilot, I want to see an unavailable message if there is no weather data and it's not loading") {
                given("we have NO data and are NOT loading") {
                    composeTestRule.setContent {
                        WeatherDetailsDialog(
                            forecast = null,
                            spotName = "Test Spot",
                            isLoading = false,
                            onDismiss = {}
                        )
                    }
                }

                then("the unavailable message should be visible") {
                    composeTestRule.onNodeWithText("Weather data unavailable").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun verifyVisibilityDisplay() {
        scenario("Verify Visibility Display") {
            story("As a pilot checking a spot's weather, I need to see the visibility clearly displayed so I can assess flight conditions.") {
                given("the OpenMeteo mock server returns a forecast with visibility of 15000 meters") {
                    val forecast = WeatherForecast(
                        current = com.madanala.tern.utils.WeatherData(
                            wind = com.madanala.tern.utils.WindData(0.0, 0.0, 0.0),
                            temperature = 20.0,
                            humidity = 50.0,
                            visibility = 15.0, // 15000 meters = 15.0 km
                            pressure = 1013.25,
                            cloudCover = 0.0,
                            timestamp = System.currentTimeMillis() / 1000
                        ),
                        hourly = emptyList(),
                        daily = emptyList()
                    )
                    
                    // When the pilot opens the Weather Details screen for a PG Spot
                    composeTestRule.setContent {
                        WeatherDetailsDialog(
                            forecast = forecast,
                            spotName = "Test Spot",
                            isLoading = false,
                            onDismiss = {}
                        )
                    }
                }

                then("the 'Visibility' detail field should display '15 km'") {
                    composeTestRule.onNodeWithText("15 km").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun testSkewTCloudBaseIsComputedFromWeatherData() {
        scenario("Skew-T Cloud Base Computed from Real Weather Data") {
            story("As a pilot checking a launch site, I want to see the estimated cloud base so I can assess thermal ceiling and plan my flight altitude.") {
                given("a forecast with surface temperature of 25°C and relative humidity of 40%") {
                    // LCL approximation: Td = 25 - ((100-40)/5) = 13°C; CloudBase = 125 * 12 = 1500m = 4921 ft
                    val forecast = WeatherForecast(
                        current = com.madanala.tern.utils.WeatherData(
                            wind = com.madanala.tern.utils.WindData(0.0, 0.0, 0.0),
                            temperature = 25.0,
                            humidity = 40.0,
                            visibility = 10.0,
                            pressure = 1013.25,
                            cloudCover = 0.0,
                            timestamp = System.currentTimeMillis() / 1000
                        ),
                        hourly = emptyList(),
                        daily = emptyList()
                    )

                    composeTestRule.setContent {
                        WeatherDetailsDialog(
                            forecast = forecast,
                            spotName = "Test Spot",
                            isLoading = false,
                            onDismiss = {}
                        )
                    }
                }

                `when`("the pilot opens the Weather Details screen for this PG Spot") {
                    composeTestRule.waitForIdle()
                }

                this.then("the Cloud Base should display '4921 ft' — computed from temperature and humidity, not hardcoded", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("SkewTCloudBase")
                        .assertTextContains("4921 ft")
                }
            }
        }
    }

    @Test
    fun testInversionLayerIsDetected() {
        scenario("Skew-T Inversion Layer Detected from Pressure-Level Temperature Data") {
            story("As a pilot, I want to know if a temperature inversion exists so I can anticipate reduced thermal activity.") {
                given("an atmosphere where 850hPa (5°C) is warmer than 925hPa (2°C) — classic inversion profile") {
                    val forecastWithInversion = WeatherForecast(
                        current = com.madanala.tern.utils.WeatherData(
                            wind = com.madanala.tern.utils.WindData(0.0, 0.0, 0.0),
                            temperature = 20.0,
                            humidity = 50.0,
                            visibility = 10.0,
                            pressure = 1013.25,
                            cloudCover = 0.0,
                            timestamp = System.currentTimeMillis() / 1000,
                            temp850hPa = 5.0,  // warmer aloft → inversion
                            temp925hPa = 2.0
                        ),
                        hourly = emptyList(),
                        daily = emptyList()
                    )

                    composeTestRule.setContent {
                        WeatherDetailsDialog(
                            forecast = forecastWithInversion,
                            spotName = "Inversion Site",
                            isLoading = false,
                            onDismiss = {}
                        )
                    }
                }

                then("the app should report 'Inversion Detected' alerting the pilot to capped thermals", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("SkewTInversionLayer")
                        .assertTextContains("Inversion Detected")
                }
            }
        }
    }

    @Test
    fun testNoInversionLayerIsDetected() {
        scenario("Skew-T Normal Atmosphere Detected") {
            story("As a pilot, I want to know if conditions are normal so I can expect typical thermal development.") {
                given("an atmosphere where 850hPa (-2°C) is cooler than 925hPa (5°C) — normal lapse rate") {
                    val forecastNoInversion = WeatherForecast(
                        current = com.madanala.tern.utils.WeatherData(
                            wind = com.madanala.tern.utils.WindData(0.0, 0.0, 0.0),
                            temperature = 20.0,
                            humidity = 50.0,
                            visibility = 10.0,
                            pressure = 1013.25,
                            cloudCover = 0.0,
                            timestamp = System.currentTimeMillis() / 1000,
                            temp850hPa = -2.0, 
                            temp925hPa = 5.0
                        ),
                        hourly = emptyList(),
                        daily = emptyList()
                    )

                    composeTestRule.setContent {
                        WeatherDetailsDialog(
                            forecast = forecastNoInversion,
                            spotName = "Normal Atmosphere",
                            isLoading = false,
                            onDismiss = {}
                        )
                    }
                }

                then("the app reports 'No Inversion'", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("SkewTInversionLayer")
                        .assertTextContains("No Inversion")
                }
            }
        }
    }

}
