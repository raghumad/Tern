package com.ternparagliding.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.task.cylinderColor
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.resolvedSelectedTask
import com.ternparagliding.ui.theme.TernFontFamily
import org.osmdroid.util.GeoPoint

private val TAGGED_GREEN = Color(0xFF22C55E)
private val ACTIVE_CYAN = Color(0xFF1FE3DE)
private val MISSING_AMBER = Color(0xFFFBBF24)

/**
 * Phase 2 — the in-flight **task ribbon**, opened deliberately from the Task button
 * (never a stray drag). Shows the whole selected task as a transit-style row of
 * waypoint dots — done (green ✓), active (cyan halo), upcoming (dim) — plus the
 * next-WP read and the manual overrides the pilot occasionally needs:
 *
 *  • **Tap a dot → Go to** that waypoint (retarget out of sequence).
 *  • **Skip** the active waypoint (auto-tag missed / deliberately skipped).
 *  • **Back** to the previous waypoint (undo an accidental auto-tag).
 *
 * A modal bottom sheet, so it can never collide with the vario HUD; the pilot
 * opens it, retargets, and dismisses back to the glance surface (the rosette).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskRibbonSheet(
    store: MapStore,
    onDismiss: () -> Unit,
    onManageTasks: () -> Unit,
    onAddFromLibrary: () -> Unit,
) {
    val state by store.state.collectAsState()
    // Resolve library references so the ribbon shows live library identity (Stage B2).
    val task: Task = state.resolvedSelectedTask() ?: return
    val tagged = state.taggedWaypointIds
    val activeId = state.activeWaypointId
    val own = state.userLocation

    val activeIndex = task.waypoints.indexOfFirst { it.id == activeId }
    val active = task.waypoints.getOrNull(activeIndex)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // Header: task name + switch/manage link
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    task.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = TernFontFamily.gruppo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onManageTasks) { Text("Switch / manage") }
            }

            Spacer(Modifier.height(8.dp))

            if (task.waypoints.isEmpty()) {
                // A freshly created task — guide the pilot straight to the library.
                Text(
                    "No waypoints in this task yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAddFromLibrary, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add from library")
                }
                return@Column
            }

            // Next-WP read (name + distance), or task-complete.
            if (active != null) {
                val dist = own?.distanceToAsDouble(GeoPoint(active.lat, active.lon))
                Text(
                    buildString {
                        append("NEXT  ")
                        append(active.displayName ?: "WP ${activeIndex + 1}")
                        if (dist != null) append("  •  ${formatRibbonDistance(dist)}")
                    },
                    color = ACTIVE_CYAN,
                    fontFamily = TernFontFamily.gruppo,
                    fontSize = 16.sp,
                )
            } else {
                Text(
                    "Task complete",
                    color = TAGGED_GREEN,
                    fontFamily = TernFontFamily.gruppo,
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            // The ribbon: dots + connecting legs, horizontally scrollable.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                task.waypoints.forEachIndexed { i, wp ->
                    if (i > 0) {
                        // Leg done (green) once the *previous* waypoint is tagged.
                        val legDone = task.waypoints[i - 1].id in tagged
                        Box(
                            Modifier
                                .width(20.dp)
                                .height(3.dp)
                                .background(if (legDone) TAGGED_GREEN else Color(0x55FFFFFF)),
                        )
                    }
                    WaypointDot(
                        wp = wp,
                        ordinal = i + 1,
                        isTagged = wp.id in tagged,
                        isActive = wp.id == activeId,
                        isMissing = com.ternparagliding.overlay.task.TaskResolver.isMissingLink(wp, state.waypointLibrary),
                        onClick = { store.dispatch(MapAction.GoToWaypoint(task.id, wp.id)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Add more waypoints from the library.
            OutlinedButton(onClick = onAddFromLibrary, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add from library")
            }

            Spacer(Modifier.height(12.dp))

            // Manual overrides: Back (revert) and Skip (advance).
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val canBack = activeIndex > 0 || (activeId == null && task.waypoints.isNotEmpty())
                OutlinedButton(
                    onClick = {
                        val target = if (activeId == null) task.waypoints.lastOrNull()
                        else task.waypoints.getOrNull(activeIndex - 1)
                        target?.let { store.dispatch(MapAction.GoToWaypoint(task.id, it.id)) }
                    },
                    enabled = canBack,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Back")
                }
                Button(
                    onClick = { active?.let { store.dispatch(MapAction.TagWaypoint(it.id)) } },
                    enabled = active != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Skip")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** One ribbon dot: role-coloured, ordinal/code centred. Done = filled + ✓; active =
 *  cyan halo; upcoming = dim outline. Tap to Go to. */
@Composable
private fun WaypointDot(
    wp: Waypoint,
    ordinal: Int,
    isTagged: Boolean,
    isActive: Boolean,
    isMissing: Boolean,
    onClick: () -> Unit,
) {
    val role = Color(cylinderColor(wp.type))
    // The dot shows the ordinal (metro-style) — comp codes like "LW049" don't fit a
    // 40dp dot. Role colour conveys type; the code/name reads in the NEXT line + on tap.
    val seq = ordinal.toString()
    Box(contentAlignment = Alignment.Center) {
        val base = Modifier
            .size(40.dp)
            .clickable(onClick = onClick)
        when {
            isActive -> Box(
                base.background(role, CircleShape).border(3.dp, ACTIVE_CYAN, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(seq, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

            isTagged -> Box(
                base.background(role.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, contentDescription = "reached", tint = Color.White, modifier = Modifier.size(20.dp)) }

            else -> Box(
                base.border(2.dp, role.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(seq, color = role, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
        // Reference to a library waypoint that's no longer present — flying a
        // possibly-stale stored position. Flag it with an amber "!" badge.
        if (isMissing) {
            Box(
                Modifier
                    .size(16.dp)
                    .align(Alignment.TopEnd)
                    .background(MISSING_AMBER, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("!", color = Color(0xFF06262B), fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        }
    }
}

private fun formatRibbonDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()}m" else String.format("%.1fkm", meters / 1000.0)
