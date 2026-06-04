package com.ternparagliding.ui.components

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
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
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
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.WeatherActions
import com.ternparagliding.utils.RouteIOManager
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
                // Using theme tokens for background
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(if (state.isRoutePanelExpanded) "TEA_Header" else "SSA_Header")
                        .clickable { 
                            if (route != null) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                store.dispatch(MapAction.ToggleRoutePanelExpanded)
                            }
                        },
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
                                        color = MaterialTheme.colorScheme.tertiary,
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
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
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
                                color = MaterialTheme.colorScheme.onTertiary,
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
