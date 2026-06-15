package com.ternparagliding.ui.components

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
import com.ternparagliding.model.Waypoint
import com.ternparagliding.model.LocationType
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.WaypointSelection
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
    val task = state.tasks.find { it.id == selectedWaypoint.taskId }
    val waypoint = task?.waypoints?.find { it.id == selectedWaypoint.waypointId }

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
                        store.dispatch(MapAction.RemoveWaypoint(selectedWaypoint.taskId, selectedWaypoint.waypointId))
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

        // Which waypoint is being edited — show its label so the pilot (and
        // gesture tests) can confirm the right point was selected.
        waypoint.label?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

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

        // Description — a human name for the cryptic code (e.g. "B4" → "Gold's
        // Point"). Shown in preference to the code in the next-waypoint guidance.
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = waypoint.description ?: "",
                    onValueChange = { newValue ->
                        store.dispatch(MapAction.UpdateWaypointDescription(
                            selectedWaypoint.taskId,
                            selectedWaypoint.waypointId,
                            newValue.takeIf { it.isNotBlank() }
                        ))
                    },
                    label = { Text("Description (e.g. \"Gold's Point\")") },
                    placeholder = { Text(waypoint.label ?: "") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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

                LocationType.values().forEach { type ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = waypoint.type == type,
                            onClick = {
                                store.dispatch(MapAction.UpdateWaypointType(
                                    selectedWaypoint.taskId,
                                    selectedWaypoint.waypointId,
                                    type
                                ))
                            }
                        )
                        Text(
                            text = when (type) {
                                LocationType.LAUNCH -> "Launch"
                                LocationType.TURNPOINT -> "Turnpoint"
                                LocationType.SSS -> "Start Speed Section"
                                LocationType.ESS -> "End Speed Section"
                                LocationType.GOAL -> "Goal"
                                LocationType.LANDING -> "Landing"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        // Cylinder parameters
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Task Parameters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                // Altitude
                OutlinedTextField(
                    value = waypoint.alt?.toString() ?: "",
                    onValueChange = { newValue ->
                        val alt = newValue.toDoubleOrNull()
                        store.dispatch(MapAction.UpdateWaypointAltitude(
                            selectedWaypoint.taskId,
                            selectedWaypoint.waypointId,
                            alt
                        ))
                    },
                    label = { Text("Altitude (m)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Time Gates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = waypoint.openTime ?: "",
                        onValueChange = { newValue ->
                            store.dispatch(MapAction.UpdateWaypointTimeGates(
                                selectedWaypoint.taskId,
                                selectedWaypoint.waypointId,
                                newValue.takeIf { it.isNotBlank() },
                                waypoint.closeTime
                            ))
                        },
                        label = { Text("Open (HH:mm)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = waypoint.closeTime ?: "",
                        onValueChange = { newValue ->
                            store.dispatch(MapAction.UpdateWaypointTimeGates(
                                selectedWaypoint.taskId,
                                selectedWaypoint.waypointId,
                                waypoint.openTime,
                                newValue.takeIf { it.isNotBlank() }
                            ))
                        },
                        label = { Text("Close (HH:mm)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                
                OutlinedTextField(
                    value = waypoint.radius?.toString() ?: "TaskConstants.FAI_DEFAULT_RADIUS_METERS",
                    onValueChange = { newValue ->
                        val radius = newValue.toDoubleOrNull()
                        if (radius != null) {
                            store.dispatch(MapAction.UpdateWaypointRadius(
                                selectedWaypoint.taskId,
                                selectedWaypoint.waypointId,
                                radius
                            ))
                        }
                    },
                    label = { Text("Radius (m)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Text(
                    text = "Standard radii: Turnpoint (400m), Start/Goal (1000m+)",
                    style = MaterialTheme.typography.bodySmall,
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