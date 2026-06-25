package com.ternparagliding.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ternparagliding.model.Task
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore

/**
 * The SEARCH and LIBRARY tabs of the task planner (currently pilot-centric
 * mocks). Split out of TaskDetailPanel.kt (Phase 0c god-file split); same
 * ui.components package, so TaskDetailPanel's call sites are unchanged.
 */
/** Great-circle distance in km between two lat/lon points (waypoint nearby-sort). */
private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/** A selectable point in SEARCH — a library spot or a PG spot, unified so both can be
 *  searched, sorted by distance, and added to the task. */
private data class SearchItem(
    val title: String,
    val code: String?,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val isPg: Boolean,
    val refId: String, // library spot id, or PG "name|lat|lon"
)

/**
 * SEARCH tab — find a waypoint in the pilot's **spot library** (imported comp set,
 * dropped points, captured PG spots) **and the PG spots on the map**, and add it to the
 * open task. Results are sorted **nearest first** (GPS fix, else map centre). PG spots
 * are added by capturing them as a PG-provenance spot; library spots by reference.
 */
@Composable
fun TaskSearchContent(store: MapStore) {
    val liveState by store.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    val origin = liveState.userLocation ?: liveState.center
    val taskId = liveState.selectedTaskId

    val items = remember(liveState.waypointLibrary, liveState.pgSpotGeoJson, liveState.tasks, taskId, searchQuery, origin) {
        // Spots already in the task (by spot id) and the PG ids they captured — excluded
        // so SEARCH only offers points you can still add.
        val taskSpotIds = liveState.tasks.find { it.id == taskId }?.waypoints?.mapNotNull { it.spotId }?.toSet() ?: emptySet()
        val capturedPgIds = liveState.waypointLibrary
            .filter { it.source == com.ternparagliding.model.SpotSource.PG_SPOT }
            .mapNotNull { it.sourceId }.toSet()
        val inTaskPgIds = liveState.waypointLibrary
            .filter { it.id in taskSpotIds && it.source == com.ternparagliding.model.SpotSource.PG_SPOT }
            .mapNotNull { it.sourceId }.toSet()

        val libItems = liveState.waypointLibrary
            .filter { it.id !in taskSpotIds }
            .map { SearchItem(it.displayName, it.code, it.lat, it.lon, it.alt, isPg = false, refId = it.id) }
        val pgItems = com.ternparagliding.overlay.pgspot.pgSpotPoints(liveState.pgSpotGeoJson)
            // Drop PG spots already captured into the library (the library item represents
            // them) or already in the task.
            .filter { it.id !in capturedPgIds && it.id !in inTaskPgIds }
            .map { SearchItem(it.name, null, it.lat, it.lon, it.alt, isPg = true, refId = it.id) }

        (libItems + pgItems)
            .filter {
                searchQuery.isBlank() ||
                    it.title.contains(searchQuery, ignoreCase = true) ||
                    (it.code?.contains(searchQuery, ignoreCase = true) == true)
            }
            .map { item -> item to origin?.let { distanceKm(it.latitude, it.longitude, item.lat, item.lon) } }
            .sortedBy { it.second ?: Double.MAX_VALUE }
    }

    Column {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search waypoints & PG spots") },
            modifier = Modifier.fillMaxWidth().testTag("TaskSearchField"),
            singleLine = true,
            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = null) },
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (items.isEmpty()) {
            Text(
                "Nothing to add nearby — import a comp file, drop points on the map, or pan to some PG spots.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            itemsIndexed(items) { _, (item, distKm) ->
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (taskId != null) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                if (item.isPg) {
                                    store.dispatch(MapAction.AddPgSpotToTask(
                                        taskId = taskId, pgSpotId = item.refId, code = item.title,
                                        name = item.title, lat = item.lat, lon = item.lon, alt = item.alt,
                                    ))
                                } else {
                                    store.dispatch(MapAction.AddLibraryWaypointsToTask(taskId, listOf(item.refId)))
                                }
                            }
                        }
                        .padding(vertical = 12.dp)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Place,
                        contentDescription = null,
                        tint = if (item.isPg) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            buildString {
                                if (item.isPg) append("PG spot  ")
                                else if (item.code != null && item.code != item.title) append("${item.code}  ")
                                append("${"%.4f".format(item.lat)}, ${"%.4f".format(item.lon)}")
                                item.alt?.let { append("  •  ${it.toInt()} m") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (distKm != null) {
                        Text(
                            if (distKm < 10) "%.1f km".format(distKm) else "${distKm.toInt()} km",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

