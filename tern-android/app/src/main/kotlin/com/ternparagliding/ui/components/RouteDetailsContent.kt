package com.ternparagliding.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.model.Route
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.RouteIOManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The DETAILS tab of the route planner: share/QR actions and the per-waypoint
 * list with weather/ETA/hazard badges and reorder/delete controls.
 *
 * Split out of RouteDetailPanel.kt (Phase 0c god-file split). Same
 * ui.components package, so RouteDetailPanel's call site is unchanged.
 */
@Composable
fun RouteDetailsContent(
    route: Route,
    state: com.ternparagliding.redux.MapState,
    store: MapStore,
    onDismiss: () -> Unit,
    onShowQr: () -> Unit
) {
    val context = LocalContext.current
    Column {
        // Sub-Header (Only shown when expanded)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { RouteIOManager.shareRouteFile(context, route) }) {
                Icon(androidx.compose.material.icons.Icons.Default.Share, contentDescription = "Share Route")
            }
            IconButton(onClick = onShowQr) {
                Icon(androidx.compose.material.icons.Icons.Default.QrCode, contentDescription = "Show QR Code")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Waypoints (${route.waypoints.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (route.waypoints.isEmpty()) {
            Text(
                text = "Tap on the map to add waypoints",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 450.dp)
                    .testTag("WaypointList"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(route.waypoints) { index, waypoint ->
                    val weatherData = state.weatherState.waypointWeathers[waypoint.id]
                    val etaTimestamp = state.weatherState.waypointEtas[waypoint.id]

                    val formattedEta = remember(etaTimestamp) {
                        etaTimestamp?.let {
                            val instant = Instant.ofEpochMilli(it)
                            val formatter = DateTimeFormatter.ofPattern("HH:mm")
                                .withZone(ZoneId.systemDefault())
                            formatter.format(instant)
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (weatherData?.hasConvectiveDanger() == true || weatherData?.hasThunderstorm() == true) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiary,
                                            shape = MaterialTheme.shapes.extraSmall,
                                            modifier = Modifier.padding(end = 6.dp).testTag("AHV_BADGE")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = "Hazard",
                                                tint = Color.Black,
                                                modifier = Modifier.size(14.dp).padding(1.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${index + 1}. ${waypoint.label ?: "Waypoint"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (formattedEta != null) {
                                        Text(
                                            text = "ETA: $formattedEta",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                                if (weatherData?.hasThunderstorm() == true) {
                                    Text(
                                        text = "⚡ THUNDERSTORM RISK DETECTED",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append("${"%.4f".format(waypoint.lat)}, ${"%.4f".format(waypoint.lon)}")
                                        append(" • r${waypoint.radius?.toInt() ?: 400}m")
                                        if (waypoint.alt != null) append(" • A${waypoint.alt.toInt()}m")
                                        if (!waypoint.openTime.isNullOrBlank()) append(" • O:${waypoint.openTime}")
                                        if (!waypoint.closeTime.isNullOrBlank()) append(" • C:${waypoint.closeTime}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // Show Weather Summary in TEA Mode
                                if (weatherData != null && weatherData.current != null) {
                                    val wind = weatherData.current.wind
                                    val gustStr = if (wind.gust > 0.0) " (G ${wind.gust.roundToInt()})" else ""
                                    Text(
                                        text = "🌬️ ${wind.speed.roundToInt()} kt @ ${wind.direction.roundToInt()}°$gustStr",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Move Up
                                IconButton(
                                    onClick = {
                                        store.dispatch(MapAction.ReorderWaypoint(route.id, index, index - 1))
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Move Up",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Move Down
                                IconButton(
                                    onClick = {
                                        store.dispatch(MapAction.ReorderWaypoint(route.id, index, index + 1))
                                    },
                                    enabled = index < route.waypoints.size - 1,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Move Down",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        store.dispatch(MapAction.RemoveWaypoint(route.id, waypoint.id))
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Waypoint",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
