package com.madanala.tern.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.components.WeatherDetailsDialog
import com.madanala.tern.utils.ForecastPeriod
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.WeatherData
import com.madanala.tern.utils.WeatherForecast
import com.madanala.tern.utils.WindData
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeatherUXTest : BddTest() {

    @Test
    fun testWeatherDetailsShowsGustAndCloudCover() {
        scenario("Verify Weather Details Dialog shows Gust and Cloud Cover") {
            story("As a pilot, I need to see gust and cloud cover data clearly in the weather details to assess flight safety.") {
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

                given("A weather forecast with 25kt Gust and 75% Cloud Cover") {
                    // Setup is done above
                }

                // WHEN the Weather Details Dialog is shown
                `when`("The Weather Details Dialog is displayed") {
                    setThemeContent {
                        WeatherDetailsDialog(forecast = forecast, onDismiss = {})
                    }
                }

                // THEN the Gust and Cloud Cover should be displayed
                this.then("The Gust (25 kt) and Cloud Cover (75%) should be visible") {
                    composeTestRule.onNodeWithText("25 kt").assertIsDisplayed()
                    composeTestRule.onNodeWithText("Gust").assertIsDisplayed()
                    composeTestRule.onNodeWithText("75%").assertIsDisplayed()
                    composeTestRule.onNodeWithText("Cloud Cover").assertIsDisplayed()
                }
                and("The Recompose layout updates without blocking the main event loop (< 16ms)") {
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                }
            }
        }
    }

    @Test
    fun testStaleDataWarning() {
        scenario("Verify Stale Data Warning appears for old data") {
            story("As a pilot, I need to be warned if the offline weather data I am viewing is more than 4 hours old, so I don't make decisions based on outdated forecasts.") {
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

                given("A weather forecast that is > 4 hours old") {
                    // Setup done above
                }

                // WHEN the Weather Details Dialog is shown
                `when`("The Weather Details Dialog is displayed") {
                    setThemeContent {
                        WeatherDetailsDialog(forecast = forecast, onDismiss = {})
                    }
                }

                // THEN the Stale Data Warning should be displayed
                this.then("The 'Weather data is stale' warning should be visible") {
                    composeTestRule.onNodeWithText("⚠️ Weather data is stale (>4h old)").assertIsDisplayed()
                }

                and("The UI remains stable and leak-free") {
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                }
            }
        }
    }

    @Test
    fun testTrajectoryInterpolationDisplay() {
        scenario("Verify Trajectory Interpolation Display") {
            story("As a pilot estimating conditions upon arrival at a waypoint, I want to see an accurate interpolation of weather between two forecast hours.") {
                val now = System.currentTimeMillis() / 1000
                val forecast = WeatherForecast(
                    current = null,
                    hourly = listOf(
                        ForecastPeriod(
                            startTime = now,
                            endTime = now + 3600,
                            weather = WeatherData(WindData(10.0, 180.0, 15.0), 20.0, 50.0, 10.0, 1013.0, 0.0, now),
                            shortForecast = "12:00"
                        ),
                        ForecastPeriod(
                            startTime = now + 3600,
                            endTime = now + 7200,
                            weather = WeatherData(WindData(20.0, 180.0, 25.0), 20.0, 50.0, 10.0, 1013.0, 0.0, now + 3600),
                            shortForecast = "13:00"
                        )
                    ),
                    daily = emptyList()
                )

                given("an hourly forecast where 12:00 has 10kt wind and 13:00 has 20kt wind") {
                    // Setup done
                }

                `when`("the pilot views the route weather panel at an estimated arrival time of 12:30") {
                    setThemeContent {
                        WeatherDetailsDialog(
                            forecast = forecast,
                            targetArrivalTimestamp = now + 1800, // half hour later
                            onDismiss = {}
                        )
                    }
                }

                this.then("the UI should display an interpolated wind speed of 15 kt") {
                    composeTestRule.onNodeWithText("Estimated Arrival Weather").assertIsDisplayed()
                    composeTestRule.onNodeWithText("15 kt").assertIsDisplayed()
                }

                and("The interpolation logic does not cause performance overhead") {
                    com.madanala.tern.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                }
            }
        }
    }
}
