package com.ternparagliding.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore

/**
 * **Workflow A — Edit Waypoint (the Spot).** Edits a waypoint's *intrinsic* identity —
 * name, code, ground elevation, position — which is **shared**: the change flows to every
 * task that references this spot (via [MapAction.UpdateSpot]). This is distinct from
 * Workflow B (a task's per-point features: role / cylinder / gates), which lives in
 * [EditWaypointScreen].
 *
 * Opaque surface (it must not let the map bleed through and hide controls), a scrollable
 * field area, and Delete/Done **pinned** above the system bars so they're never obscured.
 */
@Composable
fun EditSpotScreen(
    spotId: String,
    store: MapStore = viewModel(),
    onDismiss: () -> Unit,
) {
    val state by store.state.collectAsState()
    val spot = state.waypointLibrary.find { it.id == spotId }

    if (spot == null) { onDismiss(); return }

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Waypoint") },
            text = {
                Text("Delete \"${spot.displayName}\"? Tasks that reference it will fly from " +
                    "their last-known position and be flagged.")
            },
            confirmButton = {
                TextButton(onClick = {
                    store.dispatch(MapAction.RemoveLibraryWaypoint(spot.id))
                    showDeleteDialog = false
                    onDismiss()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {

            Text(
                text = "Edit Waypoint",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp),
            )

            // Scrollable field area (weight on a non-scrolled parent → valid).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Changes here apply to this waypoint everywhere it's used.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = spot.name ?: "",
                    onValueChange = { v ->
                        store.dispatch(MapAction.UpdateSpot(spot.id, name = v.takeIf { it.isNotBlank() }))
                    },
                    label = { Text("Name") },
                    placeholder = { Text(spot.code) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("spot_name_field"),
                )

                OutlinedTextField(
                    value = spot.code,
                    onValueChange = { v ->
                        store.dispatch(MapAction.UpdateSpot(spot.id, code = v.takeIf { it.isNotBlank() }))
                    },
                    label = { Text("Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("spot_code_field"),
                )

                OutlinedTextField(
                    value = spot.alt?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "",
                    onValueChange = { v ->
                        v.trim().toDoubleOrNull()?.let { store.dispatch(MapAction.UpdateSpot(spot.id, alt = it)) }
                    },
                    label = { Text("Elevation (m)") },
                    placeholder = { Text("ground elevation") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("spot_elev_field"),
                )

                // Position — read-only, edited by tapping a new spot on the map.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = String.format("%.6f, %.6f", spot.lat, spot.lon),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = {
                                // Arm spot move-mode and step aside so the map is visible;
                                // a single map tap drops it at the new position.
                                store.dispatch(MapAction.StartSpotMove(spot.id))
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("spot_move_button"),
                        ) { Text("Move on Map") }
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
                ) { Text("Delete") }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Done") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
