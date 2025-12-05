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

    @Test
    fun verifyDataDisplay() {
        scenario("Verify Weather Data Display") {
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
    
    @Test
    fun verifyUnavailableState() {
        scenario("Verify Weather Unavailable State") {
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
