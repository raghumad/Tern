package com.ternparagliding.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.resolvedSelectedTask
import com.ternparagliding.redux.WeatherActions
import com.ternparagliding.utils.io.TaskIOManager
import com.ternparagliding.weather.FlightRisk
import com.ternparagliding.weather.Verdict
import com.ternparagliding.weather.assessTaskFlightRisk
import androidx.compose.runtime.LaunchedEffect
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class PlanningTab {
    DETAILS, SEARCH
}

@Composable
fun TaskDetailPanel(
    modifier: Modifier = Modifier,
    store: MapStore,
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    val state by store.state.collectAsState()
    val selectedTaskId = state.selectedTaskId
    // Resolve library references so the panel reflects the live library (Stage B2).
    val task = state.resolvedSelectedTask()
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var activeTab by remember(isVisible) { mutableStateOf(PlanningTab.DETAILS) }
    var showQrDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTaskId) {
        if (selectedTaskId != null) {
            store.dispatch(WeatherActions.FetchWeatherForTask(selectedTaskId))
        }
    }

    if (showQrDialog && task != null) {
        Dialog(onDismissRequest = { showQrDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan to Import Task",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    val qrBitmap = remember(task) { TaskIOManager.generateQRCode(task) }
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Task QR Code",
                            modifier = Modifier.size(250.dp)
                        )
                    } else {
                        Text("Error generating QR Code")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(task.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("TaskDetailPanel"),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            colors = CardDefaults.cardColors(
                // Using theme tokens for background
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Whole-task flight risk — the worst KNOWN factor across wind, gusts,
                // shear, convection, storm, visibility, precip + airspace, read at each
                // waypoint's ETA. Advisory + transparent (the pilot decides); replaces the
                // old convective-only "storm risk" alarm that false-fired on a 0 km task.
                val flightRisk = remember(task, state.weatherState, state.airspaceConflicts) {
                    task?.let {
                        assessTaskFlightRisk(
                            waypoints = it.waypoints,
                            weathers = state.weatherState.waypointWeathers,
                            etas = state.weatherState.waypointEtas,
                            airspaceConflicts = state.airspaceConflicts,
                        )
                    }
                }

                // SSA/TEA Header: Always visible, answer the "Safe to Fly?" question instantly
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(if (state.isTaskPanelExpanded) "TEA_Header" else "SSA_Header")
                        .clickable { 
                            if (task != null) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                store.dispatch(MapAction.ToggleTaskPanelExpanded)
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (task != null) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = task.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                // Verdict is shown prominently in the always-visible
                                // FlightRiskBanner just below — no redundant chip here.
                            }
                            // Stats folded in from the old floating HUD: distance · time ·
                            // task type + FAI points (triangles only) · waypoint count.
                            Text(
                                text = buildString {
                                    append("${"%.1f".format(task.totalDistanceKm)} km")
                                    append(" · ${task.estimatedFlightTimeMinutes} min")
                                    // Show the scoring type only for triangles — open distance
                                    // points equal the distance, so the number would just repeat.
                                    when (task.taskType) {
                                        com.ternparagliding.model.Task.TaskType.FAI_TRIANGLE ->
                                            append(" · FAI △ ${"%.1f".format(task.faiPoints)}")
                                        com.ternparagliding.model.Task.TaskType.FLAT_TRIANGLE ->
                                            append(" · flat △ ${"%.1f".format(task.faiPoints)}")
                                        com.ternparagliding.model.Task.TaskType.OPEN_DISTANCE -> {}
                                    }
                                    append(" · ${task.waypoints.size} pts")
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        Text(
                            "Task Planner",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (task != null) {
                            // Secondary actions (share / export) live in the header now —
                            // off the primary planning surface, but one tap away.
                            IconButton(onClick = { TaskIOManager.shareTaskFile(context, task) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share task", tint = Color.White)
                            }
                            IconButton(onClick = { showQrDialog = true }) {
                                Icon(Icons.Default.QrCode, contentDescription = "Show QR code", tint = Color.White)
                            }
                            IconButton(onClick = { store.dispatch(MapAction.ToggleTaskPanelExpanded) }) {
                                Icon(
                                    imageVector = if (state.isTaskPanelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = if (state.isTaskPanelExpanded) "Collapse" else "Expand",
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }
                
                // Flight-risk synthesis: the advisory verdict + its deciding factor.
                // Tap to expand the full per-waypoint breakdown (trajectory weather).
                if (task != null) {
                    flightRisk?.let { risk ->
                        FlightRiskBanner(risk) {
                            store.dispatch(MapAction.ToggleTaskPanelExpanded)
                        }
                    }
                }

                if (state.isTaskPanelExpanded || task == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))

                    // Tab System
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlanningTabButton("DETAILS", activeTab == PlanningTab.DETAILS) { 
                            activeTab = PlanningTab.DETAILS 
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        }
                        PlanningTabButton("SEARCH", activeTab == PlanningTab.SEARCH) {
                            activeTab = PlanningTab.SEARCH
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tab body scrolls within a capped height so a long waypoint list +
                    // expanded trajectory weather never overflow the screen (the panel is
                    // bottom-anchored; without this the bottom is cut off and unreachable).
                    val tabMaxH = (LocalConfiguration.current.screenHeightDp * 0.52f).dp
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = tabMaxH)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (activeTab) {
                            PlanningTab.DETAILS -> {
                                if (task != null) {
                                    TaskDetailsContent(task, state, store, onDismiss)
                                } else {
                                    Text("No task selected. Tap on map to start planning.", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            PlanningTab.SEARCH -> {
                                TaskSearchContent(store)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Verdict palette — green / amber / red, readable on the dark panel (kept in step
// with the trajectory-weather palette in TaskPlanningHUD).
private val RISK_GO = Color(0xFF00C853)
private val RISK_CAUTION = Color(0xFFFFB300)
private val RISK_NOGO = Color(0xFFE53935)

private fun verdictColor(v: Verdict) = when (v) {
    Verdict.GO -> RISK_GO
    Verdict.CAUTION -> RISK_CAUTION
    Verdict.NO_GO -> RISK_NOGO
}

/**
 * The headline flight-risk read: a colour-coded verdict pill + the single deciding
 * factor (with where/when), or a transparent "awaiting forecast" when we can't see
 * the whole task yet. Advisory — it states the worst KNOWN factor and lets the pilot
 * decide; tap to expand the full per-waypoint breakdown.
 */
@Composable
private fun FlightRiskBanner(risk: FlightRisk, onExpand: () -> Unit) {
    // No forecast yet → say so plainly rather than imply "all clear".
    if (!risk.anyData) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("FlightRiskBanner"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.HelpOutline, contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "FLIGHT RISK · awaiting forecast",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        return
    }

    val v = risk.verdict
    val verdictText = when (v) {
        Verdict.GO -> "GO"
        Verdict.CAUTION -> "CAUTION"
        Verdict.NO_GO -> "NO-GO"
    }
    val headline = risk.headline?.takeIf { v != Verdict.GO }

    val onBanner = if (v == Verdict.GO) Color.White else Color.Black
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onExpand() }
            .testTag("FlightRiskBanner"),
        color = verdictColor(v).copy(alpha = if (v == Verdict.GO) 0.22f else 0.92f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Verdict pill + (partial-data note) on the top line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = verdictColor(v), shape = MaterialTheme.shapes.extraSmall) {
                    Text(
                        "FLIGHT RISK · $verdictText",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.Black,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!risk.dataComplete) {
                    Text(
                        "partial data",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = onBanner.copy(alpha = 0.7f),
                    )
                }
            }
            // The deciding factor, full width so the analysis stays legible.
            if (headline != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${headline.label} · ${headline.detail}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = onBanner,
                )
                headline.where?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = onBanner.copy(alpha = 0.75f),
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    "no limiting factors on the task",
                    style = MaterialTheme.typography.bodySmall,
                    color = onBanner.copy(alpha = 0.9f),
                )
            }
        }
    }
}

@Composable
fun PlanningTabButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier
            .heightIn(min = 48.dp), // Aviation-Grade: Min 48dp target
        shape = MaterialTheme.shapes.small
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}
