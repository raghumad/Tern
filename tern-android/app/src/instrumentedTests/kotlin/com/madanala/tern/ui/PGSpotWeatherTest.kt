package com.madanala.tern.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.components.WeatherDetailsDialog
import com.madanala.tern.utils.WeatherForecast
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PGSpotWeatherTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun verifyLoadingState() {
        // GIVEN we are loading
        composeTestRule.setContent {
            WeatherDetailsDialog(
                forecast = null,
                spotName = "Test Spot",
                isLoading = true,
                onDismiss = {}
            )
        }

        // THEN the loading indicator should be visible (implied by absence of "unavailable")
        // And title should be visible
        composeTestRule.onNodeWithText("Weather - Test Spot").assertIsDisplayed()
        composeTestRule.onNodeWithText("Weather data unavailable").assertDoesNotExist()
    }

    @Test
    fun verifyDataDisplay() {
        // GIVEN we have data
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

        // THEN the dialog should show the title
        composeTestRule.onNodeWithText("Weather - Test Spot").assertIsDisplayed()
        // And NOT show "unavailable"
        composeTestRule.onNodeWithText("Weather data unavailable").assertDoesNotExist()
    }
    
    @Test
    fun verifyUnavailableState() {
        // GIVEN we have NO data and NOT loading
        composeTestRule.setContent {
            WeatherDetailsDialog(
                forecast = null,
                spotName = "Test Spot",
                isLoading = false,
                onDismiss = {}
            )
        }

        // THEN the unavailable message should be visible
        composeTestRule.onNodeWithText("Weather data unavailable").assertIsDisplayed()
    }
}
