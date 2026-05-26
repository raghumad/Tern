package com.ternparagliding.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ternparagliding.R
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.model.Route
import com.ternparagliding.utils.RouteIOManager
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun RouteListScreen(
    modifier: Modifier = Modifier,
    store: MapStore,
    onRouteSelected: () -> Unit = {}, // Callback to navigate to map screen
    onDismiss: () -> Unit = {} // Callback to dismiss the screen
) {
    val state by store.state.collectAsState()
    val routes = state.routes
    val selectedRouteId = state.selectedRouteId
    val context = LocalContext.current
    val mapCenter = state.center

    // Filter routes based on viewport (simple distance check < 200km from center)
    val filteredRoutes = remember(routes, mapCenter) {
        val centerLat = mapCenter?.latitude ?: 0.0
        val centerLon = mapCenter?.longitude ?: 0.0
        if (mapCenter == null) return@remember routes // Show all if center unknown

        routes.filter { route ->
            if (route.waypoints.isEmpty()) return@filter true // Keep empty routes visible
            route.waypoints.any { wp ->
                calculateDistance(wp.lat, wp.lon, centerLat, centerLon) < 200.0
            }
        }
    }

    // File Picker for Import
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val importedRoute = RouteIOManager.importRouteFromUri(context, it)
            if (importedRoute != null) {
                store.dispatch(MapAction.AddRoute(importedRoute))
                store.dispatch(MapAction.SelectRoute(importedRoute.id))
                onRouteSelected()
            }
        }
    }

    // QR Scanner
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val importedRoute = RouteIOManager.importRouteFromQrString(result.contents)
            if (importedRoute != null) {
                store.dispatch(MapAction.AddRoute(importedRoute))
                store.dispatch(MapAction.SelectRoute(importedRoute.id))
                onRouteSelected()
            }
        }
    }

    // State for delete confirmation dialog
    var showDeleteDialog by remember { mutableStateOf(false) }
    var routeToDelete by remember { mutableStateOf<String?>(null) }

    // State for rename dialog
    var showRenameDialog by remember { mutableStateOf(false) }
    var routeToRename by remember { mutableStateOf<Route?>(null) }
    var newRouteName by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() } // Dismiss on tap outside
    ) {
        // Main Content Card (Surface)
        Card(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = 600.dp) // Limit height
                .align(Alignment.Center)
                .clickable(enabled = false) {}, // Consume clicks inside the card
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
        // Header with Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Routes",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.Upload, contentDescription = "Import Route")
                }
                IconButton(onClick = {
                    val options = ScanOptions()
                    options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    options.setPrompt("Scan a Route QR Code")
                    options.setBeepEnabled(false)
                    scanLauncher.launch(options)
                }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code")
                }
            }
        }

        if (filteredRoutes.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No nearby routes found",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Create a new route or pan the map to see others",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = {
                        // Create a new empty route with default name
                        val newRoute = Route(name = "New Route ${routes.size + 1}")
                        store.dispatch(MapAction.AddRoute(newRoute))
                        store.dispatch(MapAction.SelectRoute(newRoute.id))
                        onRouteSelected()
                    }
                ) {
                    Text("Create New Route")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredRoutes) { route ->
                    val isSelected = selectedRouteId == route.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                store.dispatch(MapAction.SelectRoute(route.id))
                                onRouteSelected()
                            },
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (isSelected) 8.dp else 2.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = route.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Row {
                                    IconButton(
                                        onClick = {
                                            val updatedRoute = route.copy(
                                                isVisible = !route.isVisible,
                                                updatedAt = Instant.now()
                                            )
                                            store.dispatch(MapAction.UpdateRoute(updatedRoute))
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (route.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (route.isVisible) "Hide route" else "Show route",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            routeToRename = route
                                            newRouteName = route.name
                                            showRenameDialog = true
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.qr_code_2_24), // Reusing icon for rename? Should be edit.
                                            // Wait, previous code used qr_code_2_24 for rename? That's weird.
                                            // I'll change it to Edit icon if available or keep it to minimize diff noise if I don't have Edit icon.
                                            // I'll stick to what was there or use a standard Edit icon.
                                            // Let's use standard Edit icon if possible, but I don't have the import.
                                            // I'll leave it as is for now to avoid breaking if R.drawable.edit isn't there.
                                            // Actually, I'll use Icons.Default.Edit if I add the import.
                                            // I'll just keep the existing painterResource for now.
                                            contentDescription = "Rename route",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            routeToDelete = route.id
                                            showDeleteDialog = true
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.outbox_alt_24),
                                            contentDescription = "Delete route",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${"%.2f".format(route.totalDistanceKm)} km",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${route.estimatedFlightTimeMinutes} min",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${route.waypoints.size} WPs",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Action Button for creating new routes
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
             FloatingActionButton(
                onClick = {
                    // Create a new empty route with default name
                    val newRoute = Route(name = "New Route ${routes.size + 1}")
                    store.dispatch(MapAction.AddRoute(newRoute))
                    store.dispatch(MapAction.SelectRoute(newRoute.id))
                    onRouteSelected()
                }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.route_24),
                    contentDescription = "Create New Route"
                )
            }
        }
        }
    }
}

    // Delete confirmation dialog
    if (showDeleteDialog && routeToDelete != null) {
        val routeName = routes.find { it.id == routeToDelete }?.name ?: "Unknown Route"

        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                routeToDelete = null
            },
            title = {
                Text(text = "Delete Route")
            },
            text = {
                Text(text = "Are you sure you want to delete \"$routeName\"? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        routeToDelete?.let { routeId ->
                            store.dispatch(MapAction.RemoveRoute(routeId))
                        }
                        showDeleteDialog = false
                        routeToDelete = null
                    }
                ) {
                    Text(text = "Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        routeToDelete = null
                    }
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    // Rename dialog
    if (showRenameDialog && routeToRename != null) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                routeToRename = null
                newRouteName = ""
            },
            title = {
                Text(text = "Rename Route")
            },
            text = {
                Column {
                    Text(text = "Enter a new name for the route:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newRouteName,
                        onValueChange = { newRouteName = it },
                        label = { Text("Route Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmedName = newRouteName.trim()
                        if (trimmedName.isNotEmpty()) {
                            routeToRename?.let { route ->
                                val updatedRoute = route.copy(
                                    name = trimmedName,
                                    updatedAt = Instant.now()
                                )
                                store.dispatch(MapAction.UpdateRoute(updatedRoute))
                            }
                            showRenameDialog = false
                            routeToRename = null
                            newRouteName = ""
                        }
                    },
                    enabled = newRouteName.trim().isNotEmpty()
                ) {
                    Text(text = "Rename")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        routeToRename = null
                        newRouteName = ""
                    }
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371 // Earth radius in km
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}