package com.ternparagliding.ui.components

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ternparagliding.model.LocationType
import com.ternparagliding.overlay.airspace.AirspaceOverlay
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import org.osmdroid.util.GeoPoint

private const val TAG = "MapViewContainer"
private val COMPASS_PADDING = 16.dp

// Map style selection.
private fun mapStyleFor(styleId: String): BaseStyle = when (styleId) {
    "terrain" -> BaseStyle.Json(
        """{
          "version": 8,
          "sources": {
            "opentopomap": {
              "type": "raster",
              "tiles": [
                "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"
              ],
              "tileSize": 256,
              "maxzoom": 17,
              "attribution": "Map data: © OpenStreetMap contributors, SRTM | Map style: © OpenTopoMap (CC-BY-SA)"
            }
          },
          "layers": [
            { "id": "opentopomap", "type": "raster", "source": "opentopomap" }
          ]
        }""".trimIndent()
    )
    "satellite" -> BaseStyle.Json(
        """{
          "version": 8,
          "sources": {
            "esri": {
              "type": "raster",
              "tiles": [
                "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
              ],
              "tileSize": 256,
              "maxzoom": 19,
              "attribution": "Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community"
            }
          },
          "layers": [
            { "id": "esri", "type": "raster", "source": "esri" }
          ]
        }""".trimIndent()
    )
    else -> BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
}

// Default center
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
    val density = LocalDensity.current

    // Register middleware
    LaunchedEffect(store) {
        store.addMiddleware(com.ternparagliding.redux.MapMiddleware(context.applicationContext))
        store.addMiddleware(com.ternparagliding.redux.RoutePlanningMiddleware(context.applicationContext))
        store.addMiddleware(com.ternparagliding.redux.WeatherMiddleware())
        store.addMiddleware(com.ternparagliding.redux.CountryPreloadMiddleware(context.applicationContext))
    }

    // Persist routes
    LaunchedEffect(store) {
        com.ternparagliding.redux.RoutePersistence.observe(
            store,
            com.ternparagliding.utils.cache.CacheManager.routeCache,
        )
    }

    // Persist unit preferences (hydrate on start, write through on change)
    LaunchedEffect(store) {
        com.ternparagliding.redux.SettingsPersistence.observe(store, context.applicationContext)
    }

    val mapViewModel: MapViewModel = viewModel()

    LaunchedEffect(store) {
        mapViewModel.setMapStore(store)
    }

    val locationService = remember(store, context) { ReduxLocationService(store, context) }
    setupLocationUpdates(state.hasLocationPermission, locationService)

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

    // Test hook
    val projectionDensity = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.DisposableEffect(cameraState, projectionDensity) {
        com.ternparagliding.utils.MapProjectionTestHook.setResolver { lat, lon ->
            val proj = runCatching { cameraState.projection }.getOrNull()
                ?: return@setResolver null
            val dp = proj.screenLocationFromPosition(
                Position(longitude = lon, latitude = lat)
            )
            with(projectionDensity) {
                androidx.compose.ui.geometry.Offset(dp.x.toPx(), dp.y.toPx())
            }
        }
        onDispose { com.ternparagliding.utils.MapProjectionTestHook.setResolver(null) }
    }

    // Redux → MapLibre
    LaunchedEffect(store) {
        store.state
            .map { Triple(it.center, it.zoom, it.rotation) }
            .distinctUntilChanged()
            .collectLatest { (center, zoom, rotation) ->
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

    // Handle pending bounding box
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
                    val settled = cameraState.position
                    store.dispatch(
                        MapAction.UpdateMapMovement(
                            rotation = settled.bearing.toFloat(),
                            center = GeoPoint(settled.target.latitude, settled.target.longitude),
                            zoom = settled.zoom,
                        )
                    )
                }
            }
    }

    // MapLibre → Redux (Robust Muffling)
    LaunchedEffect(cameraState) {
        var lastDispatchedPos: Position? = null
        val pixelThresholdSq = 25.0 // 5px threshold squared

        snapshotFlow { cameraState.position to cameraState.moveReason }
            .distinctUntilChanged()
            .conflate()
            .collectLatest { pair ->
                val pos = pair.first
                val reason = pair.second

                if (reason == CameraMoveReason.GESTURE) {
                    val target = pos.target
                    val shouldDispatch = lastDispatchedPos?.let { last ->
                        val proj = cameraState.projection
                        if (proj != null) {
                            val p1 = proj.screenLocationFromPosition(last)
                            val p2 = proj.screenLocationFromPosition(target)
                            val dx = (p1.x - p2.x).value
                            val dy = (p1.y - p2.y).value
                            (dx * dx + dy * dy) > pixelThresholdSq
                        } else true
                    } ?: true

                    if (shouldDispatch) {
                        store.dispatch(
                            MapAction.UpdateMapMovement(
                                rotation = pos.bearing.toFloat(),
                                center = GeoPoint(target.latitude, target.longitude),
                                zoom = pos.zoom,
                            )
                        )
                        lastDispatchedPos = target
                    }
                }
            }
    }

    // Smart Waypoint Creation State
    val smartSuggestionState = state.smartSuggestionState
    val nearbyPGSpot = smartSuggestionState.nearbyPGSpot
    val pendingWaypointCreation = smartSuggestionState.pendingWaypointCreation

    if (nearbyPGSpot != null && pendingWaypointCreation != null) {
        val spotName = nearbyPGSpot.feature.get("properties")?.let { props ->
            (props as? Map<*, *>)?.get("name") as? String
        } ?: "Unknown Spot"

        val spotType = nearbyPGSpot.feature.get("properties")?.let { props ->
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

    Box(modifier = modifier.fillMaxSize()) {
        MaplibreMap(
            Modifier
                .fillMaxSize()
                .testTag("map_view"),
            mapStyleFor(state.mapStyle),
            cameraState,
        ) {
            // Route overlay
            val visibleRoutes = state.routes.filter { it.isVisible }
            if (visibleRoutes.isNotEmpty()) {
                com.ternparagliding.overlay.route.RouteLayer(
                    routes = visibleRoutes,
                    selectedWaypointId = state.selectedWaypoint?.let { "${it.routeId}:${it.waypointId}" },
                )
            }

            // Airspace overlay
            AirspaceOverlay(store = store, cameraState = cameraState)

            // PG-spot overlay
            com.ternparagliding.overlay.pgspot.PgSpotOverlay(
                store = store,
                cameraState = cameraState,
            )

            // Weather-hazard halos
            com.ternparagliding.overlay.hazard.HazardOverlay(store = store)

            // Peer markers
            val ctx = LocalContext.current
            val nerdFont = remember {
                androidx.core.content.res.ResourcesCompat.getFont(
                    ctx, com.ternparagliding.R.font.jetbrains_mono_nerd_regular
                )
            }
            com.ternparagliding.overlay.mezulla.PeerLayer(
                peers = state.peerState.peers,
                viewMode = state.mezullaViewMode,
                lastEventTime = state.peerState.lastEventTime,
                ownLocation = state.userLocation,
                nerdFont = nerdFont,
            )
        }

        com.ternparagliding.overlay.route.RouteProximityOverlay(store = store)

        com.ternparagliding.overlay.mezulla.OffScreenPeerIndicators(
            peers = state.peerState.peers,
            ownLocation = state.userLocation,
            cameraState = cameraState,
        )

        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(COMPASS_PADDING),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.ternparagliding.mezulla.ui.MezullaStatusBadge(
                peerState = state.peerState,
            )
            if (state.compassVisible) {
                Compass(rotation = state.rotation)
            }
        }

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
