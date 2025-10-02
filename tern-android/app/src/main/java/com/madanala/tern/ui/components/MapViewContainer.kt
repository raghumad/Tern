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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore

@Composable
fun MapViewContainer(
    modifier: Modifier = Modifier,
    store: MapStore = viewModel()
) {
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

    // TODO: Integrate Redux with location updates when ViewModel state is migrated
    // This will be updated when we migrate location logic to Redux

    // Phase 1: DisposableEffect for compose lifecycle
    val mapViewModel: MapViewModel = viewModel()

    DisposableEffect(Unit) {
        // Set up callbacks to Redux store (Phase 1 Redux migration)
        mapViewModel.rotationCallback = { rotation ->
            store.updateRotation(rotation)
        }
        mapViewModel.locationReadyCallback = { ready ->
            store.dispatch(MapAction.SetLocationReady(ready))
        }
        onDispose {
            // Redux store cleanup handled by ViewModel lifecycle
            mapViewModel.rotationCallback = null
            mapViewModel.locationReadyCallback = null
        }
    }

    // Location updates - start when permission granted
    // TODO: Migrate this to Redux in future phase
    LaunchedEffect(state.hasLocationPermission) {
        if (state.hasLocationPermission) {
            mapViewModel.startLocationUpdates()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // TODO: MapView will be managed differently in Redux architecture
        // For now, keeping ViewModel for MapView instance management
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
