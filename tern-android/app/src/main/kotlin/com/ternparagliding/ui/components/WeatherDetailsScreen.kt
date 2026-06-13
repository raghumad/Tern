package com.ternparagliding.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.ternparagliding.ui.weather.FlyabilityCard
import com.ternparagliding.weather.assessOutlook
import com.ternparagliding.weather.assessQuality
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ternparagliding.utils.io.ForecastPeriod
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.WeatherForecast
import com.ternparagliding.units.Units
import com.ternparagliding.units.UnitPrefs
import androidx.compose.ui.semantics.semantics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WeatherDetailsScreen - Modern Android Aviation Weather Dialog
 * Moved from screens to components as it's a reusable dialog component
 * Equivalent to iOS weather detail view with aviation-specific formatting
 */
@Composable
fun WeatherDetailsDialog(
    forecast: WeatherForecast?,
    spotName: String = "Launch Site",
    targetArrivalTimestamp: Long? = null,
    siteContext: com.ternparagliding.weather.SiteContext? = null,
    units: UnitPrefs = UnitPrefs(),
    isLoading: Boolean = false,
    onDismiss: () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Weather - $spotName",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("WeatherDialogTitle")
                )
                Text(
                    text = "Aviation Forecast",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.testTag("WeatherLoadingIndicator")
                    )
                }
            } else if (forecast == null) {
                Text(
                    "Weather data unavailable",
                    modifier = Modifier.fillMaxWidth().testTag("WeatherUnavailableMessage"),
                    textAlign = TextAlign.Center
                )
            } else {
                WeatherContent(forecast, targetArrivalTimestamp, siteContext, units)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Weather Content - Scrollable weather details
 */
@Composable
private fun WeatherContent(
    forecast: WeatherForecast,
    targetTimestamp: Long?,
    siteContext: com.ternparagliding.weather.SiteContext? = null,
    units: UnitPrefs = UnitPrefs(),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Stale Data Warning
        if (forecast.isStale()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    "⚠️ Weather data is stale (>4h old)",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Flyability — the headline read: is it flyable here, now and soon?
        forecast.current?.let { nowWeather ->
            FlyabilityCard(
                outlook = assessOutlook(forecast, site = siteContext),
                quality = assessQuality(nowWeather, site = siteContext),
                site = siteContext,
                altitudeUnit = units.altitude,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        // Compute Skew-T data before branching so it's available in outer scope
        val interpolated: com.ternparagliding.utils.io.WeatherData? = if (targetTimestamp != null && forecast.hourly.isNotEmpty()) {
            val startPeriod = forecast.hourly.lastOrNull { it.startTime <= targetTimestamp }
                ?: forecast.hourly.first()
            val endPeriod = forecast.hourly.firstOrNull { it.startTime > targetTimestamp }
                ?: forecast.hourly.last()
            com.ternparagliding.utils.cache.WeatherCache.interpolateWeather(startPeriod, endPeriod, targetTimestamp)
        } else null

        // Current Conditions
        if (interpolated != null) {
            Text("Estimated Arrival Weather", style = MaterialTheme.typography.titleMedium)
            CurrentWeatherCard(interpolated, units)
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            forecast.current?.let { current ->
                CurrentWeatherCard(current, units)
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Skew-T Analysis — real computed values from current weather data
        val skewTData = interpolated ?: forecast.current
        SkewTPlaceholderCard(weatherData = skewTData, units = units)
        Spacer(modifier = Modifier.height(16.dp))

        // Hourly Forecast (next 24 hours, every 3 hours)
        forecast.hourly.take(8).chunked(1).forEach { hours -> // Show every hour for 8 hours
            hours.first().let { period ->
                HourlyWeatherCard(period, "Now +${(period.startTime - (System.currentTimeMillis() / 1000)) / 3600}h", units)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Daily Forecast
        Text(
            "5-Day Forecast",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        forecast.daily.take(5).forEach { period ->
            DailyWeatherCard(period, units)
        }
    }
}

/**
 * Current Weather Card - Aviation focused on wind, temperature
 */
@Composable
private fun CurrentWeatherCard(weather: WeatherData, units: UnitPrefs = UnitPrefs()) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Current Conditions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Temperature (prominent for aviation) — in the pilot's preferred unit
            Text(
                Units.temp(weather.temperature, units.temperature),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Wind (most important for paragliding). Surface (10 m) is the wind the
            // pilot feels at launch → primary. The 80 m "aloft" wind (what wind.speed
            // carries) is shown secondary; friction can veer the two apart.
            val surfaceSpeed = weather.windSpeed10m ?: weather.wind.speed
            val surfaceDir = weather.windDirection10m ?: weather.wind.direction
            WindDisplay(surfaceSpeed, surfaceDir, units)
            if (weather.windDirection10m != null) { // a genuine, distinct 80 m reading (Open-Meteo)
                Text(
                    "aloft ${Units.speed(weather.wind.speed, units.speed)} @ ${weather.wind.direction.toInt()}° · 80 m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Additional details in grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeatherDetail("Humidity", "${weather.humidity.toInt()}%", Modifier.testTag("WeatherHumidityValue"))
                WeatherDetail("Pressure", "${weather.pressure.toInt()} hPa", Modifier.testTag("WeatherPressureValue"))
                WeatherDetail("Visibility", Units.distance(weather.visibility, units.distance), Modifier.testTag("WeatherVisibilityValue"))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeatherDetail("Gust", Units.speed(weather.wind.gust, units.speed), Modifier.testTag("WeatherGustValue"))
                WeatherDetail("Cloud Cover", "${weather.cloudCover.toInt()}%", Modifier.testTag("WeatherCloudCoverValue"))
            }
        }
    }
}

/**
 * Hourly Weather Card
 */
@Composable
private fun HourlyWeatherCard(period: ForecastPeriod, label: String, units: UnitPrefs = UnitPrefs()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )

        // Temp (preferred unit; compact, so value-only with a degree)
        Text(
            "${Units.tempValue(period.weather.temperature, units.temperature).toInt()}°",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )

        // Wind (surface, for consistency with Current Conditions)
        Row(modifier = Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
            WindDisplaySmall(
                period.weather.windSpeed10m ?: period.weather.wind.speed,
                period.weather.windDirection10m ?: period.weather.wind.direction,
                period.weather.wind.gust, units,
            )
        }
    }
}

/**
 * Daily Weather Card
 */
@Composable
private fun DailyWeatherCard(period: ForecastPeriod, units: UnitPrefs = UnitPrefs()) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Date
            val dateFormat = SimpleDateFormat("EEE", Locale.getDefault())
            val date = Date(period.startTime * 1000)
            Text(
                dateFormat.format(date),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )

            // Forecast
            Text(
                period.shortForecast,
                modifier = Modifier.weight(2f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            // Temp range
            Text(
                "${Units.tempValue(period.weather.temperature, units.temperature).toInt()}°",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium
            )

            // Wind (surface)
            Row(modifier = Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(8.dp))
                WindDisplaySmall(
                    period.weather.windSpeed10m ?: period.weather.wind.speed,
                    period.weather.windDirection10m ?: period.weather.wind.direction,
                    period.weather.wind.gust, units,
                )
            }
        }
    }
}

/**
 * Wind Display - Aviation standard with arrow rotated to wind direction
 */
@Composable
private fun WindDisplay(speed: Double, direction: Double, units: UnitPrefs = UnitPrefs()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Speed (preferred unit)
        Text(
            Units.speed(speed, units.speed),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Direction in text (aviation format)
        Text(
            "${direction.toInt()}°",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Small Wind Display for condensed UI
 */
@Composable
private fun WindDisplaySmall(speed: Double, direction: Double, gust: Double = 0.0, units: UnitPrefs = UnitPrefs()) {
    // Gust-significance test stays in canonical knots; only the display converts.
    val gustText = if (gust > speed + 5) " G${Units.speedValue(gust, units.speed).toInt()}" else ""
    Text(
        "${Units.speedValue(speed, units.speed).toInt()}$gustText ${Units.speedSymbol(units.speed)} @ ${direction.toInt()}°",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium
    )
}

/**
 * Weather Detail Component
 */
@Composable
private fun WeatherDetail(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.semantics(mergeDescendants = true) {}
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Skew-T Analysis card — displays real computed Cloud Base and Inversion Layer.
 * Cloud Base uses the Lifted Condensation Level (LCL) approximation.
 * Inversion Layer is detected from Open-Meteo 850hPa vs 925hPa temperature data.
 */
@Composable
private fun SkewTPlaceholderCard(weatherData: com.ternparagliding.utils.io.WeatherData? = null, units: UnitPrefs = UnitPrefs()) {
    val cloudBaseText = weatherData?.let { Units.altitude(computeCloudBaseMeters(it), units.altitude) } ?: "—"
    val inversionText = weatherData?.let { detectInversionLayer(it) } ?: "—"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Skew-T Analysis",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (weatherData != null)
                        "Surface T: ${Units.temp(weatherData.temperature, units.temperature)} · RH: ${weatherData.humidity.toInt()}%"
                    else
                        "No weather data",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeatherDetail(
                    label = "Cloud Base",
                    value = cloudBaseText,
                    modifier = Modifier.testTag("SkewTCloudBase")
                )
                WeatherDetail(
                    label = "Inversion",
                    value = inversionText,
                    modifier = Modifier.testTag("SkewTInversionLayer")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeatherDetail(
                    label = "Lapse Rate",
                    value = weatherData?.let { computeLapseRate(it) } ?: "—",
                    modifier = Modifier.testTag("WeatherLapseRate")
                )
                WeatherDetail(
                    label = "Storm Risk",
                    value = weatherData?.let { evaluateStormRisk(it) } ?: "—",
                    modifier = Modifier.testTag("WeatherStormRisk")
                )
            }
        }
    }
}

/**
 * Estimated cloud base in **metres** AGL via the LCL approximation
 * (Td = T − (100 − RH)/5; H = 125·(T − Td)). The caller formats it to the pilot's
 * altitude unit — same canonical-metres basis as Flyability.cloudBaseMeters.
 */
private fun computeCloudBaseMeters(weather: com.ternparagliding.utils.io.WeatherData): Double {
    val t = weather.temperature
    val rh = weather.humidity.coerceIn(1.0, 100.0)
    val dewPoint = t - ((100.0 - rh) / 5.0)
    return 125.0 * (t - dewPoint).coerceAtLeast(0.0)
}

/**
 * Detects a temperature inversion by comparing 850hPa and 925hPa level temps.
 * An inversion exists when the temperature at 850hPa (higher altitude) exceeds
 * the temperature at 925hPa (lower altitude), indicating a warmer layer aloft.
 * Returns null-safe "No data" when pressure-level data is unavailable.
 */
private fun detectInversionLayer(weather: com.ternparagliding.utils.io.WeatherData): String {
    val t850 = weather.temp850hPa
    val t925 = weather.temp925hPa
    return when {
        t850 == null || t925 == null -> "No data"
        t850 > t925 -> "Inversion" // Shortened for UX
        else -> "Normal"
    }
}

/**
 * Computes Lapse Rate in °C per 1000m based on surface vs 850hPa level (≈1500m).
 * Standard lapse rate is 6.5°C/km. >8.0 indicates high instability.
 */
private fun computeLapseRate(weather: WeatherData): String {
    val tSfc = weather.temperature
    val t850 = weather.temp850hPa ?: return "No data"
    
    // Lapse Rate = (T_lower - T_upper) / (Alt_upper - Alt_lower)
    // 850hPa is approx 1500m. Surface is 0m.
    val lapseRate = (tSfc - t850) / 1.5
    return "%.1f°/km".format(lapseRate)
}

/**
 * Evaluates storm risk based on lapse rate instability and moisture.
 * Logic: High Lapse Rate (>8) + High Humidity (>70%) = High Risk.
 */
private fun evaluateStormRisk(weather: WeatherData): String {
    val tSfc = weather.temperature
    val t850 = weather.temp850hPa ?: return "No data"
    val lapseRate = (tSfc - t850) / 1.5
    
    return when {
        lapseRate > 8.5 && weather.humidity > 75 -> "High (Thunder)"
        lapseRate > 7.0 && weather.humidity > 60 -> "Moderate"
        else -> "Low"
    }
}