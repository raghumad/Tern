package com.madanala.tern.ui.weather

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.madanala.tern.model.RiskLevel
import com.madanala.tern.model.RouteWeather
import com.madanala.tern.model.TrajectoryAnalyzer
import com.madanala.tern.model.WaypointWeather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RouteWeatherPanel(
    routeWeather: RouteWeather,
    modifier: Modifier = Modifier
) {
    var launchTimeOffsetHours by remember { mutableFloatStateOf(0f) }
    var isWaypointListExpanded by remember { mutableStateOf(false) }

    // Derived state for the updated trajectory based on slider
    val currentTrajectory by remember(routeWeather, launchTimeOffsetHours) {
        derivedStateOf {
            val offsetMillis = (launchTimeOffsetHours * 3600 * 1000).toLong()
            
            // Recalculate based on offset
            // We assume the base trajectory has the correct relative timing
            // We just shift the "estimatedArrival" and re-evaluate risk/wind
            val updatedWaypoints = routeWeather.trajectoryForecast?.waypoints?.map { wp ->
                val newArrival = wp.estimatedArrival + offsetMillis
                
                // Find nearest wind/cape point for this new time
                // This is a simplification. In real app we'd interpolate or find exact match.
                // For now, we assume the 'forecast' object contains the full series and we pick the closest point.
                // But WaypointWeather.forecast might be just the slice. 
                // Let's assume for this UI demo that we just use the existing forecast 
                // but ideally we would re-query the time series.
                
                wp.copy(estimatedArrival = newArrival)
            } ?: emptyList()

            // Re-run analysis (simplified)
            TrajectoryAnalyzer.analyzeTrajectory(updatedWaypoints)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Route Forecast",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        val startTime = routeWeather.trajectoryForecast?.waypoints?.firstOrNull()?.estimatedArrival ?: System.currentTimeMillis()
        val endTime = routeWeather.trajectoryForecast?.waypoints?.lastOrNull()?.estimatedArrival ?: System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val startStr = timeFormat.format(Date(startTime + (launchTimeOffsetHours * 3600 * 1000).toLong()))
        val endStr = timeFormat.format(Date(endTime + (launchTimeOffsetHours * 3600 * 1000).toLong()))
        
        Text(
            text = "($startStr - $endStr)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Slider
        Text(text = "Launch Time: +${launchTimeOffsetHours.roundToInt()} hr")
        Slider(
            value = launchTimeOffsetHours,
            onValueChange = { launchTimeOffsetHours = it },
            valueRange = 0f..3f,
            steps = 2
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Gauges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Wind
            val avgWind = currentTrajectory.avgHeadwind
            WindGauge(
                value = avgWind,
                label = "Avg Wind",
                unit = "km/h",
                directionDegrees = 315.0, // Mock NW for now, needs real data
                gradientColors = listOf(Color.Blue, Color.Cyan)
            )

            // Gust (Mock data for now as it's not in RouteWeather yet)
            WindGauge(
                value = avgWind + 10,
                label = "Gust",
                unit = "km/h",
                gradientColors = listOf(Color.Green, Color.Yellow, Color.Red)
            )

            // Risk
            val riskVal = when(currentTrajectory.maxRisk.riskLevel) {
                RiskLevel.LOW -> 10.0
                RiskLevel.MODERATE -> 50.0
                RiskLevel.HIGH -> 80.0
                RiskLevel.EXTREME -> 100.0
            }
            WindGauge(
                value = riskVal,
                maxValue = 100.0,
                label = "Risk",
                unit = "CAPE",
                gradientColors = listOf(Color.Green, Color.Red)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Stability Metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricItem("Cloud Base", "2200m", Color.Green)
            MetricItem("Inversion", "None", Color.Gray)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Waypoint List
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isWaypointListExpanded = !isWaypointListExpanded },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp).animateContentSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Waypoint Breakdown",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Icon(
                        imageVector = if (isWaypointListExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand"
                    )
                }

                if (isWaypointListExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    currentTrajectory.waypoints.forEach { wp ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val timeStr = timeFormat.format(Date(wp.estimatedArrival))
                            Text(text = "${wp.waypointId} ($timeStr)", style = MaterialTheme.typography.bodyMedium)
                            
                            // Mock wind for waypoint
                            Text(text = "NW 10 km/h", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, color: Color) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = FontWeight.Bold)
    }
}
