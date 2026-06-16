package com.ternparagliding.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ternparagliding.model.LibraryWaypoint
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.io.TaskIOManager
import androidx.compose.ui.platform.LocalContext

/**
 * The standalone **waypoint library** — waypoints exist here independently of any
 * task (comp organisers issue them first; tasks reference them later). Import
 * .cup/.wpt/.gpx, search, and delete. Tasks are built by referencing these.
 */
@Composable
fun WaypointLibraryScreen(
    modifier: Modifier = Modifier,
    store: MapStore,
    onDismiss: () -> Unit = {},
) {
    val state by store.state.collectAsState()
    val context = LocalContext.current
    val library = state.waypointLibrary
    var query by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf<String?>(null) }

    val filtered = remember(library, query) {
        if (query.isBlank()) library
        else library.filter {
            it.code.contains(query, true) || (it.name?.contains(query, true) == true)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val wps = TaskIOManager.importWaypointsFromUri(context, it)
            if (wps.isNotEmpty()) {
                store.dispatch(MapAction.ImportWaypointsToLibrary(wps))
                importMsg = "Imported ${wps.size} waypoints"
            } else {
                importMsg = "No waypoints found in that file"
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
    ) {
        Card(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .align(Alignment.Center)
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Waypoints", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${library.size} in library", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Upload, contentDescription = "Import waypoints (.cup/.wpt/.gpx)")
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search code or name") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )

                importMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }

                if (library.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("No waypoints yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Import the comp waypoint file (.cup, .wpt, or .gpx). Tasks are built from these.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 12.dp)) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Text("  Import waypoints")
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
                        items(filtered, key = { it.id }) { wp ->
                            WaypointRow(
                                wp = wp,
                                onLocate = {
                                    // Centre the map on the waypoint and close the library so it's visible.
                                    store.dispatch(MapAction.UpdateCenter(org.osmdroid.util.GeoPoint(wp.lat, wp.lon)))
                                    store.dispatch(MapAction.UpdateZoom(13.0))
                                    onDismiss()
                                },
                                onDelete = { store.dispatch(MapAction.RemoveLibraryWaypoint(wp.id)) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    if (library.isNotEmpty()) {
                        TextButton(
                            onClick = { store.dispatch(MapAction.ClearWaypointLibrary) },
                            modifier = Modifier.padding(8.dp),
                        ) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaypointRow(wp: LibraryWaypoint, onLocate: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onLocate) // tap the row → locate on the map
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(wp.code, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                wp.name?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                buildString {
                    append(String.format("%.4f, %.4f", wp.lat, wp.lon))
                    wp.alt?.let { append("  •  ${it.toInt()} m") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${wp.code}",
                tint = MaterialTheme.colorScheme.error)
        }
    }
}
