package com.ternparagliding.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.model.Task
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.io.TaskIOManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The DETAILS tab of the task planner: share/QR actions and the per-waypoint
 * list with weather/ETA/hazard badges and reorder/delete controls.
 *
 * Split out of TaskDetailPanel.kt (Phase 0c god-file split). Same
 * ui.components package, so TaskDetailPanel's call site is unchanged.
 */
@Composable
fun TaskDetailsContent(
    task: Task,
    state: com.ternparagliding.redux.MapState,
    store: MapStore,
    onDismiss: () -> Unit,
) {
    val legs = task.legDistances
    // Turnpoint sequence per point, computed exactly like the map marker (TaskLayer) so the
    // disc badge in this list shows the same number/letter the pilot sees on the map.
    val seqByWp = remember(task.waypoints) {
        var tp = 0
        task.waypoints.associate { wp ->
            wp.id to (if (wp.type == com.ternparagliding.model.LocationType.TURNPOINT) ++tp else 0)
        }
    }
    Column {
        // Add sits next to the count — a compact button, not a full-width slab. This
        // adds by dropping on the map; the SEARCH tab adds from the library.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Waypoints (${task.waypoints.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            androidx.compose.material3.FilledTonalButton(
                onClick = { store.dispatch(MapAction.StartAddWaypoint) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("AddFromMapButton"),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("From map", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (task.waypoints.isEmpty()) {
            Text(
                text = "No points yet — tap “Add point from map”, or use SEARCH to pick from your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            // Plain Column (not a LazyColumn): the panel body scrolls as one, so a nested
            // lazy scroller here would fight it. Tasks have a handful of points — fine.
            Column(
                modifier = Modifier.testTag("WaypointList"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                task.waypoints.forEachIndexed { index, waypoint ->
                    val weatherData = state.weatherState.waypointWeathers[waypoint.id]
                    val etaTimestamp = state.weatherState.waypointEtas[waypoint.id]

                    val formattedEta = remember(etaTimestamp) {
                        etaTimestamp?.let {
                            val instant = Instant.ofEpochMilli(it)
                            val formatter = DateTimeFormatter.ofPattern("HH:mm")
                                .withZone(ZoneId.systemDefault())
                            formatter.format(instant)
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        // Tap a point to edit it (role / start gate, cylinder, gates, and
                        // an "Edit waypoint…" link to rename) — opens the per-point editor.
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { store.dispatch(MapAction.SelectWaypoint(task.id, waypoint.id)) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (weatherData?.hasConvectiveDanger() == true || weatherData?.hasThunderstorm() == true) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiary,
                                            shape = MaterialTheme.shapes.extraSmall,
                                            modifier = Modifier.padding(end = 6.dp).testTag("AHV_BADGE")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = "Hazard",
                                                tint = Color.Black,
                                                modifier = Modifier.size(14.dp).padding(1.dp)
                                            )
                                        }
                                    }
                                    WaypointMarkerBadge(waypoint.type, seqByWp[waypoint.id] ?: 0, size = 24.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "${index + 1}. ${waypoint.displayName ?: waypoint.label ?: "Waypoint"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (formattedEta != null) {
                                        Text(
                                            text = "ETA: $formattedEta",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                                if (weatherData?.hasThunderstorm() == true) {
                                    Text(
                                        text = "⚡ THUNDERSTORM RISK DETECTED",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                                // Leg in from the previous point — the at-a-glance "how
                                // far is this hop" the planner cares about most.
                                if (index > 0) {
                                    val legKm = legs.getOrNull(index - 1)
                                    if (legKm != null) {
                                        Text(
                                            text = "↳ ${com.ternparagliding.overlay.task.TaskGeoJson.formatKm(legKm)} from ${index}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(
                                    text = buildString {
                                        append("⌀ ${waypoint.radius?.toInt() ?: 400} m cylinder")
                                        if (waypoint.alt != null) append(" • ${waypoint.alt.toInt()} m elev")
                                        if (!waypoint.openTime.isNullOrBlank()) append(" • open ${waypoint.openTime}")
                                        if (!waypoint.closeTime.isNullOrBlank()) append(" • close ${waypoint.closeTime}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${"%.4f".format(waypoint.lat)}, ${"%.4f".format(waypoint.lon)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                                // Show Weather Summary in TEA Mode
                                if (weatherData != null && weatherData.current != null) {
                                    val wind = weatherData.current.wind
                                    val gustStr = if (wind.gust > 0.0) " (G ${wind.gust.roundToInt()})" else ""
                                    Text(
                                        text = "🌬️ ${wind.speed.roundToInt()} kt @ ${wind.direction.roundToInt()}°$gustStr",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Edit this point (role / start gate, cylinder, gates, rename).
                                IconButton(
                                    onClick = { store.dispatch(MapAction.SelectWaypoint(task.id, waypoint.id)) },
                                    modifier = Modifier.size(32.dp).testTag("edit_point_${index}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit point",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Move Up
                                IconButton(
                                    onClick = {
                                        store.dispatch(MapAction.ReorderWaypoint(task.id, index, index - 1))
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Move Up",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Move Down
                                IconButton(
                                    onClick = {
                                        store.dispatch(MapAction.ReorderWaypoint(task.id, index, index + 1))
                                    },
                                    enabled = index < task.waypoints.size - 1,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Move Down",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        store.dispatch(MapAction.RemoveWaypoint(task.id, waypoint.id))
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Waypoint",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Trajectory weather (folded in from the old floating HUD) — collapsible,
        // default collapsed so it doesn't pad the panel.
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        TaskTrajectoryWeather(state = state, task = task, store = store)
    }
}

