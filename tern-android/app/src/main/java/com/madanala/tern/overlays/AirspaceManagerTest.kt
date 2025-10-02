package com.madanala.tern.overlays

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType

/**
 * Test component to demonstrate AirspaceOverlayManager integration with Redux
 * Shows how to connect overlay manager to Redux state for visibility toggling
 */

/**
 * Test component to demonstrate AirspaceOverlayManager integration with Redux
 * Shows how to connect overlay manager to Redux state for visibility toggling
 */
@Composable
fun AirspaceManagerTest(
    store: MapStore = viewModel(),
    mapViewModel: com.madanala.tern.ui.components.MapViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by store.state.collectAsState()

    var airspaceManager by remember { mutableStateOf<AirspaceOverlayManager?>(null) }

    // Initialize airspace manager when MapView is ready
    LaunchedEffect(mapViewModel.mapView) {
        val manager = AirspaceOverlayManager(
            applicationContext = context.applicationContext,
            mapStore = store
        )

        // Attach to the existing MapView
        manager.onAttach(mapViewModel.mapView)

        airspaceManager = manager

        Log.d("AirspaceManagerTest", "AirspaceOverlayManager attached to MapView")
    }

    Column {
        Text(text = "Airspace Overlay Manager Test")

        // Display current airspace state from Redux
        Text(text = "Airspaces Enabled: ${state.overlayState.airspaces.enabled}")

        // Button to toggle airspaces via Redux
        Button(onClick = {
            val currentlyEnabled = state.overlayState.airspaces.enabled
            store.dispatch(MapAction.SetOverlayEnabled(OverlayType.AIRSPACE, !currentlyEnabled))
            Log.d("AirspaceManagerTest", "Toggled airspaces via Redux: ${!currentlyEnabled}")
        }) {
            Text(if (state.overlayState.airspaces.enabled) "Disable Airspaces" else "Enable Airspaces")
        }

        // Button to clear cache and force reload
        Button(onClick = {
            airspaceManager?.let { manager ->
                val stats = manager.getCacheStats()
                Log.d("AirspaceManagerTest", "Cache stats: $stats")
            }
        }) {
            Text("Log Cache Stats")
        }
    }
}
