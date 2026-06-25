package com.ternparagliding.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ternparagliding.ui.components.*
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.WeatherActions
import com.ternparagliding.redux.resolvedTasks
import kotlinx.coroutines.launch
import com.ternparagliding.mezulla.demo.AravisDemoReplay
import com.ternparagliding.mezulla.ui.SosAlertBanner
import org.osmdroid.util.GeoPoint
import kotlin.math.*

const val MAP_VIEW_TERRAIN = 0
const val MAP_VIEW_SATELLITE = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TernMapScreen(
    modifier: Modifier = Modifier,
    store: MapStore = viewModel()
) {
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showEditWaypointScreen by remember { mutableStateOf(false) }
    var showTaskListScreen by remember { mutableStateOf(false) }
    var showTaskRibbon by remember { mutableStateOf(false) }
    var showWaypointLibrary by remember { mutableStateOf(false) }
    var showWaypointPicker by remember { mutableStateOf(false) }
    // Workflow A — editing a standalone waypoint's identity (null = closed). Opened from
    // the library, the map weather sheet, or the per-point editor's "Edit waypoint…" link.
    var editingSpotId by remember { mutableStateOf<String?>(null) }
    // Workflow B1 — editing a task's structure (name + ordered points + reorder).
    var editingTaskId by remember { mutableStateOf<String?>(null) }
    val state by store.state.collectAsState()
    val isLocationReady = state.isLocationReady
    val gpsStatus = state.gpsStatus

    // Dismiss welcome screen after minimum duration and location readiness
    var minDisplayTimeReached by remember { mutableStateOf(false) }
    var welcomeTimedOut by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500) // Minimum 1.5s branding visibility
        minDisplayTimeReached = true
    }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5000) // Safety timeout
        welcomeTimedOut = true
    }

    val showWelcome = !minDisplayTimeReached || (!isLocationReady && !welcomeTimedOut)

    // Demo replay state
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val demoReplay = remember {
        AravisDemoReplay(
            store = store,
            onReplayFinished = {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Aravis replay finished — all pilots landed.")
                }
            },
        )
    }

    // Show edit waypoint screen when a waypoint is selected — but step aside while
    // that selection is in "move mode" (isDragging), so the pilot can see the map and
    // tap the new spot. Committing the move flips isDragging off and the editor returns,
    // confirming the new location.
    // In add-from-map mode each drop selects the new point, but we must NOT pop the
    // editor — the pilot is mid-flow placing several points. Suppress while adding.
    LaunchedEffect(state.selectedWaypoint, state.addingWaypoint) {
        showEditWaypointScreen = !state.addingWaypoint &&
            (state.selectedWaypoint?.let { !it.isDragging && !it.isNew } ?: false)
    }

    // Android Back should close the open layer, never drop the pilot out of the app
    // mid-planning. Closes the topmost overlay/mode/panel in z-order; only when nothing
    // is open does Back fall through to the system (exit). Modal sheets keep their own
    // back handling (registered later, they win first); this covers the panel + the
    // full-screen overlays + the transient modes that otherwise had no handler.
    val backTarget: (() -> Unit)? = when {
        editingSpotId != null -> ({ editingSpotId = null })
        editingTaskId != null -> ({ editingTaskId = null })
        showWaypointPicker -> ({ showWaypointPicker = false })
        showEditWaypointScreen -> ({ showEditWaypointScreen = false; store.dispatch(MapAction.DeselectWaypoint) })
        showWaypointLibrary -> ({ showWaypointLibrary = false })
        showTaskListScreen -> ({ showTaskListScreen = false })
        showTaskRibbon -> ({ showTaskRibbon = false })
        showSettingsSheet -> ({ showSettingsSheet = false })
        state.selectedWaypoint?.isDragging == true -> ({ store.dispatch(MapAction.CancelWaypointDrag) })
        state.movingSpotId != null -> ({ store.dispatch(MapAction.CancelSpotMove) })
        state.addingWaypoint -> ({ store.dispatch(MapAction.StopAddWaypoint) })
        state.selectedTaskId != null -> ({ store.dispatch(MapAction.DeselectTask) })
        else -> null
    }
    BackHandler(enabled = backTarget != null) { backTarget?.invoke() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            MapViewContainer(
                modifier = Modifier.fillMaxSize(),
                store = store
            )

            // SOS alert banner -- top of screen, above everything
            SosAlertBanner(
                peerState = state.peerState,
                dismissedSosAlerts = state.dismissedSosAlerts,
                userLocation = state.userLocation,
                onDismiss = { nodeNumber: Long ->
                    store.dispatch(MapAction.DismissSosAlert(nodeNumber))
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            )

            // Move-mode banner: while a waypoint is armed for moving, tell the pilot
            // what to do (tap to place) and offer an explicit out (Cancel restores the
            // original position). Sits at the top, clear of the right-edge dock.
            state.selectedWaypoint?.takeIf { it.isDragging }?.let { sel ->
                val movingName = state.resolvedTasks().find { it.id == sel.taskId }
                    ?.waypoints?.find { it.id == sel.waypointId }?.displayName ?: "waypoint"
                MoveWaypointBanner(
                    name = movingName,
                    onCancel = { store.dispatch(MapAction.CancelWaypointDrag) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(WindowInsets.statusBars.asPaddingValues())
                        .padding(16.dp),
                )
            }

            // Spot move-mode banner (Workflow A "Move on map" for a standalone waypoint).
            state.movingSpotId?.let { spotId ->
                val movingName = state.waypointLibrary.find { it.id == spotId }?.displayName ?: "waypoint"
                MoveWaypointBanner(
                    name = movingName,
                    onCancel = { store.dispatch(MapAction.CancelSpotMove) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(WindowInsets.statusBars.asPaddingValues())
                        .padding(16.dp),
                )
            }

            // Controls: settings, recenter, task, Mezulla view mode. Shared content,
            // laid out per-orientation below. (Sharing is contextual now; vario pairing
            // lives in Settings → Connections.)
            val controlsLandscape =
                LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val dockContent: @Composable () -> Unit = {
                SettingsButton(onClick = { showSettingsSheet = true })
                RecenterButton(
                    enabled = state.userLocation != null,
                    // Off-follow + a live fix → the button is the way back: re-lock the camera onto
                    // the pilot. Highlighted so it reads as "tap to follow me again".
                    suspended = !state.cameraFollow && state.userLocation != null,
                    onClick = {
                        state.userLocation?.let { store.dispatch(MapAction.UpdateCenter(it)) }
                        store.dispatch(MapAction.SetCameraFollow(true))
                    },
                )
                // A task in play → open the in-flight ribbon (overview + retarget);
                // otherwise the list to pick/create one.
                TaskButton(onClick = {
                    if (state.selectedTaskId != null) showTaskRibbon = true
                    else showTaskListScreen = true
                })
                // (Mezulla view-mode now lives in the team sheet, opened from the buddies chip
                // next to the compass — not a standalone dock button.)
            }

            val taskPanelVisible = state.selectedTaskId != null && !showTaskListScreen &&
                !showEditWaypointScreen && !state.flightDeck.varioConnected &&
                !state.addingWaypoint

            // Dock: a fixed home at the top-right, stacked below the compass + its
            // readout, in both orientations. It never shares space with the bottom task
            // sheet, so there's no overlap to manage and no riding/hiding as the sheet
            // grows. (Landscape insets from the right-edge nav bar; portrait clears a bit
            // more below the compass.)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .then(
                        if (controlsLandscape)
                            Modifier
                                .padding(WindowInsets.systemBars.asPaddingValues())
                                .padding(top = 108.dp, end = 16.dp)
                        else
                            Modifier
                                .padding(WindowInsets.statusBars.asPaddingValues())
                                .padding(top = 120.dp, end = 16.dp)
                    ),
                verticalArrangement = Arrangement.spacedBy(if (controlsLandscape) 6.dp else 10.dp),
            ) { dockContent() }

            TaskDetailPanel(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(WindowInsets.navigationBars.asPaddingValues()),
                store = store,
                isVisible = taskPanelVisible,
                onDismiss = { store.dispatch(MapAction.DeselectTask) },
            )

            AnimatedVisibility(
                visible = showWelcome,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                WelcomeScreen(gpsStatus = gpsStatus)
            }

            // Pair-priming overlay.
            val activity = LocalContext.current.findActivity() as? com.ternparagliding.TernParaglidingActivity
            if (activity != null) {
                val pairingState by activity.pairingOrchestrator.state.collectAsState()
                val pairingLink by activity.pairingOrchestrator.currentLink.collectAsState()
                val showPriming = pairingState !is com.ternparagliding.mezulla.pairing.PairingState.Idle &&
                    pairingState !is com.ternparagliding.mezulla.pairing.PairingState.Success
                AnimatedVisibility(visible = showPriming, enter = fadeIn(), exit = fadeOut()) {
                    com.ternparagliding.mezulla.ui.PairingPrimingScreen(
                        state = pairingState,
                        link = pairingLink,
                    )
                }
            }
        }
    }


    if (showSettingsSheet) {
        val activity = LocalContext.current.findActivity() as? com.ternparagliding.TernParaglidingActivity
        SettingsSheet(
            onDismiss = { showSettingsSheet = false },
            store = store,
            demoReplay = demoReplay,
            pairingOrchestrator = activity?.pairingOrchestrator,
        )
    }

    if (showTaskRibbon && state.selectedTaskId != null) {
        TaskRibbonSheet(
            store = store,
            onDismiss = { showTaskRibbon = false },
            onManageTasks = {
                showTaskRibbon = false
                showTaskListScreen = true
            },
            onAddFromLibrary = {
                showTaskRibbon = false
                showWaypointPicker = true
            },
        )
    }

    if (showWaypointPicker) {
        // Add to the task being edited (B1) if open, else the selected task (ribbon).
        (editingTaskId ?: state.selectedTaskId)?.let { taskId ->
            WaypointPickerSheet(
                store = store,
                taskId = taskId,
                onDismiss = { showWaypointPicker = false },
            )
        }
    }

    if (showTaskListScreen) {
        TaskListScreen(
            store = store,
            onTaskSelected = { showTaskListScreen = false },
            onDismiss = { showTaskListScreen = false },
            onManageWaypoints = {
                showTaskListScreen = false
                showWaypointLibrary = true
            },
            onEditTask = { tid -> editingTaskId = tid }, // pencil → Workflow B1
        )
    }

    // Workflow B1 — Edit Task (name + ordered points + reorder). Over the task list.
    editingTaskId?.let { tid ->
        EditTaskScreen(
            taskId = tid,
            store = store,
            onEditPoint = { wpId -> store.dispatch(MapAction.SelectWaypoint(tid, wpId)) }, // → B2
            onAddFromLibrary = { showWaypointPicker = true },
            onDismiss = { editingTaskId = null },
        )
    }

    if (showWaypointLibrary) {
        WaypointLibraryScreen(
            store = store,
            onDismiss = { showWaypointLibrary = false },
            onEditWaypoint = { spot -> editingSpotId = spot.id },
        )
    }

    if (showEditWaypointScreen) {
        EditWaypointScreen(
            store = store,
            onEditSpot = { spotId -> editingSpotId = spotId }, // drill into Workflow A
            onDismiss = {
                showEditWaypointScreen = false
                store.dispatch(MapAction.DeselectWaypoint)
            }
        )
    }

    // Workflow A — waypoint identity editor. Rendered last so it overlays the per-point
    // editor (B2) and the library when drilled into from either.
    editingSpotId?.let { sid ->
        EditSpotScreen(spotId = sid, store = store, onDismiss = { editingSpotId = null })
    }

    // Smart First Waypoint Suggestion
    var showLaunchSuggestion by remember { mutableStateOf<com.ternparagliding.model.Waypoint?>(null) }

    LaunchedEffect(state.selectedTaskId) {
        val task = state.tasks.find { it.id == state.selectedTaskId }
        if (task != null && task.waypoints.isEmpty()) {
            // Find nearest launch from other tasks
            val center = state.center
            if (center != null) {
                val nearestLaunch = state.tasks.flatMap { it.waypoints }
                    .filter { it.type == com.ternparagliding.model.LocationType.LAUNCH }
                    .minByOrNull { calculateDistance(it.lat, it.lon, center.latitude, center.longitude) }

                if (nearestLaunch != null) {
                    val dist = calculateDistance(nearestLaunch.lat, nearestLaunch.lon, center.latitude, center.longitude)
                    if (dist < 50.0) { // Suggest if within 50km
                        showLaunchSuggestion = nearestLaunch
                    }
                }
            }
        }
    }

    if (showLaunchSuggestion != null) {
        AlertDialog(
            onDismissRequest = { showLaunchSuggestion = null },
            title = { Text("Add Start Point?") },
            text = { Text("Would you like to start at ${showLaunchSuggestion?.label ?: "Nearest Launch"}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedTaskId?.let { taskId ->
                            showLaunchSuggestion?.let { wp ->
                                store.dispatch(MapAction.AddWaypointToTask(
                                    taskId = taskId,
                                    lat = wp.lat,
                                    lon = wp.lon,
                                    type = com.ternparagliding.model.LocationType.LAUNCH,
                                    label = wp.label
                                ))
                            }
                        }
                        showLaunchSuggestion = null
                    }
                ) {
                    Text("Yes, Add Launch")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLaunchSuggestion = null }) {
                    Text("No")
                }
            }
        )
    }

    // Show weather details dialog when PG spot is tapped
    state.weatherState.showingWeatherDialog?.let { dialogState ->
        // A PG-spot id is "name|lat|lon"; a library-waypoint id is "wp|code|lat|lon".
        // The tapped coordinates are the last two "|"-separated fields.
        val selectedTaskId = state.selectedTaskId
        val isPgSpot = !dialogState.pgSpotId.startsWith("wp|")
        val idParts = dialogState.pgSpotId.split("|")
        val spotLat = idParts.getOrNull(idParts.size - 2)?.toDoubleOrNull()
        val spotLon = idParts.getOrNull(idParts.size - 1)?.toDoubleOrNull()

        // The library spot behind a "wp|code|…" tap → enables Edit (Workflow A) and
        // a library-style add-to-task.
        val libSpot = if (!isPgSpot) state.waypointLibrary.find { it.code == idParts.getOrNull(1) } else null

        val onEditWaypoint: (() -> Unit)? = libSpot?.let { spot ->
            {
                editingSpotId = spot.id
                store.dispatch(WeatherActions.DismissWeatherDetails)
            }
        }

        val addToTask: (() -> Unit)? = when {
            selectedTaskId == null -> null
            // PG spot → capture it as a PG_SPOT-provenance spot and reference it.
            isPgSpot && spotLat != null && spotLon != null -> {
                {
                    store.dispatch(MapAction.AddPgSpotToTask(
                        taskId = selectedTaskId,
                        pgSpotId = dialogState.pgSpotId,
                        code = dialogState.spotName,
                        name = dialogState.spotName,
                        lat = spotLat,
                        lon = spotLon,
                        alt = dialogState.siteContext?.elevationM,
                    ))
                    store.dispatch(WeatherActions.DismissWeatherDetails)
                }
            }
            // Library waypoint → reference the existing spot by id.
            libSpot != null -> {
                {
                    store.dispatch(MapAction.AddLibraryWaypointsToTask(selectedTaskId, listOf(libSpot.id)))
                    store.dispatch(WeatherActions.DismissWeatherDetails)
                }
            }
            else -> null
        }

        WeatherDetailsDialog(
            forecast = dialogState.forecast ?: state.weatherState.spotWeathers[dialogState.pgSpotId],
            spotName = dialogState.spotName,
            siteContext = dialogState.siteContext,
            units = com.ternparagliding.units.UnitPrefs(
                temperature = state.settingsState.temperatureUnit,
                speed = state.settingsState.speedUnit,
                distance = state.settingsState.distanceUnit,
                altitude = state.settingsState.altitudeUnit,
            ),
            isLoading = state.weatherState.fetchingSpots.contains(dialogState.pgSpotId),
            onAddToTask = addToTask,
            onEditWaypoint = onEditWaypoint,
            onDismiss = {
                store.dispatch(WeatherActions.DismissWeatherDetails)
            }
        )
    }
}

/**
 * Instruction banner shown while a waypoint is armed for moving (move-mode). Tells the
 * pilot to tap the map to place [name], with an explicit Cancel that restores the
 * original position. A pill so it reads as a transient mode, not a permanent control.
 */
@Composable
private fun MoveWaypointBanner(
    name: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Tap the map to move $name",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371 // Earth radius in km
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}
