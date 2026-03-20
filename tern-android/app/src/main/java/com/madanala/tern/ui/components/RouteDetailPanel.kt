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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
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
import com.madanala.tern.redux.WeatherActions
import com.madanala.tern.utils.RouteIOManager
import androidx.compose.runtime.LaunchedEffect
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class PlanningTab {
    DETAILS, SEARCH, LIBRARY
}

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
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var activeTab by remember(isVisible) { mutableStateOf(PlanningTab.DETAILS) }
    var showQrDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedRouteId) {
        if (selectedRouteId != null) {
            store.dispatch(WeatherActions.FetchWeatherForRoute(selectedRouteId))
        }
    }

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
        visible = isVisible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("RouteDetailPanel"),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f) // Aviation-Grade: Use theme surface with glass alpha
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Tab System
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlanningTabButton("DETAILS", activeTab == PlanningTab.DETAILS) { 
                        activeTab = PlanningTab.DETAILS 
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    PlanningTabButton("SEARCH", activeTab == PlanningTab.SEARCH) { 
                        activeTab = PlanningTab.SEARCH 
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    PlanningTabButton("LIBRARY", activeTab == PlanningTab.LIBRARY) { 
                        activeTab = PlanningTab.LIBRARY 
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (activeTab) {
                    PlanningTab.DETAILS -> {
                        if (route != null) {
                            RouteDetailsContent(route, state, store, onDismiss, onShowQr = { showQrDialog = true })
                        } else {
                            Text("No route selected. Tap on map to start planning.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    PlanningTab.SEARCH -> {
                        RouteSearchContent(store)
                    }
                    PlanningTab.LIBRARY -> {
                        RouteLibraryContent(store)
                    }
                }
            }
        }
    }
}

@Composable
fun PlanningTabButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier
            .heightIn(min = 48.dp), // Aviation-Grade: Min 48dp target
        shape = MaterialTheme.shapes.small
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RouteSearchContent(store: MapStore) {
    var searchQuery by remember { mutableStateOf("") }
    
    // Pilot-Centric Search Mock
    val results = listOf(
        "Lookout Mountain Launch" to org.osmdroid.util.GeoPoint(39.7429, -105.2393),
        "Golden colorado" to org.osmdroid.util.GeoPoint(39.7526, -105.2201), 
        "Boulder Flatirons" to org.osmdroid.util.GeoPoint(39.9880, -105.2930)
    )

    Column {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search PG Spots (e.g. Golden)") },
            modifier = Modifier.fillMaxWidth().testTag("RouteSearchField"),
            singleLine = true,
            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = null) },
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            itemsIndexed(results.filter { it.first.contains(searchQuery, ignoreCase = true) }) { _, (name, point) ->
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            store.dispatch(MapAction.LongPressMap(point, label = name)) 
                        }
                        .padding(vertical = 12.dp)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(androidx.compose.material.icons.Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("${"%.3f".format(point.latitude)}, ${"%.3f".format(point.longitude)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun RouteLibraryContent(store: MapStore) {
    // Mock Library Routes
    val libraryRoutes = listOf(
        Route(id = "lib_1", name = "Lookout Classic XC", waypoints = emptyList()),
        Route(id = "lib_2", name = "Boulder Canyon Run", waypoints = emptyList()),
        Route(id = "lib_3", name = "Golden Record Attempt", waypoints = emptyList())
    )

    Column {
        Text("SAVED ROUTES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            itemsIndexed(libraryRoutes) { _, route ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            store.dispatch(MapAction.AddRoute(route))
                            store.dispatch(MapAction.SelectRoute(route.id))
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(androidx.compose.material.icons.Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(route.name, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun RouteDetailsContent(
    route: Route,
    state: com.madanala.tern.redux.MapState,
    store: MapStore,
    onDismiss: () -> Unit,
    onShowQr: () -> Unit
) {
    val context = LocalContext.current
    Column {
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
                    Icon(androidx.compose.material.icons.Icons.Default.Share, contentDescription = "Share Route")
                }
                IconButton(onClick = onShowQr) {
                    Icon(androidx.compose.material.icons.Icons.Default.QrCode, contentDescription = "Show QR Code")
                }
                IconButton(onClick = onDismiss) {
                    Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = "Close")
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
                                    Text(
                                        text = "${index + 1}. ${waypoint.label ?: "Waypoint"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (formattedEta != null) {
                                        Text(
                                            text = "ETA: $formattedEta",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
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
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }

                                // Show Weather Summary
                                if (weatherData != null && weatherData.current != null) {
                                    val wind = weatherData.current.wind
                                    Text(
                                        text = "🌬️ ${wind.speed.roundToInt()} kt @ ${wind.direction.roundToInt()}°${if (wind.gust > 0) " (G ${wind.gust.roundToInt()})" else ""}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontWeight = FontWeight.Bold
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
