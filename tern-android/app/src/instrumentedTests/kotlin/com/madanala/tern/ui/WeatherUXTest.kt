package com.madanala.tern.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.madanala.tern.ui.components.WeatherDetailsDialog
import com.madanala.tern.utils.ForecastPeriod
import com.madanala.tern.utils.WeatherData
import com.madanala.tern.utils.WeatherForecast
import com.madanala.tern.utils.WindData
import org.junit.Rule
import org.junit.Test

class WeatherUXTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testWeatherDetailsShowsGustAndCloudCover() {
        // GIVEN a forecast with Gust and Cloud Cover data
        val now = System.currentTimeMillis() / 1000
        val forecast = WeatherForecast(
            current = WeatherData(
                wind = WindData(speed = 10.0, direction = 180.0, gust = 25.0),
                temperature = 20.0,
                humidity = 50.0,
                visibility = 10.0,
                pressure = 1013.0,
                cloudCover = 75.0,
                timestamp = now
            ),
            daily = emptyList(),
            hourly = emptyList()
        )

        // WHEN the Weather Details Dialog is shown
        composeTestRule.setContent {
            WeatherDetailsDialog(forecast = forecast, onDismiss = {})
        }

        // THEN the Gust and Cloud Cover should be displayed
        composeTestRule.onNodeWithText("25 kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gust").assertIsDisplayed()
        composeTestRule.onNodeWithText("75%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cloud Cover").assertIsDisplayed()
    }

    @Test
    fun testStaleDataWarning() {
        // GIVEN a stale forecast (older than 4 hours)
        val now = System.currentTimeMillis() / 1000
        val fiveHoursAgo = now - (5 * 3600)
        
        // Create a forecast where the first hourly period is 5 hours ago
        val staleHourly = listOf(
            ForecastPeriod(
                startTime = fiveHoursAgo,
                endTime = fiveHoursAgo + 3600,
                weather = WeatherData(
                    wind = WindData(10.0, 180.0, 0.0),
                    temperature = 20.0,
                    humidity = 50.0,
                    visibility = 10.0,
                    pressure = 1013.0,
                    cloudCover = 0.0,
                    timestamp = fiveHoursAgo
                ),
                shortForecast = "Old"
            )
        )

        val forecast = WeatherForecast(
            current = null,
            daily = emptyList(),
            hourly = staleHourly
        )

        // WHEN the Weather Details Dialog is shown
        composeTestRule.setContent {
            WeatherDetailsDialog(forecast = forecast, onDismiss = {})
        }

        // THEN the Stale Data Warning should be displayed
        composeTestRule.onNodeWithText("⚠️ Weather data is stale (>4h old)").assertIsDisplayed()
    }
}
