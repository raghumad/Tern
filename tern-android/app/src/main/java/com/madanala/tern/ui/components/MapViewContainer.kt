package com.madanala.tern.ui.components
import com.madanala.tern.model.LocationType

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
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.MapAction
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.ui.components.Compass
import com.madanala.tern.ui.components.RoutePlanningHUD

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

// UI Constants
private val COMPASS_PADDING = 16.dp

// Route Planning Constants
// Route Planning Constants
// private const val DEFAULT_ROUTE_NAME_PREFIX = "Route"
// private const val WAYPOINT_LABEL_PREFIX = "WP"
// private const val WAYPOINT_LABEL_SEPARATOR = "-"
// private const val FIRST_ROUTE_INDEX = 1

@Composable
fun MapViewContainer(
    modifier: Modifier = Modifier,
    store: MapStore = viewModel()
) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
    val hasLocationPermission = handleLocationPermissions(store)

    // Register Middleware
    LaunchedEffect(store) {
        store.addMiddleware(com.madanala.tern.redux.MapMiddleware(context.applicationContext))
        store.addMiddleware(com.madanala.tern.redux.RoutePlanningMiddleware(context.applicationContext))
        store.addMiddleware(com.madanala.tern.redux.WeatherMiddleware())
    }


    // Core components
    val mapViewModel: MapViewModel = viewModel()

    // Smart Waypoint Creation State (Managed by Redux)
    val smartSuggestionState = state.smartSuggestionState
    val nearbyPGSpot = smartSuggestionState.nearbyPGSpot
    val pendingWaypointCreation = smartSuggestionState.pendingWaypointCreation
    val coroutineScope = rememberCoroutineScope()

    // gestureHandler definition follows...
    val gestureHandler = remember {
        MapGestureHandler(
            context,
            store,
            onLongPress = { geoPoint ->
                // Check if we are adding to an existing route
                val selectedRouteId = store.state.value.selectedRouteId
                
                if (selectedRouteId != null) {
                    // Route selected -> Add directly (skip smart suggestion)
                    // Route selected -> Add directly (skip smart suggestion)
                    store.dispatch(MapAction.LongPressMap(geoPoint))
                } else {
                    // No route selected -> New route -> Try Smart Suggestion
                    mapViewModel.checkForSmartSuggestion(geoPoint)
                }
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

    // Smart Waypoint Dialog
    if (nearbyPGSpot != null && pendingWaypointCreation != null) {
        val spotName = nearbyPGSpot?.feature?.get("properties")?.let { props ->
            (props as? Map<*, *>)?.get("name") as? String
        } ?: "Unknown Spot"
        
        val spotType = nearbyPGSpot?.feature?.get("properties")?.let { props ->
            (props as? Map<*, *>)?.get("siteType") as? String
        } ?: "Launch"

        AlertDialog(
            onDismissRequest = {
                // Dismiss: Create at original clicked location
                pendingWaypointCreation?.let { geoPoint ->
                    store.dispatch(MapAction.LongPressMap(geoPoint))
                }
                mapViewModel.clearSmartSuggestionState()
            },
            title = { Text("Nearby ${spotType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }}") },
            text = { Text("Found nearby paragliding spot: \"$spotName\".\n\nDo you want to use this spot as your waypoint?") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        // Confirm: Create using PG spot location and type
                        nearbyPGSpot?.let { feature ->
                            val centroid = feature.centroid
                            val type = if (spotType.equals("landing", ignoreCase = true)) LocationType.LANDING else LocationType.LAUNCH
                            store.dispatch(MapAction.LongPressMap(centroid, type, spotName))
                        }
                        mapViewModel.clearSmartSuggestionState()
                    }
                ) {
                    Text("Use Spot")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        // Dismiss: Create at original clicked location
                        pendingWaypointCreation?.let { geoPoint ->
                            store.dispatch(MapAction.LongPressMap(geoPoint))
                        }
                        mapViewModel.clearSmartSuggestionState()
                    }
                ) {
                    Text("Use Clicked Location")
                }
            }
        )
    }

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

        // 🎯 RFC 005: High-Fidelity Marker Overlay (Animations & Semantics)
        // Renders markers as real Compose elements on top of the MapView
        // to support active animations (hazard pulse/flash) and testability.
        state.routes.filter { it.isVisible }.forEach { route ->
            route.waypoints.forEach { waypoint ->
                val forecast = state.weatherState.waypointWeathers[waypoint.id]
                val isSelected = state.selectedWaypoint?.let { it.routeId == route.id && it.waypointId == waypoint.id } ?: false
                
                // Calculate screen position based on projection
                // [STABILITY FIX] Use remember to avoid excessive projection lookups
                val screenPoint = remember(waypoint.lat, waypoint.lon, state.center, state.zoom, state.rotation) {
                    try {
                        val point = GeoPoint(waypoint.lat, waypoint.lon)
                        mapView.projection.toPixels(point, null)
                    } catch (e: Exception) {
                        null
                    }
                }

                screenPoint?.let { p ->
                    val density = LocalDensity.current
                    val markerSizePx = with(density) { 56.dp.toPx() }
                    
                    Box(
                        modifier = Modifier.offset {
                            IntOffset(
                                x = p.x - (markerSizePx / 2).toInt(),
                                y = p.y - (markerSizePx / 2).toInt()
                            )
                        }
                    ) {
                        LocationMarker(
                            location = waypoint,
                            zoom = state.zoom,
                            forecast = forecast,
                            isSelected = isSelected,
                            onClick = {
                                store.dispatch(MapAction.SelectWaypoint(route.id, waypoint.id))
                            }
                        )
                    }
                }
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

        // Show Route Planning HUD if a route is selected
        if (state.selectedRouteId != null) {
            RoutePlanningHUD(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(16.dp)
            )
        }
    }
}


// Helper to trigger the check
private const val SMART_SUGGESTION_RADIUS_MILES = 15.5 // ~25km

// handleWaypointCreationRequest moved to MapViewModel.checkForSmartSuggestion

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

// Legacy createWaypointAtLocation and helpers removed - Migrated to MapReducers (LongPressMap action)
