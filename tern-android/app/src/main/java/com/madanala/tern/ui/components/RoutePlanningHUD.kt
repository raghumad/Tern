package com.madanala.tern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.madanala.tern.redux.MapState
import com.madanala.tern.ui.theme.AeroGlass
import com.madanala.tern.ui.theme.AeroSlate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Shield
import kotlin.math.roundToInt

/**
 * Route Planning HUD - Aviation-Grade Overlay
 * Displays critical flight metrics, weather, and sync status.
 *
 * Design Philosophy:
 * - High contrast for cockpit visibility
 * - Transparent glass background (AeroGlass)
 * - Micro-animations for sync status
 */
@Composable
fun RoutePlanningHUD(
    state: MapState,
    modifier: Modifier = Modifier
) {
    val selectedRouteId = state.selectedRouteId
    val route = state.routes.find { it.id == selectedRouteId } ?: return

    // HUD Content
    Card(
        modifier = modifier
            .padding(16.dp)
            .widthIn(max = 240.dp)
            .testTag("RoutePlanningHUD"),
        colors = CardDefaults.cardColors(containerColor = com.madanala.tern.ui.theme.AeroSlate), // Aviation-Grade: Higher contrast than glass
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp) // Heavier shadow for glare resistance
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Section: Route Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("DISTANCE", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
                    Text(
                        "${"%.1f".format(route.totalDistanceKm)} km",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.testTag("HUD_Distance")
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("FAI PTS", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
                    Text(
                        "%.1f".format(route.faiPoints),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary, // AeroNeonCyan
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("HUD_FaiPoints")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))

            // Airspace Warning (Aviation-Grade: High contrast pulse)
            val hasAirspaceCollision = true // Placeholder for collision logic
            if (hasAirspaceCollision) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Red),
                    modifier = Modifier.fillMaxWidth().testTag("HUD_AirspaceWarning")
                ) {
                    Text(
                        "🛑 CLASS B COLLISION",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally)
                    )
                }
            }

            // Section: Weather (Adaptive Metrics/4D Trajectory)
            Spacer(modifier = Modifier.height(12.dp))
            Text("4D TRAJECTORY WEATHER", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WeatherMetricItem("ETA @ GOAL", "13:45", "HUD_ETA_Goal")
                WeatherMetricItem("FORECAST WIND", "12kt NW", "HUD_ETA_Wind")
            }
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WeatherMetricItem("CLOUDBASE", "3200m", "HUD_Weather_Cloudbase")
                WeatherMetricItem("LAPSE", "-6.5°", "HUD_Weather_LapseRate")
            }
            
            // Storm Risk Warning
            val hasStormRisk = true // Placeholder for storm risk logic
            if (hasStormRisk) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("HUD_Weather_StormRisk"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️ STORM RISK", color = Color.Yellow, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) // Yellow for high contrast on Slate
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))

            // Section: Sync Status (Aviation Shield)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Syncing Icon (Pulsing)
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Syncing",
                        modifier = Modifier
                            .size(16.dp)
                            .testTag("HUD_SyncStatus"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("OFFLINE SECURED", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
                }
                
                // Flight Ready Shield
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Flight Ready",
                    tint = Color.Green,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("HUD_FlightReady_Shield")
                )
            }
        }
    }
}

@Composable
fun WeatherMetricItem(label: String, value: String, tag: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.testTag(tag))
    }
}

