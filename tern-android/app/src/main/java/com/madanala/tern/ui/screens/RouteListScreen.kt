package com.madanala.tern.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.madanala.tern.R
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.route.Route
import java.time.Instant

@Composable
fun RouteListScreen(
    modifier: Modifier = Modifier,
    store: MapStore,
    onRouteSelected: () -> Unit = {} // Callback to navigate to map screen
) {
    val state by store.state.collectAsState()
    val routes = state.routes
    val selectedRouteId = state.selectedRouteId

    // State for delete confirmation dialog
    var showDeleteDialog by remember { mutableStateOf(false) }
    var routeToDelete by remember { mutableStateOf<String?>(null) }

    // State for rename dialog
    var showRenameDialog by remember { mutableStateOf(false) }
    var routeToRename by remember { mutableStateOf<Route?>(null) }
    var newRouteName by remember { mutableStateOf("") }

    if (routes.isEmpty()) {
        // Empty state
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No routes available",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Create a new route to get started",
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
                }
            ) {
                Text("Create New Route")
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(routes) { route ->
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
                                            routeToRename = route
                                            newRouteName = route.name
                                            showRenameDialog = true
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.qr_code_2_24),
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
                                    text = "Distance: ${"%.2f".format(route.totalDistanceKm)} km",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Flight Time: ${route.estimatedFlightTimeMinutes} min",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Waypoints: ${route.waypoints.size}",
                                    style = MaterialTheme.typography.bodyMedium
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

            // Floating Action Button for creating new routes
            FloatingActionButton(
                onClick = {
                    // Create a new empty route with default name
                    val newRoute = Route(name = "New Route ${routes.size + 1}")
                    store.dispatch(MapAction.AddRoute(newRoute))
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.route_24),
                    contentDescription = "Create New Route"
                )
            }
        }
    }
}