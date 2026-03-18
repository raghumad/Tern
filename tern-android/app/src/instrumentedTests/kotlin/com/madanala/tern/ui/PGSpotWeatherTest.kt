package com.madanala.tern.ui

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.ui.components.WeatherDetailsDialog
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.WeatherData
import com.madanala.tern.utils.WeatherForecast
import com.madanala.tern.utils.WindData
import org.junit.Test
import org.junit.runner.RunWith

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@RunWith(AndroidJUnit4::class)
class PGSpotWeatherTest : BddTest() {

    @Test
    fun verifyLoadingAndUnavailableStates() {
        var isLoading by mutableStateOf(true)
        var currentForecast by mutableStateOf<WeatherForecast?>(null)

        scenario("Weather Loading and Error Handling") {
            story("As a pilot, I need to know the state of the weather fetch so I don't fly on incomplete or missing data.") {
                given("the weather system is currently fetching data") {
                    setThemeContent {
                        WeatherDetailsDialog(
                            forecast = currentForecast,
                            spotName = "Test Spot",
                            isLoading = isLoading,
                            onDismiss = {}
                        )
                    }
                }

                then("I should see the loading indicator", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("WeatherLoadingIndicator").assertIsDisplayed()
                }

                `when`("the data fetch fails and returns null") {
                    isLoading = false
                    currentForecast = null
                }

                then("I should see a clear 'unavailable' message preventing me from making blind decisions", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("WeatherUnavailableMessage").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("WeatherLoadingIndicator").assertDoesNotExist()
                }
            }
        }
    }

    @Test
    fun testPilotDecisionSupportForUnstableAtmosphere() {
        scenario("The High-Altitude Decision: Assessing Thunderstorm Risk") {
            story("As a pilot planning a cross-country flight, I need to identify atmospheric instability and storm risk to ensure a safe landing before conditions deteriorate.") {
                given("an atmosphere with a high lapse rate (10°C/km) and high humidity (80%)") {
                    val unstableForecast = WeatherForecast(
                        current = WeatherData(
                            wind = WindData(10.0, 270.0, 15.0),
                            temperature = 25.0,
                            humidity = 80.0,
                            visibility = 15.0,
                            pressure = 1013.25,
                            cloudCover = 60.0,
                            timestamp = System.currentTimeMillis() / 1000,
                            temp850hPa = 10.0 // (25-10)/1.5 = 10.0°/km lapse rate (Very Unstable)
                        ),
                        hourly = emptyList(),
                        daily = emptyList()
                    )

                    setThemeContent {
                        WeatherDetailsDialog(
                            forecast = unstableForecast,
                            spotName = "Mount Saint Pierre",
                            isLoading = false,
                            onDismiss = {}
                        )
                    }
                }

                then("the 'Lapse Rate' should display '10.0°/km' indicating high instability") {
                    composeTestRule.onNodeWithTag("WeatherLapseRate")
                        .assertTextContains("10.0°/km")
                }

                and("the 'Storm Risk' should warn me with 'High (Thunder)' so I can plan a conservative flight path", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("WeatherStormRisk")
                        .assertTextContains("High (Thunder)")
                }
            }
        }
    }

    @Test
    fun testPilotDecisionSupportForCappedThermals() {
        scenario("The Inversion Trap: Identifying Capped Thermal Activity") {
            story("As a pilot looking for a long XC day, I need to spot temperature inversions that might trap me below the ridge line.") {
                given("an atmosphere where it's warmer aloft (10°C at 850hPa) than at the surface (8°C)") {
                    val inversionForecast = WeatherForecast(
                        current = WeatherData(
                            wind = WindData(5.0, 180.0, 8.0),
                            temperature = 8.0,
                            humidity = 40.0,
                            visibility = 20.0,
                            pressure = 1020.0,
                            cloudCover = 10.0,
                            timestamp = System.currentTimeMillis() / 1000,
                            temp850hPa = 10.0, // Warmer aloft -> Inversion
                            temp925hPa = 7.0
                        ),
                        hourly = emptyList(),
                        daily = emptyList()
                    )

                    setThemeContent {
                        WeatherDetailsDialog(
                            forecast = inversionForecast,
                            spotName = "Dolomites North",
                            isLoading = false,
                            onDismiss = {}
                        )
                    }
                }

                then("the app should report 'Inversion' alerting me to likely capped thermals") {
                    composeTestRule.onNodeWithTag("SkewTInversionLayer")
                        .assertTextContains("Inversion")
                }
                
                and("the 'Lapse Rate' should be negative or neutral", takeScreenshot = true) {
                    // (8 - 10) / 1.5 = -1.3
                    composeTestRule.onNodeWithTag("WeatherLapseRate")
                        .assertTextContains("-1.3°/km")
                }
            }
        }
    }

    @Test
    fun testCloudBaseCalculationFidelity() {
        scenario("Thermal Ceiling Verification") {
            story("As a pilot, I need an accurate estimate of the cloud base to know how much 'working room' I have between the terrain and the airspace.") {
                given("a warm, dry day (25°C, 40% Humidity)") {
                    // LCL approximation: Td = 25 - ((100-40)/5) = 13°C; CloudBase = 125 * 12 = 1500m = 4921 ft
                    val forecast = WeatherForecast(
                        current = WeatherData(
                            wind = WindData(0.0, 0.0, 0.0),
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

                    setThemeContent {
                        WeatherDetailsDialog(
                            forecast = forecast,
                            spotName = "Alps Center",
                            isLoading = false,
                            onDismiss = {}
                        )
                    }
                }

                then("the 'Cloud Base' should show '4921 ft' allowing me to confirm airspace clearance", takeScreenshot = true) {
                    composeTestRule.onNodeWithTag("SkewTCloudBase")
                        .assertTextContains("4921 ft")
                }
            }
        }
    }
}
