package com.madanala.tern.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.RouteIOManager

@Composable
fun RouteDetailPanel(
    modifier: Modifier = Modifier,
    store: MapStore,
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    val state by store.state.collectAsState()
    val selectedRouteId = state.selectedRouteId
    val route = state.routes.find { it.id == selectedRouteId }
    val context = LocalContext.current
    var showQrDialog by remember { mutableStateOf(false) }

    if (showQrDialog && route != null) {
        Dialog(onDismissRequest = { showQrDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan to Import Route",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    val qrBitmap = remember(route) { RouteIOManager.generateQRCode(route) }
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Route QR Code",
                            modifier = Modifier.size(250.dp)
                        )
                    } else {
                        Text("Error generating QR Code")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(route.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    AnimatedVisibility(
        visible = isVisible && route != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        if (route != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = route.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${"%.2f".format(route.totalDistanceKm)} km • ${route.estimatedFlightTimeMinutes} min",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${route.routeType.name.replace("_", " ")} • ${"%.1f".format(route.faiPoints)} pts",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Row {
                            IconButton(onClick = { RouteIOManager.shareRouteFile(context, route) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share Route")
                            }
                            IconButton(onClick = { showQrDialog = true }) {
                                Icon(Icons.Default.QrCode, contentDescription = "Show QR Code")
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
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
                                .heightIn(max = 200.dp)
                                .testTag("WaypointList"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(route.waypoints) { index, waypoint ->
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
                                            Text(
                                                text = "${index + 1}. ${waypoint.label ?: "Waypoint"}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = buildString {
                                                    append("${"%.4f".format(waypoint.lat)}, ${"%.4f".format(waypoint.lon)}")
                                                    append(" • r${waypoint.radius?.toInt() ?: 400}m")
                                                    if (waypoint.alt != null) append(" • A${waypoint.alt.toInt()}m")
                                                    if (waypoint.openTime != null) append(" • O:${waypoint.openTime}")
                                                    if (waypoint.closeTime != null) append(" • C:${waypoint.closeTime}")
                                                    if (waypoint.type != com.madanala.tern.model.Waypoint.Type.TURNPOINT) {
                                                        val typeName = when (waypoint.type) {
                                                            com.madanala.tern.model.Waypoint.Type.LAUNCH -> "Launch"
                                                            com.madanala.tern.model.Waypoint.Type.TURNPOINT -> "Turnpoint"
                                                            com.madanala.tern.model.Waypoint.Type.SSS -> "Start Speed Section"
                                                            com.madanala.tern.model.Waypoint.Type.ESS -> "End Speed Section"
                                                            com.madanala.tern.model.Waypoint.Type.GOAL -> "Goal"
                                                            com.madanala.tern.model.Waypoint.Type.LANDING -> "Landing"
                                                        }
                                                        append(" • $typeName")
                                                    }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            // Show leg distance if available
                                            if (index > 0 && index - 1 < route.legDistances.size) {
                                                Text(
                                                    text = "+${"%.2f".format(route.legDistances[index - 1])} km",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.secondary
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
        }
    }
}
