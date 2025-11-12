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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.MapAction
import com.madanala.tern.route.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.ui.components.Compass
import org.osmdroid.util.GeoPoint

// UI Constants
private val COMPASS_PADDING = 16.dp

// Route Planning Constants
private const val DEFAULT_ROUTE_NAME_PREFIX = "Route"
private const val WAYPOINT_LABEL_PREFIX = "WP"
private const val WAYPOINT_LABEL_SEPARATOR = "-"
private const val FIRST_ROUTE_INDEX = 1

@Composable
fun MapViewContainer(
    modifier: Modifier = Modifier,
    store: MapStore = viewModel()
) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
    val hasLocationPermission = handleLocationPermissions(store)

    // Core components
    val mapViewModel: MapViewModel = viewModel()
    val gestureHandler = remember {
        MapGestureHandler(
            context,
            onLongPress = { geoPoint ->
                // Handle waypoint creation for route planning
                handleWaypointCreation(store, geoPoint)
            }
        )
    }
    val locationService = ReduxLocationService(store)

    // Setup map view lifecycle
    setupMapViewLifecycle(mapViewModel.mapView, gestureHandler)

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

        // Map view with gesture handling managed by MapGestureHandler
        AndroidView(
            factory = { ctx ->
                // mapView already created and managed by MapViewModel
                // Gesture handling is now managed by MapGestureHandler attached in DisposableEffect
                mapView
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("map_view")
        )

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
    gestureHandler: MapGestureHandler
) {
    DisposableEffect(mapView) {
        // Attach gesture handler for long-press detection
        gestureHandler.attachToMapView(mapView)

        onDispose {
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

/**
 * Handle waypoint creation for route planning
 * Multi-waypoint routes: Adds to most recent route, or creates new route if none exist
 */
private fun handleWaypointCreation(store: MapStore, geoPoint: GeoPoint) {
    try {
        Log.d("MapViewContainer", "Long press detected at: ${geoPoint.latitude}, ${geoPoint.longitude}")

        val currentState = store.state.value
        Log.d("MapViewContainer", "Current routes count: ${currentState.routes.size}")

        if (currentState.routes.isEmpty()) {
            // No routes exist - create a new route with the first waypoint
            val newRoute = createFirstRoute(geoPoint)

            Log.d("MapViewContainer", "Creating first route: ${newRoute.name}")
            store.dispatch(MapAction.AddRoute(newRoute))
            Log.d("MapViewContainer", "Created new route: ${newRoute.name} at ${geoPoint.latitude}, ${geoPoint.longitude}")
        } else {
            // Routes exist - add waypoint to the most recent route
            addWaypointToMostRecentRoute(store, geoPoint, currentState.routes)
        }

    } catch (e: Exception) {
        Log.e("MapViewContainer", "Error creating waypoint", e)
    }
}

/**
 * Create the first route with a single waypoint
 */
private fun createFirstRoute(geoPoint: GeoPoint): Route {
    val waypointLabel = "$WAYPOINT_LABEL_PREFIX$FIRST_ROUTE_INDEX$WAYPOINT_LABEL_SEPARATOR$FIRST_ROUTE_INDEX"
    val routeName = "$DEFAULT_ROUTE_NAME_PREFIX $FIRST_ROUTE_INDEX"

    return Route(
        name = routeName,
        waypoints = listOf(
            Waypoint(
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = Waypoint.Type.TURNPOINT,
                label = waypointLabel
            )
        )
    )
}

/**
 * Add a waypoint to the most recently created route
 */
private fun addWaypointToMostRecentRoute(store: MapStore, geoPoint: GeoPoint, routes: List<Route>) {
    val mostRecentRoute = routes.maxByOrNull { it.createdAt }
    if (mostRecentRoute != null) {
        val waypointNumber = mostRecentRoute.waypoints.size + 1
        val routeIndex = routes.indexOf(mostRecentRoute) + 1
        val label = "$WAYPOINT_LABEL_PREFIX$routeIndex$WAYPOINT_LABEL_SEPARATOR$waypointNumber"

        Log.d("MapViewContainer", "Adding waypoint to route: ${mostRecentRoute.name} (${mostRecentRoute.waypoints.size} existing waypoints)")
        store.dispatch(MapAction.AddWaypointToRoute(
            routeId = mostRecentRoute.id,
            lat = geoPoint.latitude,
            lon = geoPoint.longitude,
            type = Waypoint.Type.TURNPOINT,
            label = label
        ))
        Log.d("MapViewContainer", "Added waypoint $label to route: ${mostRecentRoute.name}")
    }
}
