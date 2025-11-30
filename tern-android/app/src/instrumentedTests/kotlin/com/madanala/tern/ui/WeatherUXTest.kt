package com.madanala.tern.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.madanala.tern.ui.components.WeatherDetailsDialog
import com.madanala.tern.utils.ForecastPeriod
import com.madanala.tern.utils.ReportGenerator
import com.madanala.tern.utils.WeatherData
import com.madanala.tern.utils.WeatherForecast
import com.madanala.tern.utils.WindData
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

class WeatherUXTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val testNameRule = TestName()

    @Before
    fun setup() {
        try {
            Runtime.getRuntime().exec("logcat -c")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @After
    fun tearDown() {
        ReportGenerator.generateFinalReport(testNameRule.methodName)
    }

    @Test
    fun testWeatherDetailsShowsGustAndCloudCover() {
        scenario("Verify Weather Details Dialog shows Gust and Cloud Cover") {
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
                composeTestRule.setContent {
                    WeatherDetailsDialog(forecast = forecast, onDismiss = {})
                }
            }

            // THEN the Gust and Cloud Cover should be displayed
            then("The Gust (25 kt) and Cloud Cover (75%) should be visible") {
                composeTestRule.onNodeWithText("25 kt").assertIsDisplayed()
                composeTestRule.onNodeWithText("Gust").assertIsDisplayed()
                composeTestRule.onNodeWithText("75%").assertIsDisplayed()
                composeTestRule.onNodeWithText("Cloud Cover").assertIsDisplayed()
            }
        }
    }

    @Test
    fun testStaleDataWarning() {
        scenario("Verify Stale Data Warning appears for old data") {
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
                composeTestRule.setContent {
                    WeatherDetailsDialog(forecast = forecast, onDismiss = {})
                }
            }

            // THEN the Stale Data Warning should be displayed
            then("The 'Weather data is stale' warning should be visible") {
                composeTestRule.onNodeWithText("⚠️ Weather data is stale (>4h old)").assertIsDisplayed()
            }
        }
    }

    // BDD Helpers
    private fun scenario(name: String, block: () -> Unit) {
        ReportGenerator.logStep("SCENARIO", name)
        try {
            block()
            val screenshot = ReportGenerator.captureScreenshot("success_${name.take(20).replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario Passed", "PASS", screenshot)
        } catch (e: Throwable) {
            val screenshot = ReportGenerator.captureScreenshot("failure_${name.take(20).replace(" ", "_")}")
            ReportGenerator.logStep("RESULT", "Scenario Failed: ${e.message}", "FAIL", screenshot)
            throw e
        } finally {
            val logCatOutput = ReportGenerator.captureLogCat()
            ReportGenerator.finishScenario(name, logCatOutput)
        }
    }

    private fun given(description: String, block: () -> Unit) = step("GIVEN", description, block)
    private fun `when`(description: String, block: () -> Unit) = step("WHEN", description, block)
    private fun then(description: String, block: () -> Unit) = step("THEN", description, block)

    private fun step(type: String, description: String, block: () -> Unit) {
        try {
            block()
            val screenshot = ReportGenerator.captureScreenshot("step_${type}_${description.take(20).replace(" ", "_")}")
            ReportGenerator.logStep(type, description, "PASS", screenshot)
        } catch (e: Throwable) {
            val screenshot = ReportGenerator.captureScreenshot("failure_${type}_${description.take(20).replace(" ", "_")}")
            ReportGenerator.logStep(type, description, "FAIL", screenshot)
            throw e
        }
    }
}
