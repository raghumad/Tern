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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0F172A).copy(alpha = 0.85f) // AeroSlate High-Contrast
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Determine Aggregated Route Health for SSA Mode
                val hasStormRisk = remember(route, state.weatherState) {
                    route?.waypoints?.any { wp -> 
                        state.weatherState.waypointWeathers[wp.id]?.let { 
                            it.hasConvectiveDanger() || it.hasThunderstorm() 
                        } ?: false
                    } ?: false
                }

                // SSA/TEA Header: Always visible, answer the "Safe to Fly?" question instantly
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(if (state.isRoutePanelExpanded) "TEA_Header" else "SSA_Header"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (route != null) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = route.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                if (hasStormRisk) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = Color(0xFFFFBF00),
                                        shape = MaterialTheme.shapes.extraSmall
                                    ) {
                                        Text(
                                            "! STORM RISK",
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Black,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${"%.1f".format(route.totalDistanceKm)} km | ${route.estimatedFlightTimeMinutes} min",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        Text(
                            "Route Planner",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (route != null) {
                            IconButton(onClick = { store.dispatch(MapAction.ToggleRoutePanelExpanded) }) {
                                Icon(
                                    imageVector = if (state.isRoutePanelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = if (state.isRoutePanelExpanded) "Collapse" else "Expand",
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }
                
                // 🎯 AHV (Atmospheric Hazard Vectorization) Alert Section
                if (hasStormRisk) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .testTag("AHV_BANNER"),
                        color = Color(0xFFFFBF00).copy(alpha = 0.9f), // Safety Amber
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "STORM RISK DETECTED ON TRAJECTORY",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.Black,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                if (state.isRoutePanelExpanded || route == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))

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
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            color = Color(0xFFFFBF00),
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
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (formattedEta != null) {
                                        Text(
                                            text = "ETA: $formattedEta",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF38BDF8), // Sky Blue high-contrast
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                                if (weatherData?.hasThunderstorm() == true) {
                                    Text(
                                        text = "⚡ THUNDERSTORM RISK DETECTED",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFFFBF00),
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append("${"%.4f".format(waypoint.lat)}, ${"%.4f".format(waypoint.lon)}")
                                        append(" • r${waypoint.radius?.toInt() ?: 400}m")
                                        if (waypoint.alt != null) append(" • A${waypoint.alt.toInt()}m")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                                // Show Weather Summary in TEA Mode
                                if (weatherData != null && weatherData.current != null) {
                                    val wind = weatherData.current.wind
                                    Text(
                                        text = "🌬️ ${wind.speed.roundToInt()} kt @ ${wind.direction.roundToInt()}°",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF4ADE80), // Neon Green for tactical clarity
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
