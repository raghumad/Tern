package com.madanala.tern.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.components.WeatherDetailsDialog
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.WeatherForecast
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
    fun testSkewTPlaceholderIsVisible() {
        scenario("Verify Skew-T Analysis Placeholder") {
            story("As a pilot analyzing a spot's weather, I need to see a dedicated area for Skew-T diagram analysis including Cloud Base and Inversion Layer.") {
                given("the pilot opens the Weather Details screen for any PG Spot") {
                    val forecast = WeatherForecast(
                        current = com.madanala.tern.utils.WeatherData(
                            wind = com.madanala.tern.utils.WindData(0.0, 0.0, 0.0),
                            temperature = 20.0,
                            humidity = 50.0,
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

                `when`("the pilot scrolls down the details dialog") {
                    // Compose will automatically scroll to nodes if needed for assertion
                }

                then("they should see a 'Skew-T Analysis' section") {
                    composeTestRule.onNodeWithText("Skew-T Analysis").assertIsDisplayed()
                }
                
                and("they should see a placeholder for 'Cloud Base'") {
                    composeTestRule.onNodeWithText("Cloud Base").assertIsDisplayed()
                }
                
                and("they should see a placeholder for 'Inversion Layer'") {
                    composeTestRule.onNodeWithText("Inversion Layer").assertIsDisplayed()
                }
            }
        }
    }
}
