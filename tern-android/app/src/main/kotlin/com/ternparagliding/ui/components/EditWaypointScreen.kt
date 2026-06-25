package com.ternparagliding.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ternparagliding.model.LocationType
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.resolvedTasks
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * **Workflow B2 — the per-point task editor.** Edits how *this task* uses a point: its
 * **role, cylinder radius, and time gates** (per-task — the same waypoint can be 1 km in
 * one task and 200 m in another). The waypoint's intrinsic identity (name / position /
 * elevation) is shown **read-only** with an "Edit waypoint…" link into Workflow A
 * ([EditSpotScreen]), because changing identity affects *every* task that uses the spot.
 *
 * Opaque surface; Delete/Done pinned above the system bars so they're never obscured.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditWaypointScreen(
    store: MapStore = viewModel(),
    onEditSpot: (spotId: String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val state by store.state.collectAsState()
    val selectedWaypoint = state.selectedWaypoint

    if (selectedWaypoint == null) { onDismiss(); return }

    // Resolve so identity (name/coords/alt) reflects the live spot, not a stale snapshot.
    val task = state.resolvedTasks().find { it.id == selectedWaypoint.taskId }
    val waypoint = task?.waypoints?.find { it.id == selectedWaypoint.waypointId }

    if (waypoint == null) { onDismiss(); return }

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Remove from task") },
            text = { Text("Remove this point from \"${task.name}\"? The waypoint itself stays in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    store.dispatch(MapAction.RemoveWaypoint(selectedWaypoint.taskId, selectedWaypoint.waypointId))
                    showDeleteDialog = false
                    onDismiss()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {

            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Text("Edit Point", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                task?.name?.let {
                    Text("in “$it”", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── WAYPOINT (shared identity, read-only here) ──────────────────
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("WAYPOINT", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(waypoint.displayName ?: waypoint.label ?: "Waypoint",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            buildString {
                                append(String.format("%.5f, %.5f", waypoint.lat, waypoint.lon))
                                waypoint.alt?.let { append("  •  ${it.toInt()} m") }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        waypoint.spotId?.let { sid ->
                            TextButton(
                                onClick = { onEditSpot(sid) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.testTag("edit_waypoint_link"),
                            ) { Text("Edit waypoint…") }
                            Text("Identity is shared — editing it changes this waypoint in every task.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ── IN THIS TASK (per-task features) ────────────────────────────
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("IN THIS TASK", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)

                        Text("Role", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        // Compact chip row (not 6 stacked radios) — readable + far less scrolling.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LocationType.values().forEach { type ->
                                FilterChip(
                                    selected = waypoint.type == type,
                                    onClick = {
                                        store.dispatch(MapAction.UpdateWaypointType(
                                            selectedWaypoint.taskId, selectedWaypoint.waypointId, type))
                                    },
                                    label = {
                                        Text(when (type) {
                                            LocationType.LAUNCH -> "Takeoff"
                                            LocationType.TURNPOINT -> "Turnpoint"
                                            LocationType.SSS -> "SSS"
                                            LocationType.ESS -> "ESS"
                                            LocationType.GOAL -> "Goal"
                                            LocationType.LANDING -> "Landing"
                                        })
                                    },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = waypoint.radius?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "",
                            onValueChange = { newValue ->
                                val trimmed = newValue.trim()
                                if (trimmed.isEmpty()) {
                                    store.dispatch(MapAction.UpdateWaypointRadius(
                                        selectedWaypoint.taskId, selectedWaypoint.waypointId, null))
                                } else trimmed.toDoubleOrNull()?.let { radius ->
                                    store.dispatch(MapAction.UpdateWaypointRadius(
                                        selectedWaypoint.taskId, selectedWaypoint.waypointId, radius))
                                }
                            },
                            label = { Text("Cylinder radius (m)") },
                            placeholder = { Text("default") },
                            modifier = Modifier.fillMaxWidth().testTag("wp_radius_field"),
                            singleLine = true,
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = waypoint.openTime ?: "",
                                onValueChange = { v ->
                                    store.dispatch(MapAction.UpdateWaypointTimeGates(
                                        selectedWaypoint.taskId, selectedWaypoint.waypointId,
                                        v.takeIf { it.isNotBlank() }, waypoint.closeTime))
                                },
                                label = { Text("Open (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = waypoint.closeTime ?: "",
                                onValueChange = { v ->
                                    store.dispatch(MapAction.UpdateWaypointTimeGates(
                                        selectedWaypoint.taskId, selectedWaypoint.waypointId,
                                        waypoint.openTime, v.takeIf { it.isNotBlank() }))
                                },
                                label = { Text("Close (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }

                        Text("Standard radii: Turnpoint 400 m, Start/Goal 1000 m+",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Pinned action bar — always visible, inset above the nav bar.
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Done") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
