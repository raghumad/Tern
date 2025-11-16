package com.madanala.tern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.WaypointSelection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

/**
 * Screen for editing waypoint properties including type selection and deletion
 */
@Composable
fun EditWaypointScreen(
    store: MapStore = viewModel(),
    onDismiss: () -> Unit
) {
    val state by store.state.collectAsState()
    val selectedWaypoint = state.selectedWaypoint

    if (selectedWaypoint == null) {
        onDismiss()
        return
    }

    // Find the selected waypoint data
    val route = state.routes.find { it.id == selectedWaypoint.routeId }
    val waypoint = route?.waypoints?.find { it.id == selectedWaypoint.waypointId }

    if (waypoint == null) {
        onDismiss()
        return
    }

    var showDeleteDialog by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Waypoint") },
            text = { Text("Are you sure you want to delete this waypoint? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.dispatch(MapAction.RemoveWaypoint(selectedWaypoint.routeId, selectedWaypoint.waypointId))
                        showDeleteDialog = false
                        onDismiss()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Edit Waypoint",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Waypoint coordinates (read-only)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Location",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Latitude: ${String.format("%.6f", waypoint.lat)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Longitude: ${String.format("%.6f", waypoint.lon)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Waypoint type selection
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Type",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Waypoint.Type.values().forEach { type ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = waypoint.type == type,
                            onClick = {
                                store.dispatch(MapAction.UpdateWaypointType(
                                    selectedWaypoint.routeId,
                                    selectedWaypoint.waypointId,
                                    type
                                ))
                            }
                        )
                        Text(
                            text = when (type) {
                                Waypoint.Type.LAUNCH -> "Launch"
                                Waypoint.Type.TURNPOINT -> "Turnpoint"
                                Waypoint.Type.LANDING -> "Landing"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        // Cylinder parameters (placeholder for future implementation)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Cylinder Parameters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Cylinder radius and altitude parameters will be configurable here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Delete button
        OutlinedButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Delete Waypoint")
        }

        // Done button
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    }
}