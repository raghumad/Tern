package com.ternparagliding.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ternparagliding.redux.MapStore

/**
 * Handles location permission logic for the map view.
 * Encapsulates permission checking, requesting, and Redux state updates.
 */
@Composable
fun handleLocationPermissions(store: MapStore): Boolean {
    val context = LocalContext.current

    // Check initial permission status
    val initialPermissionGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    // Manage permission state
    var hasLocationPermission by remember {
        mutableStateOf(initialPermissionGranted)
    }

    // Initialize Redux state with current permission status
    LaunchedEffect(Unit) {
        store.setLocationPermission(initialPermissionGranted)
    }

    // Set up permission launcher
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

    // Request permissions if not granted
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    return hasLocationPermission
}
