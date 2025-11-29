package com.madanala.tern.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.madanala.tern.utils.ForecastPeriod
import com.madanala.tern.utils.WeatherData
import com.madanala.tern.utils.WeatherForecast
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
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Aviation Forecast",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            if (forecast == null) {
                Text(
                    "Weather data unavailable",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            } else {
                WeatherContent(forecast)
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
private fun WeatherContent(forecast: WeatherForecast) {
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

        // Current Conditions
        forecast.current?.let { current ->
            CurrentWeatherCard(current)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Hourly Forecast (next 24 hours, every 3 hours)
        forecast.hourly.take(8).chunked(1).forEach { hours -> // Show every hour for 8 hours
            hours.first().let { period ->
                HourlyWeatherCard(period, "Now +${(period.startTime - (System.currentTimeMillis() / 1000)) / 3600}h")
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
            DailyWeatherCard(period)
        }
    }
}

/**
 * Current Weather Card - Aviation focused on wind, temperature
 */
@Composable
private fun CurrentWeatherCard(weather: WeatherData) {
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

            // Temperature (prominent for aviation)
            Text(
                "${weather.temperature.toInt()}°C",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Wind (most important for paragliding)
            WindDisplay(weather.wind.speed, weather.wind.direction)

            Spacer(modifier = Modifier.height(8.dp))

            // Additional details in grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeatherDetail("Humidity", "${weather.humidity.toInt()}%")
                WeatherDetail("Pressure", "${weather.pressure.toInt()} hPa")
                WeatherDetail("Visibility", "${weather.visibility.toInt()} km")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeatherDetail("Gust", "${weather.wind.gust.toInt()} kt")
                WeatherDetail("Cloud Cover", "${weather.cloudCover.toInt()}%")
            }
        }
    }
}

/**
 * Hourly Weather Card
 */
@Composable
private fun HourlyWeatherCard(period: ForecastPeriod, label: String) {
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

        // Temp
        Text(
            "${period.weather.temperature.toInt()}°",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )

        // Wind
        Row(modifier = Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
            WindDisplaySmall(period.weather.wind.speed, period.weather.wind.direction, period.weather.wind.gust)
        }
    }
}

/**
 * Daily Weather Card
 */
@Composable
private fun DailyWeatherCard(period: ForecastPeriod) {
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
                "${period.weather.temperature.toInt()}°",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium
            )

            // Wind
            Row(modifier = Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(8.dp))
                WindDisplaySmall(period.weather.wind.speed, period.weather.wind.direction, period.weather.wind.gust)
            }
        }
    }
}

/**
 * Wind Display - Aviation standard with arrow rotated to wind direction
 */
@Composable
private fun WindDisplay(speed: Double, direction: Double) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Speed
        Text(
            "${speed.toInt()} kt",
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
private fun WindDisplaySmall(speed: Double, direction: Double, gust: Double = 0.0) {
    val gustText = if (gust > speed + 5) " G${gust.toInt()}" else ""
    Text(
        "${speed.toInt()}$gustText kt @ ${direction.toInt()}°",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium
    )
}

/**
 * Weather Detail Component
 */
@Composable
private fun WeatherDetail(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
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