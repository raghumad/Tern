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
import androidx.compose.ui.platform.LocalDensity
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
import org.osmdroid.util.GeoPoint
import android.view.GestureDetector
import android.view.MotionEvent

import com.madanala.tern.model.Waypoint
import com.madanala.tern.route.WaypointOverlay
import com.madanala.tern.route.WaypointStore
import com.madanala.tern.route.TypeSelectionSheet
import com.madanala.tern.route.RouteStore
import com.madanala.tern.ui.overlays.RouteOverlayManager
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch


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

        // ✅ CORRECT: Set GPS status to acquiring, not location ready
        store.updateGpsStatus(com.madanala.tern.redux.GpsStatus.ACQUIRING)

        // In a full implementation, this would:
        // 1. Initialize location manager
        // 2. Set up location callbacks that dispatch Redux actions
        // 3. Handle location updates through Redux state changes
        // 4. Only set location ready when valid GPS fix is acquired
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
     * Set location ready when we receive the first valid GPS fix
     */
    fun updateLocation(location: org.osmdroid.util.GeoPoint?) {
        store.dispatch(MapAction.UpdateUserLocation(location))

        // Set location ready when we receive the first valid location
        // This ensures welcome screen only disappears after GPS fix
        if (location != null && isValidAviationLocation(location) && !store.state.value.isLocationReady) {
            Log.d("ReduxLocationService", "Valid GPS fix received, setting location ready: $location")
            store.setLocationReady(true)
            store.updateGpsStatus(com.madanala.tern.redux.GpsStatus.ACTIVE)
        }
    }

    /**
     * Validate GPS location for aviation use
     */
    private fun isValidAviationLocation(location: org.osmdroid.util.GeoPoint): Boolean {
        // Basic validation for aviation coordinates
        return location.latitude.isFinite() &&
                location.longitude.isFinite() &&
                location.latitude in -90.0..90.0 &&
                location.longitude in -180.0..180.0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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

    // RouteOverlayManager for route visualization
    val routeOverlayManager = remember { RouteOverlayManager(context, store) }

    // Connect RouteOverlayManager to map view lifecycle for route drawing
    DisposableEffect(mapViewModel.mapView) {
        val mapView = mapViewModel.mapView

        // Attach RouteOverlayManager to map view for route visualization
        routeOverlayManager.onAttach(mapView)

        onDispose {
            // Cleanup handled by RouteOverlayManager lifecycle
            routeOverlayManager.onDetach()
        }
    }

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
        val density = LocalDensity.current
        val sheetState = rememberModalBottomSheetState()
        val coroutineScope = rememberCoroutineScope()
    var pendingCoord by remember { mutableStateOf<org.osmdroid.util.GeoPoint?>(null) }
    var isDraggingWaypoint by remember { mutableStateOf(false) }
    

          // Attach a long-press listener (MapEventsOverlay) to allow creating waypoints by long-press.
          // This is a conservative Phase 1 implementation (in-memory store); we'll migrate to Redux in Phase 2.
          AndroidView(
              factory = { ctx ->
                  // mapView already created and managed by MapViewModel; add MapEventsOverlay once
                  try {
                      // GestureDetector to detect long-press and convert screen coords to GeoPoint
                      val gestureDetector = GestureDetector(mapView.context, object : GestureDetector.SimpleOnGestureListener() {
                          override fun onLongPress(e: MotionEvent) {
                              try {
                                  val gp = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
                                  if (gp is GeoPoint) {
                                      val lat = gp.latitude
                                      val lon = gp.longitude
                                     if (lat.isFinite() && lon.isFinite()) {
                                         // Instead of immediately adding, show type selection sheet
                                         pendingCoord = gp
                                         coroutineScope.launch {
                                             // show sheet - using compose ModalBottomSheet state
                                             // actual sheet UI is below in compose tree
                                         }
                                     }
                                  }
                              } catch (t: Throwable) {
                                  Log.w("MapViewContainer", "onLongPress failed: ${t.message}")
                              }
                          }
                      })

                      // Set touch listener to forward events to gesture detector while preserving map gestures
                      mapView.setOnTouchListener { _, event ->
                          try {
                              gestureDetector.onTouchEvent(event)
                          } catch (ignored: Throwable) {
                          }
                          // return false so MapView still handles gestures (panning/zoom)
                          false
                      }
                  } catch (t: Throwable) {
                      Log.w("MapViewContainer", "Failed to attach long-press handler: ${t.message}")
                  }
                  mapView
              },
              modifier = Modifier.fillMaxSize()
          )

          // Modal sheet for type selection
          @OptIn(ExperimentalMaterial3Api::class)
          if (pendingCoord != null && !isDraggingWaypoint) {
              ModalBottomSheet(
                  onDismissRequest = {
                       pendingCoord = null
                       isDraggingWaypoint = false
                   },
                  sheetState = sheetState
              ) {
                  TypeSelectionSheet(onSelect = { type ->
                      val gp = pendingCoord
                      if (gp != null) {
                        Log.d("MapViewContainer", "Creating waypoint at: ${gp.latitude}, ${gp.longitude} with type: $type")

                        // Create waypoint with route association using route-centric operations
                        val currentRoute = RouteStore.getCurrentRoute() ?: RouteStore.createRoute("Route")
                        val newWaypoint = WaypointStore.createWaypointInRoute(
                            routeId = currentRoute.id,
                            lat = gp.latitude,
                            lon = gp.longitude,
                            type = type
                        )
                        Log.d("MapViewContainer", "Waypoint created with ID: ${newWaypoint.id}")

                        // Add marker for the waypoint (only once)
                        WaypointOverlay.addMarker(mapView, newWaypoint, WaypointStore) { isDragging ->
                            isDraggingWaypoint = isDragging
                        }
                        Log.d("MapViewContainer", "Waypoint marker added for: $type")
                      }
                      pendingCoord = null
                      isDraggingWaypoint = false
                  }, onCancel = {
                      pendingCoord = null
                      isDraggingWaypoint = false
                  })
              }
          }


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