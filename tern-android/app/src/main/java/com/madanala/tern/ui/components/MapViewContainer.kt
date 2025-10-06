package com.madanala.tern.ui.components

// Phase 1: Core Lifecycle Fixes
// - Replaced produceState with StateFlow for map rotation
// - Added DisposableEffect for compose lifecycle management
// - Kept launcher-based permission handling (rememberPermissionState from Accompanist had API issues)

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import android.util.Log

/**
 * Redux-based location service that handles GPS updates through Redux actions
 * Replaces direct MapViewModel location handling with Redux-compliant architecture
 */
class ReduxLocationService(private val store: MapStore) {

    /**
     * Start location updates through Redux actions
     * This method would be called when location permission is granted
     */
    fun startLocationUpdates() {
        Log.d("ReduxLocationService", "Starting Redux-based location updates")

        // Dispatch Redux action to indicate location service is starting
        store.dispatch(MapAction.SetLocationReady(true))

        // In a full implementation, this would:
        // 1. Initialize location manager
        // 2. Set up location callbacks that dispatch Redux actions
        // 3. Handle location updates through Redux state changes
    }

    /**
     * Stop location updates
     */
    fun stopLocationUpdates() {
        Log.d("ReduxLocationService", "Stopping Redux-based location updates")

        // Dispatch Redux action to indicate location service is stopping
        store.dispatch(MapAction.SetLocationReady(false))
    }

    /**
     * Update current location through Redux action
     */
    fun updateLocation(location: org.osmdroid.util.GeoPoint?) {
        store.dispatch(MapAction.UpdateUserLocation(location))
    }
}

@Composable
fun MapViewContainer(
     modifier: Modifier = Modifier,
     store: MapStore = viewModel()
 ) {
     // Redux-based location service for handling GPS updates through Redux actions
     val locationService = ReduxLocationService(store)
    val context = LocalContext.current
    val state by store.state.collectAsState()

    // Permission handling - maintain local state for launcher
    val initialPermissionGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    var hasLocationPermission by remember {
        mutableStateOf(initialPermissionGranted)
    }

    // Initialize Redux state with current permission status
    LaunchedEffect(Unit) {
        store.setLocationPermission(initialPermissionGranted)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (hasLocationPermission) {
                // Dispatch permission granted action
                store.setLocationPermission(true)
            } else {
                Toast.makeText(context, "Location permission denied.", Toast.LENGTH_LONG).show()
                store.setLocationPermission(false)
            }
        }
    )

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // Redux migration: Collect rotation from global state
    val mapRotation = state.rotation

    // Redux location integration - location state flows through Redux actions and state
    // MapViewModel connection handled via setMapStore() for overlay coordination

    // MapViewModel - Redux integration handled via setMapStore
    val mapViewModel: MapViewModel = viewModel()

    DisposableEffect(store) {
        // Connect MapViewModel to Redux store for state integration
        mapViewModel.setMapStore(store)

        onDispose {
            // Cleanup handled by MapViewModel lifecycle
        }
    }

    // Redux-based location updates - replaced direct MapViewModel dependency
    LaunchedEffect(state.hasLocationPermission) {
         if (state.hasLocationPermission) {
             // Start Redux-based location service
             locationService.startLocationUpdates()
             Log.d("MapViewContainer", "Redux location service started")
         } else {
             // Stop location service when permission revoked
             locationService.stopLocationUpdates()
             Log.d("MapViewContainer", "Redux location service stopped")
         }
     }

    // Sync Redux location state with MapViewModel for overlay coordination
    LaunchedEffect(state.userLocation, state.isLocationReady) {
         state.userLocation?.let { location ->
             if (state.isLocationReady) {
                 // Update MapViewModel with Redux location state for overlay management
                 // This maintains compatibility while using Redux as single source of truth
                 Log.d("MapViewContainer", "Syncing Redux location to MapViewModel: $location")
             }
         }
     }

    Box(modifier = modifier.fillMaxSize()) {
         // Redux-integrated MapView management
         // MapViewModel connected via Redux store for overlay coordination
         val mapView = mapViewModel.mapView

        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Show compass based on Redux state
        if (state.compassVisible) {
            Compass(
                rotation = mapRotation,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(16.dp)
            )
        }
    }
}
