package com.ternparagliding.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ternparagliding.model.LocationType
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.resolvedTasks
import kotlin.math.roundToInt

/**
 * **Workflow B1 — Edit Task.** Owns the task's *structure*: its name and the **ordered
 * sequence** of points. Reorder by dragging a row's handle, remove a point, add from the
 * library, and tap a row to edit that point's per-task features ([EditWaypointScreen]).
 *
 * Reorder is committed on drag-release: the dragged row follows the finger, then snaps to
 * the slot its centre landed in (`ReorderWaypoint`). Rows are fixed-height so the target
 * slot is just the accumulated offset / row height.
 */
@Composable
fun EditTaskScreen(
    taskId: String,
    store: MapStore = viewModel(),
    onEditPoint: (waypointId: String) -> Unit,
    onAddFromLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by store.state.collectAsState()
    val task = state.resolvedTasks().find { it.id == taskId }

    if (task == null) { onDismiss(); return }

    val rowHeight = 60.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragFrom by remember { mutableStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {

            Text("Edit Task", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = task.name,
                    onValueChange = { store.dispatch(MapAction.UpdateTask(task.copy(name = it))) },
                    label = { Text("Task name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("task_name_field"),
                )

                Text("WAYPOINTS  ·  drag the handle to reorder",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

                if (task.waypoints.isEmpty()) {
                    Text("No points yet. Add from the library below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // Turnpoint sequence per point, matching the map marker (TaskLayer) so the
                    // disc badge here shows the same number/letter as on the map.
                    val seqByWp = remember(task.waypoints) {
                        var tp = 0
                        task.waypoints.associate { wp ->
                            wp.id to (if (wp.type == LocationType.TURNPOINT) ++tp else 0)
                        }
                    }
                    Column {
                        task.waypoints.forEachIndexed { index, wp ->
                            val isDragging = index == dragFrom
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeight)
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .offset { IntOffset(0, if (isDragging) dragOffsetY.roundToInt() else 0) }
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                                    )
                                    .clickable { onEditPoint(wp.id) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Drag handle — only this child captures drag gestures.
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "Reorder ${wp.displayName ?: wp.label ?: ""}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp)
                                        .pointerInput(taskId, task.waypoints.size) {
                                            detectDragGestures(
                                                onDragStart = { dragFrom = index; dragOffsetY = 0f },
                                                onDragEnd = {
                                                    val shift = (dragOffsetY / rowPx).roundToInt()
                                                    val to = (index + shift).coerceIn(0, task.waypoints.lastIndex)
                                                    if (to != index) {
                                                        store.dispatch(MapAction.ReorderWaypoint(taskId, index, to))
                                                    }
                                                    dragFrom = -1; dragOffsetY = 0f
                                                },
                                                onDragCancel = { dragFrom = -1; dragOffsetY = 0f },
                                                onDrag = { change, delta -> change.consume(); dragOffsetY += delta.y },
                                            )
                                        },
                                )

                                // Role marker — identical to the map (disc + white ring + code).
                                WaypointMarkerBadge(wp.type, seqByWp[wp.id] ?: 0, size = 28.dp)

                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(wp.displayName ?: wp.label ?: "Waypoint",
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildString {
                                            append(roleLabel(wp.type))
                                            wp.radius?.let { append("  •  ${it.toInt()} m") }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                IconButton(onClick = {
                                    store.dispatch(MapAction.RemoveWaypoint(taskId, wp.id))
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove ${wp.label ?: ""}",
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                OutlinedButton(onClick = onAddFromLibrary, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add from library")
                }
            }

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Done") }
        }
    }
}

private fun roleLabel(type: LocationType): String = when (type) {
    LocationType.LAUNCH -> "Takeoff"
    LocationType.TURNPOINT -> "Turnpoint"
    LocationType.SSS -> "Start (SSS)"
    LocationType.ESS -> "ESS"
    LocationType.GOAL -> "Goal"
    LocationType.LANDING -> "Landing"
}
