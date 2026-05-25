package com.madanala.tern.ui.components

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.model.LocationType
import com.madanala.tern.overlay.airspace.AirspaceOverlay
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapConstants
import com.madanala.tern.redux.MapStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import org.osmdroid.util.GeoPoint

private const val TAG = "MapViewContainer"
private val COMPASS_PADDING = 16.dp

// Default center (roughly center of France — reasonable for a paragliding app
// that starts without a GPS fix). Once the GPS acquires, Redux pushes the real
// position and the map snaps to it.
private const val DEFAULT_LAT = 45.8
private const val DEFAULT_LON = 6.5

@Composable
fun MapViewContainer(
    modifier: Modifier = Modifier,
    store: MapStore = viewModel(),
) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
    val hasLocationPermission = handleLocationPermissions(store)
    val coroutineScope = rememberCoroutineScope()

    // Register middleware (unchanged from OSMDroid version)
    LaunchedEffect(store) {
        store.addMiddleware(com.madanala.tern.redux.MapMiddleware(context.applicationContext))
        store.addMiddleware(com.madanala.tern.redux.RoutePlanningMiddleware(context.applicationContext))
        store.addMiddleware(com.madanala.tern.redux.WeatherMiddleware())
    }

    // MapViewModel still owns overlay lifecycle — disabled for M1 but kept
    // so that M2-M5 can migrate one manager at a time.
    val mapViewModel: MapViewModel = viewModel()

    // Connect MapViewModel to the Redux store (needed for smart suggestion)
    LaunchedEffect(store) {
        mapViewModel.setMapStore(store)
    }

    // Location service (Redux-driven, no OSMDroid dependency)
    val locationService = remember(store) { ReduxLocationService(store) }

    // Setup location updates
    setupLocationUpdates(state.hasLocationPermission, locationService)

    // ──────────────────────────────────────────────────────────────────────
    // MapLibre camera state — the single source of truth for what the user
    // sees on screen.  Redux's MapState.center/zoom/rotation are the
    // *application-level* source of truth; the CameraState is the
    // *rendering-level* source of truth.  We keep them in sync below.
    // ──────────────────────────────────────────────────────────────────────

    val initialCenter = state.center
    val initialZoom = state.zoom

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(
                longitude = initialCenter?.longitude ?: DEFAULT_LON,
                latitude = initialCenter?.latitude ?: DEFAULT_LAT,
            ),
            zoom = initialZoom,
        )
    )

    // ── Redux → MapLibre (programmatic camera moves) ────────────────────
    // When something external (GPS first fix, route zoom, bounding box)
    // updates Redux's center/zoom, push that into the MapLibre camera.
    // We skip updates that came from the user's own gesture (tracked via
    // CameraMoveReason) to avoid feedback loops.
    LaunchedEffect(store) {
        store.state
            .map { Triple(it.center, it.zoom, it.rotation) }
            .distinctUntilChanged()
            .collectLatest { (center, zoom, rotation) ->
                // Only push Redux changes when the camera isn't being moved
                // by the user's finger. If it IS a gesture, the MapLibre →
                // Redux path (below) will update Redux to match.
                if (cameraState.moveReason != CameraMoveReason.GESTURE) {
                    center?.let {
                        cameraState.animateTo(
                            CameraPosition(
                                target = Position(
                                    longitude = it.longitude,
                                    latitude = it.latitude,
                                ),
                                zoom = zoom,
                                bearing = rotation.toDouble(),
                            )
                        )
                    }
                }
            }
    }

    // Handle pending bounding box (e.g. ZoomToRoute)
    LaunchedEffect(store) {
        store.state
            .map { it.pendingBoundingBox }
            .distinctUntilChanged()
            .collectLatest { box ->
                if (box != null) {
                    val bbox = org.maplibre.spatialk.geojson.BoundingBox(
                        Position(box.minLon, box.minLat),
                        Position(box.maxLon, box.maxLat),
                    )
                    cameraState.animateTo(bbox)
                    // Clear pending so it doesn't re-trigger
                    store.dispatch(MapAction.UpdateBoundingBox(null))
                }
            }
    }

    // ── MapLibre → Redux (user gestures) ────────────────────────────────
    // When the user pans/zooms/rotates the map, propagate the new camera
    // position back to Redux so overlays, location tracking, etc. stay
    // in sync.
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.position }
            .distinctUntilChanged()
            .collectLatest { pos ->
                val target = pos.target
                val lat = target.latitude
                val lon = target.longitude

                // Debounce is already handled by distinctUntilChanged on
                // the snapshot flow.  We dispatch a single combined action
                // so the reducer only fires once per camera frame.
                store.dispatch(
                    MapAction.UpdateMapMovement(
                        rotation = pos.bearing.toFloat(),
                        center = GeoPoint(lat, lon),
                        zoom = pos.zoom,
                    )
                )
            }
    }

    // Smart Waypoint Creation State (driven by Redux)
    val smartSuggestionState = state.smartSuggestionState
    val nearbyPGSpot = smartSuggestionState.nearbyPGSpot
    val pendingWaypointCreation = smartSuggestionState.pendingWaypointCreation

    // Smart Waypoint Dialog (unchanged from OSMDroid version)
    if (nearbyPGSpot != null && pendingWaypointCreation != null) {
        val spotName = nearbyPGSpot.feature?.get("properties")?.let { props ->
            (props as? Map<*, *>)?.get("name") as? String
        } ?: "Unknown Spot"

        val spotType = nearbyPGSpot.feature?.get("properties")?.let { props ->
            (props as? Map<*, *>)?.get("siteType") as? String
        } ?: "Launch"

        AlertDialog(
            onDismissRequest = {
                pendingWaypointCreation.let { geoPoint ->
                    store.dispatch(MapAction.LongPressMap(geoPoint))
                }
                mapViewModel.clearSmartSuggestionState()
            },
            title = {
                Text(
                    "Nearby ${
                        spotType.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
                        }
                    }"
                )
            },
            text = { Text("Found nearby paragliding spot: \"$spotName\".\n\nDo you want to use this spot as your waypoint?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        nearbyPGSpot.let { feature ->
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
                TextButton(
                    onClick = {
                        pendingWaypointCreation.let { geoPoint ->
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

    // ──────────────────────────────────────────────────────────────────────
    // Layout: MapLibre fills the entire Box.  Compose controls (compass,
    // route HUD) are siblings that float on top — no AndroidView interop
    // needed anymore.
    // ──────────────────────────────────────────────────────────────────────

    Box(modifier = modifier.fillMaxSize()) {
        // Native MapLibre map — uses the native SDK directly because
        // maplibre-compose's SymbolLayer wrapper is broken in v0.13.0.
        // Everything on the map is a GeoJSON feature rendered by native layers.
        com.madanala.tern.overlay.NativeMapView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("map_view"),
            initialLat = initialCenter?.latitude ?: DEFAULT_LAT,
            initialLon = initialCenter?.longitude ?: DEFAULT_LON,
            initialZoom = initialZoom,
            peers = state.peerState.peers,
            viewMode = state.mezullaViewMode,
            lastEventTime = state.peerState.lastEventTime,
        )

        // Compass (reads rotation from Redux state, same as before)
        if (state.compassVisible) {
            Compass(
                rotation = state.rotation,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(COMPASS_PADDING),
            )
        }

        // Route Planning HUD
        if (state.selectedRouteId != null) {
            RoutePlanningHUD(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(16.dp),
            )
        }
    }
}

// ── Helper composables ──────────────────────────────────────────────────

@Composable
private fun setupLocationUpdates(
    hasLocationPermission: Boolean,
    locationService: ReduxLocationService,
) {
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            locationService.startLocationUpdates()
            Log.d(TAG, "Redux location service started")
        } else {
            locationService.stopLocationUpdates()
            Log.d(TAG, "Redux location service stopped")
        }
    }
}
