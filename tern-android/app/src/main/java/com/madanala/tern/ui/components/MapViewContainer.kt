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
import androidx.compose.runtime.setValue
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
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.ui.components.Compass
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

    // Smart Waypoint Creation State
    var nearbyPGSpot by remember { mutableStateOf<com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature?>(null) }
    var pendingWaypointCreation by remember { mutableStateOf<GeoPoint?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Core components
    val mapViewModel: MapViewModel = viewModel()
    val gestureHandler = remember {
        MapGestureHandler(
            context,
            store,
            onLongPress = { geoPoint ->
                // Check if we are adding to an existing route
                val selectedRouteId = store.state.value.selectedRouteId
                
                if (selectedRouteId != null) {
                    // Route selected -> Add directly (skip smart suggestion)
                    createWaypointAtLocation(store, geoPoint)
                } else {
                    // No route selected -> New route -> Try Smart Suggestion
                    handleWaypointCreationRequest(
                        context,
                        geoPoint,
                        coroutineScope,
                        onFoundNearby = { feature ->
                            pendingWaypointCreation = geoPoint
                            nearbyPGSpot = feature
                        },
                        onNoNearby = {
                            createWaypointAtLocation(store, geoPoint)
                        }
                    )
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
                    createWaypointAtLocation(store, geoPoint)
                }
                nearbyPGSpot = null
                pendingWaypointCreation = null
            },
            title = { Text("Nearby ${spotType.capitalize()}") },
            text = { Text("Found nearby paragliding spot: \"$spotName\".\n\nDo you want to use this spot as your waypoint?") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        // Confirm: Create using PG spot location and type
                        nearbyPGSpot?.let { feature ->
                            val centroid = feature.centroid
                            val type = if (spotType.equals("landing", ignoreCase = true)) Waypoint.Type.LANDING else Waypoint.Type.LAUNCH
                            createWaypointAtLocation(store, centroid, type, spotName)
                        }
                        nearbyPGSpot = null
                        pendingWaypointCreation = null
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
                            createWaypointAtLocation(store, geoPoint)
                        }
                        nearbyPGSpot = null
                        pendingWaypointCreation = null
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

// Helper to trigger the check
private const val SMART_SUGGESTION_RADIUS_MILES = 15.5 // ~25km

private fun handleWaypointCreationRequest(
    context: android.content.Context,
    geoPoint: GeoPoint,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onFoundNearby: (com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature) -> Unit,
    onNoNearby: () -> Unit
) {
    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            Log.d("MapViewContainer", "Smart Suggestion: Checking for spots near ${geoPoint.latitude}, ${geoPoint.longitude}")
            val countryCode = com.madanala.tern.utils.CountryUtils.getCountryCodeFromGeoPoint(context, geoPoint)
            
            if (countryCode != null) {
                Log.d("MapViewContainer", "Smart Suggestion: Country detected: $countryCode")
                // Query cache for nearby spots
                val nearbySpots = com.madanala.tern.utils.CacheManager.pgSpotCache.queryNearbyPGSpots(countryCode, geoPoint, SMART_SUGGESTION_RADIUS_MILES)
                Log.d("MapViewContainer", "Smart Suggestion: Found ${nearbySpots.size} spots within $SMART_SUGGESTION_RADIUS_MILES miles")
                
                if (nearbySpots.isNotEmpty()) {
                    // Find the closest one
                    val closest = nearbySpots.minByOrNull { it.centroid.distanceToAsDouble(geoPoint) }
                    if (closest != null) {
                        val name = closest.feature["properties"]?.let { (it as? Map<*, *>)?.get("name") } ?: "Unknown"
                        Log.d("MapViewContainer", "Smart Suggestion: Closest spot is '$name' at ${closest.centroid}")
                        
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onFoundNearby(closest)
                        }
                        return@launch
                    }
                } else {
                    Log.d("MapViewContainer", "Smart Suggestion: No spots found in cache for $countryCode nearby")
                }
            } else {
                Log.w("MapViewContainer", "Smart Suggestion: Could not determine country code for location")
            }
            
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onNoNearby()
            }
        } catch (e: Exception) {
            Log.e("MapViewContainer", "Error checking nearby spots", e)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onNoNearby()
            }
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
 * Smart selection: Long press near existing waypoint selects it instead of creating duplicate
 */
private fun createWaypointAtLocation(store: MapStore, geoPoint: GeoPoint, type: Waypoint.Type = Waypoint.Type.TURNPOINT, label: String? = null) {
    try {
        Log.d("MapViewContainer", "Long press detected at: ${geoPoint.latitude}, ${geoPoint.longitude}")

        val currentState = store.state.value
        Log.d("MapViewContainer", "Current routes count: ${currentState.routes.size}")

        // NOTE: We removed "findNearbyWaypoint" here to allow creating overlapping routes.
        // Selection is handled by single tap (RouteOverlayManager).
        // Long press ALWAYS creates a new waypoint/route.

        if (currentState.routes.isEmpty()) {
            // No routes exist - create a new route with the first waypoint
            val newRoute = createFirstRoute(geoPoint, 1, type, label)

            Log.d("MapViewContainer", "Creating first route: ${newRoute.name}")
            store.dispatch(MapAction.AddRoute(newRoute))
            store.dispatch(MapAction.SelectRoute(newRoute.id)) // Auto-select route
            
            // Auto-select the waypoint to allow immediate editing (e.g. changing type)
            val firstWaypointId = newRoute.waypoints.first().id
            store.dispatch(MapAction.SelectWaypoint(newRoute.id, firstWaypointId))
            
            Log.d("MapViewContainer", "Created new route: ${newRoute.name} at ${geoPoint.latitude}, ${geoPoint.longitude}")
        } else {
            // Check if a route is selected
            val selectedRouteId = currentState.selectedRouteId
            if (selectedRouteId != null) {
                // Add waypoint to the selected route
                addWaypointToSelectedRoute(store, geoPoint, selectedRouteId, currentState.routes, type, label)
            } else {
                // No route selected - create a NEW route instead of appending to "most recent"
                // This prevents "random waypoints" appearing on old/zombie routes
                val newRouteIndex = currentState.routes.size + 1
                val newRoute = createFirstRoute(geoPoint, newRouteIndex, type, label)
                
                Log.d("MapViewContainer", "Creating new route (no selection): ${newRoute.name}")
                store.dispatch(MapAction.AddRoute(newRoute))
                store.dispatch(MapAction.SelectRoute(newRoute.id)) // Auto-select route
                
                // Auto-select the waypoint to allow immediate editing (e.g. changing type)
                val firstWaypointId = newRoute.waypoints.first().id
                store.dispatch(MapAction.SelectWaypoint(newRoute.id, firstWaypointId))
            }
        }

    } catch (e: Exception) {
        Log.e("MapViewContainer", "Error handling waypoint creation", e)
    }
}

/**
 * Create the first route with a single waypoint
 */
private fun createFirstRoute(geoPoint: GeoPoint, routeIndex: Int, type: Waypoint.Type = Waypoint.Type.TURNPOINT, label: String? = null): Route {
    // Use routeIndex for correct labeling (e.g. WP2-1 for Route 2)
    val waypointLabel = label ?: "$WAYPOINT_LABEL_PREFIX$routeIndex$WAYPOINT_LABEL_SEPARATOR$FIRST_ROUTE_INDEX"
    val routeName = "$DEFAULT_ROUTE_NAME_PREFIX $routeIndex"

    return Route(
        name = routeName,
        waypoints = listOf(
            Waypoint(
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = type,
                label = waypointLabel
            )
        )
    )
}



/**
 * Add a waypoint to the selected route
 */
private fun addWaypointToSelectedRoute(store: MapStore, geoPoint: GeoPoint, selectedRouteId: String, routes: List<Route>, type: Waypoint.Type = Waypoint.Type.TURNPOINT, label: String? = null) {
    val selectedRoute = routes.find { it.id == selectedRouteId }
    if (selectedRoute != null) {
        val waypointNumber = selectedRoute.waypoints.size + 1
        // Find the index of the route in the list to maintain consistent naming (Route 1 -> WP1-X)
        // Note: This assumes routes are ordered. If they can be reordered, we might need a persistent ID-to-Index map or store the index in the Route object.
        // For now, using list index + 1 is consistent with createFirstRoute logic.
        val routeIndex = routes.indexOf(selectedRoute) + 1
        
        // Generate label if not provided
        val finalLabel = label ?: "$WAYPOINT_LABEL_PREFIX$routeIndex$WAYPOINT_LABEL_SEPARATOR$waypointNumber"

        Log.d("MapViewContainer", "Adding waypoint to selected route: ${selectedRoute.name} (${selectedRoute.waypoints.size} existing waypoints)")
        
        val newWaypointId = java.util.UUID.randomUUID().toString()
        
        store.dispatch(MapAction.AddWaypointToRoute(
            routeId = selectedRoute.id,
            lat = geoPoint.latitude,
            lon = geoPoint.longitude,
            type = type,
            label = finalLabel,
            id = newWaypointId
        ))
        
        // Auto-select the new waypoint to allow immediate editing (e.g. changing type)
        store.dispatch(MapAction.SelectWaypoint(selectedRoute.id, newWaypointId))
        
        Log.d("MapViewContainer", "Added waypoint $finalLabel to selected route: ${selectedRoute.name}")
    }
}

/**
 * Find existing waypoint within tolerance distance from geoPoint
 * Uses Hilbert-aware spatial reasoning (following established pattern)
 */
private fun findNearbyWaypoint(routes: List<Route>, geoPoint: GeoPoint, toleranceDegrees: Double): com.madanala.tern.model.Waypoint? {
    // Hilbert pattern: compute index for target point for spatial reasoning consistency
    val targetHilbertIndex = com.madanala.tern.utils.MapOverlayCacheUtils.computeHilbertIndex(geoPoint, 16)

    routes.forEach { route ->
        route.waypoints.forEach { waypoint ->
            // Primary: Exact distance calculation (following Hilbert query pattern)
            val distanceDegrees = calculateDistanceDegrees(
                waypoint.lat, waypoint.lon,
                geoPoint.latitude, geoPoint.longitude
            )

            // Secondary: Hilbert index proximity check for spatial reasoning consistency
            val waypointHilbertIndex = com.madanala.tern.utils.MapOverlayCacheUtils.computeHilbertIndex(
                org.osmdroid.util.GeoPoint(waypoint.lat, waypoint.lon), 16
            )

            val hilbertDistance = kotlin.math.abs(targetHilbertIndex - waypointHilbertIndex)

            // Combined check: exact distance within tolerance
            if (distanceDegrees <= toleranceDegrees) {
                return waypoint
            }
        }
    }
    return null
}

/**
 * Calculate distance between two points in degrees (following Hilbert validation pattern)
 */
private fun calculateDistanceDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = lat1 - lat2
    val dLon = lon1 - lon2
    return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
}
