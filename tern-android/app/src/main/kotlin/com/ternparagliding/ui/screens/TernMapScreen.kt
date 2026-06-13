package com.ternparagliding.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ternparagliding.ui.components.*
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.WeatherActions
import kotlinx.coroutines.launch
import com.ternparagliding.mezulla.demo.AravisDemoReplay
import com.ternparagliding.mezulla.ui.SosAlertBanner
import com.ternparagliding.mezulla.ui.MezullaViewModeButton
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
    var showShareSheet by remember { mutableStateOf(false) }
    var showEditWaypointScreen by remember { mutableStateOf(false) }
    var showRouteListScreen by remember { mutableStateOf(false) }
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

    // Show edit waypoint screen when waypoint is selected
    LaunchedEffect(state.selectedWaypoint) {
        showEditWaypointScreen = state.selectedWaypoint != null
    }

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

            // Right-edge controls: settings, share, route, and Mezulla view mode
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .align(Alignment.CenterEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsButton(onClick = { showSettingsSheet = true })
                ShareButton(onClick = { showShareSheet = true })
                RouteButton(onClick = { showRouteListScreen = true })
                MezullaViewModeButton(
                    viewMode = state.mezullaViewMode,
                    linkState = state.peerState.linkState,
                    onCycle = { store.dispatch(MapAction.CycleMezullaViewMode) },
                )
            }

            RouteDetailPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                store = store,
                isVisible = state.selectedRouteId != null && !showRouteListScreen && !showEditWaypointScreen,
                onDismiss = { store.dispatch(MapAction.DeselectRoute) }
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

    if (showShareSheet) {
        ShareSheet(onDismiss = { showShareSheet = false })
    }

    if (showRouteListScreen) {
        RouteListScreen(
            store = store,
            onRouteSelected = { showRouteListScreen = false },
            onDismiss = { showRouteListScreen = false }
        )
    }

    if (showEditWaypointScreen) {
        EditWaypointScreen(
            store = store,
            onDismiss = { 
                showEditWaypointScreen = false
                store.dispatch(MapAction.DeselectWaypoint)
            }
        )
    }

    // Smart First Waypoint Suggestion
    var showLaunchSuggestion by remember { mutableStateOf<com.ternparagliding.model.Waypoint?>(null) }

    LaunchedEffect(state.selectedRouteId) {
        val route = state.routes.find { it.id == state.selectedRouteId }
        if (route != null && route.waypoints.isEmpty()) {
            // Find nearest launch from other routes
            val center = state.center
            if (center != null) {
                val nearestLaunch = state.routes.flatMap { it.waypoints }
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
                        state.selectedRouteId?.let { routeId ->
                            showLaunchSuggestion?.let { wp ->
                                store.dispatch(MapAction.AddWaypointToRoute(
                                    routeId = routeId,
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
            onDismiss = {
                store.dispatch(WeatherActions.DismissWeatherDetails)
            }
        )
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
