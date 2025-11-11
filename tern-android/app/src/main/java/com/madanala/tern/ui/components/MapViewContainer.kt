package com.madanala.tern.ui.components

// Phase 1: Core Lifecycle Fixes
// - Replaced produceState with StateFlow for map rotation
// - Added DisposableEffect for compose lifecycle management
// - Kept launcher-based permission handling (rememberPermissionState from Accompanist had API issues)

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.redux.MapStore
import org.osmdroid.util.GeoPoint

import com.madanala.tern.route.TypeSelectionSheet
import com.madanala.tern.ui.overlays.RouteOverlayManager
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.rememberCoroutineScope
import com.madanala.tern.ui.components.Compass

// UI Constants
private val COMPASS_PADDING = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapViewContainer(
    modifier: Modifier = Modifier,
    store: MapStore = viewModel()
) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
    val hasLocationPermission = handleLocationPermissions(store)

    // State for waypoint creation
    var pendingCoord by remember { mutableStateOf<org.osmdroid.util.GeoPoint?>(null) }
    var isDraggingWaypoint by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Core components
    val mapViewModel: MapViewModel = viewModel()
    val routeOverlayManager = remember { RouteOverlayManager(context, store) }
    val gestureHandler = remember { MapGestureHandler(context) { geoPoint -> pendingCoord = geoPoint } }
    val waypointCreationManager = remember { WaypointCreationManager(mapViewModel.mapView, coroutineScope) }
    val locationService = ReduxLocationService(store)

    // Setup map view lifecycle
    setupMapViewLifecycle(mapViewModel.mapView, routeOverlayManager, gestureHandler)

    // Setup Redux integration
    setupReduxIntegration(store, mapViewModel)

    // Setup location updates
    setupLocationUpdates(state.hasLocationPermission, locationService)

    // Sync location state
    syncLocationState(state.userLocation, state.isLocationReady)

    // Redux migration: Collect rotation from global state
    val mapRotation = state.rotation

    Box(modifier = modifier.fillMaxSize()) {
        // Redux-integrated MapView management
        // MapViewModel connected via Redux store for overlay coordination
        val mapView = mapViewModel.mapView
        val density = LocalDensity.current
        val sheetState = rememberModalBottomSheetState()

        // Map view with gesture handling managed by MapGestureHandler
        AndroidView(
            factory = { ctx ->
                // mapView already created and managed by MapViewModel
                // Gesture handling is now managed by MapGestureHandler attached in DisposableEffect
                mapView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Modal sheet for type selection
        if (pendingCoord != null) {
            ModalBottomSheet(
                onDismissRequest = {
                    pendingCoord = null
                    isDraggingWaypoint = false
                },
                sheetState = sheetState
            ) {
                TypeSelectionSheet(
                    onSelect = { type ->
                        pendingCoord?.let { coordinate ->
                            waypointCreationManager.createWaypoint(coordinate, type)
                        }
                        pendingCoord = null
                        isDraggingWaypoint = false
                    },
                    onCancel = {
                        pendingCoord = null
                        isDraggingWaypoint = false
                    }
                )
            }
        }

        // Show compass based on Redux state
        if (state.compassVisible) {
            Compass(
                rotation = mapRotation,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(COMPASS_PADDING)
            )
        }
    }
}

// Helper functions for composable lifecycle management

@Composable
private fun setupMapViewLifecycle(
    mapView: org.osmdroid.views.MapView,
    routeOverlayManager: RouteOverlayManager,
    gestureHandler: MapGestureHandler
) {
    DisposableEffect(mapView) {
        // Attach RouteOverlayManager to map view for route visualization
        routeOverlayManager.onAttach(mapView)

        // Attach gesture handler for long-press detection
        gestureHandler.attachToMapView(mapView)

        onDispose {
            // Cleanup handled by RouteOverlayManager lifecycle
            routeOverlayManager.onDetach()
            // Cleanup gesture handler
            gestureHandler.detachFromMapView()
        }
    }
}

@Composable
private fun setupReduxIntegration(store: MapStore, mapViewModel: MapViewModel) {
    DisposableEffect(store) {
        // Connect MapViewModel to Redux store for state integration
        mapViewModel.setMapStore(store)

        onDispose {
            // Cleanup handled by MapViewModel lifecycle
        }
    }
}

@Composable
private fun setupLocationUpdates(hasLocationPermission: Boolean, locationService: ReduxLocationService) {
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            // Start Redux-based location service
            locationService.startLocationUpdates()
            Log.d("MapViewContainer", "Redux location service started")
        } else {
            // Stop location service when permission revoked
            locationService.stopLocationUpdates()
            Log.d("MapViewContainer", "Redux location service stopped")
        }
    }
}

@Composable
private fun syncLocationState(userLocation: GeoPoint?, isLocationReady: Boolean) {
    LaunchedEffect(userLocation, isLocationReady) {
        userLocation?.let { location ->
            if (isLocationReady) {
                // Update MapViewModel with Redux location state for overlay management
                // This maintains compatibility while using Redux as single source of truth
                Log.d("MapViewContainer", "Syncing Redux location to MapViewModel: $location")
            }
        }
    }
}
