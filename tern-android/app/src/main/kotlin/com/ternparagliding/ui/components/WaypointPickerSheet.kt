package com.ternparagliding.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore

/**
 * Stage B1 — pick waypoints from the **library** to add to a task. Multi-select
 * (preserving pick order, which becomes task order), with search. Each picked
 * waypoint is appended as a task point linked back to the library entry; the pilot
 * then sets roles / cylinders / gates in the per-point editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointPickerSheet(
    store: MapStore,
    taskId: String,
    onDismiss: () -> Unit,
) {
    val state by store.state.collectAsState()
    val library = state.waypointLibrary
    var query by remember { mutableStateOf("") }
    // Ordered selection — the order taps happen in is the order they join the task.
    val picked = remember { mutableStateListOf<String>() }

    val filtered = remember(library, query) {
        if (query.isBlank()) library
        else library.filter { it.code.contains(query, true) || (it.name?.contains(query, true) == true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Add from library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            if (library.isEmpty()) {
                Text(
                    "Your waypoint library is empty. Import a .cup / .wpt / .gpx first (Tasks → waypoint library).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                return@Column
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search code or name") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = 8.dp)) {
                items(filtered, key = { it.id }) { wp ->
                    val isPicked = picked.contains(wp.id)
                    val order = if (isPicked) picked.indexOf(wp.id) + 1 else null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isPicked) picked.remove(wp.id) else picked.add(wp.id)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isPicked, onCheckedChange = {
                            if (isPicked) picked.remove(wp.id) else picked.add(wp.id)
                        })
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(wp.code, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                wp.name?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text(String.format("%.4f, %.4f", wp.lat, wp.lon),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Show the pick order so the resulting task sequence is predictable.
                        order?.let {
                            Text("#$it", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (picked.isNotEmpty()) {
                        store.dispatch(MapAction.AddLibraryWaypointsToTask(taskId, picked.toList()))
                    }
                    onDismiss()
                },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (picked.isEmpty()) "Select waypoints" else "Add ${picked.size} to task")
            }
        }
    }
}
