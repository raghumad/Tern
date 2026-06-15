package com.ternparagliding.ui.components

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
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
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
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

/**
 * For a bench-replay flight id, the team scenario + the buddy pilots to render as Mezulla peers
 * (with stable synthetic node numbers), or null for a solo replay. The own-ship pilot (the DUT
 * the replay streams) is deliberately excluded so it doesn't double as a peer.
 */
private fun deckBuddies(
    flightId: String,
): Pair<com.ternparagliding.sim.swarm.Scenario, Map<com.ternparagliding.sim.swarm.PilotId, Long>>? {
    val aravis = com.ternparagliding.sim.swarm.scenarios.AravisTeam2026
    val bir = com.ternparagliding.sim.swarm.scenarios.BirBilling2025
    return when (flightId) {
        "aravis" -> aravis.scenario to mapOf(
            aravis.CBE to 0xB0D01L, aravis.COR to 0xB0D02L, aravis.LMA to 0xB0D03L,
        )
        "birbilling" -> bir.scenario to mapOf(
            bir.ARIEL to 0xB0D04L, bir.BARNEY to 0xB0D05L,
        )
        else -> null
    }
}

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

    // XC Tracer vario over BLE — a second peripheral beside the LoRa board. The pilot toggles
    // it from the shelf; once it streams positioned fixes it becomes the position authority and
    // the phone GPS powers down (better data + battery offload), falling back on disconnect.
    val xcTracerClient = remember(context) {
        com.ternparagliding.flight.XcTracerBleClient(context.applicationContext, coroutineScope)
    }
    val windTracker = remember { com.ternparagliding.flight.CirclingWindTracker() }
    // Deck accumulators (shared by the live BLE path and the IGC bench replay): thermal
    // averager, the climb-tinted track, and a short rolling buffer for phase classification.
    val averager = remember { com.ternparagliding.flight.ThermalAverager() }
    val flightTrack = remember { com.ternparagliding.flight.FlightTrack() }
    val phaseBuf = remember { ArrayDeque<com.ternparagliding.flight.WindEstimator.TrackSample>() }
    val trackVersion = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Smoothed follow-camera state (eased, not snapped) + last value actually dispatched.
    val smoothedZoom = remember { androidx.compose.runtime.mutableStateOf(Double.NaN) }
    val smoothedBearing = remember { androidx.compose.runtime.mutableStateOf(Double.NaN) }
    val lastZoom = remember { androidx.compose.runtime.mutableStateOf(Double.NaN) }
    val lastBearing = remember { androidx.compose.runtime.mutableStateOf(Double.NaN) }
    fun resetDeckCamera() {
        smoothedZoom.value = Double.NaN; smoothedBearing.value = Double.NaN
        lastZoom.value = Double.NaN; lastBearing.value = Double.NaN
    }
    // Buddy playback for team replays: own-ship is the scenario DUT, and the rest of the team
    // ride onto the map as Mezulla peers driven off the SAME replay clock (one timeline). The
    // scenario + buddy node-numbers are chosen per flight by [deckBuddies].
    val buddyPlayback = remember { androidx.compose.runtime.mutableStateOf<com.ternparagliding.sim.swarm.SwarmPlayback?>(null) }
    val buddyNodes = remember { androidx.compose.runtime.mutableStateOf<Map<com.ternparagliding.sim.swarm.PilotId, Long>>(emptyMap()) }
    // Throttle buddy injection: each PeerPositionReceived rebuilds the peer bundle + re-renders the
    // marker bitmaps, so dispatching one per replay fix (~8/s) starves the UI thread. Real LoRa
    // buddies update every few seconds anyway — cap at ~3/s (wall clock) to keep the deck smooth.
    val lastBuddyDriveMs = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    fun driveBuddies(timeMs: Long) {
        val pb = buddyPlayback.value ?: return
        val nowReal = android.os.SystemClock.uptimeMillis()
        if (nowReal - lastBuddyDriveMs.longValue < 300L) return
        lastBuddyDriveMs.longValue = nowReal
        val t = java.time.Instant.ofEpochMilli(timeMs)
        buddyNodes.value.forEach { (pilot, node) ->
            val pos = pb.currentPosition(pilot, t) ?: return@forEach // null = pre-launch / landed
            val identity = com.ternparagliding.mezulla.connection.PeerIdentity.fromNodeNumber(
                node, longName = pilot.value, shortName = pilot.value.take(4),
            )
            val pfix = com.ternparagliding.mezulla.connection.PeerPosition.Fix(
                latitudeDeg = pos.latitude,
                longitudeDeg = pos.longitude,
                altitudeMeters = pos.altitudeMeters,
                groundSpeedMetersPerSecond = null,
                groundTrackDegrees = null,
                timestampSeconds = t.epochSecond,
            )
            store.dispatch(com.ternparagliding.mezulla.redux.PeerAction.PeerPositionReceived(identity, pfix, t))
        }
    }

    // One fix from any source (BLE or replay): update the brains, push the enriched deck state,
    // grow the track, and follow the pilot. The camera is *eased*: zoom and track-up bearing
    // nudge toward their targets so motion stays calm even under sped-up replay. Track-up follows
    // course on glide but holds steady while circling (otherwise the map spins every thermal).
    fun onDeckFix(fix: com.ternparagliding.flight.SensorFix) {
        val wind = windTracker.add(fix)
        val avg = fix.climbMs?.let { averager.add(fix.timeMs, it) }
        fix.toTrackSample()?.let { ts ->
            phaseBuf.addLast(ts)
            val cutoff = ts.timeMs - 30_000L
            while (phaseBuf.isNotEmpty() && phaseBuf.first().timeMs < cutoff) phaseBuf.removeFirst()
        }
        if (flightTrack.add(fix)) trackVersion.intValue++
        store.dispatch(MapAction.UpdateVarioFix(fix, wind?.directionDeg, wind?.speedMs, avg))
        driveBuddies(fix.timeMs) // synchronized buddies (Aravis replay only; no-op otherwise)
        if (fix.hasPosition) {
            val cam = com.ternparagliding.flight.FlightCamera
            val phase = com.ternparagliding.flight.WindEstimator.classifyPhase(phaseBuf.toList())
            val circling = phase == com.ternparagliding.flight.WindEstimator.FlightPhase.CIRCLING

            val targetZoom = cam.autoZoom(phase, fix.groundSpeedMs ?: 0.0)
            smoothedZoom.value = cam.ease(smoothedZoom.value, targetZoom, cam.ZOOM_EASE)
            // Hold heading while circling (don't chase the spinning course); else track-up.
            val targetBearing = if (circling) smoothedBearing.value else (fix.courseDeg ?: smoothedBearing.value)
            smoothedBearing.value = cam.easeBearing(smoothedBearing.value, targetBearing, cam.BEARING_EASE)

            store.dispatch(MapAction.UpdateCenter(GeoPoint(fix.lat!!, fix.lon!!)))
            val sz = smoothedZoom.value
            if (lastZoom.value.isNaN() || kotlin.math.abs(sz - lastZoom.value) > 0.05) {
                store.dispatch(MapAction.UpdateZoom(sz)); lastZoom.value = sz
            }
            val sb = smoothedBearing.value
            if (!sb.isNaN()) {
                val moved = lastBearing.value.isNaN() ||
                    kotlin.math.abs(((sb - lastBearing.value + 540.0) % 360.0) - 180.0) > 0.5
                if (moved) { store.dispatch(MapAction.UpdateRotation(sb.toFloat())); lastBearing.value = sb }
            }
        }
    }
    // BLE runtime permissions for the vario scan (Android 12+). Requested on first connect.
    val blePerms = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else emptyArray()
    }
    fun hasBlePerms(): Boolean = blePerms.all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val blePermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) xcTracerClient.start()
        else store.dispatch(MapAction.ToggleVario) // denied → revert the toggle
    }
    LaunchedEffect(state.flightDeck.varioRequested) {
        if (state.flightDeck.varioRequested) {
            if (hasBlePerms()) xcTracerClient.start() else blePermLauncher.launch(blePerms)
        } else {
            xcTracerClient.stop()
        }
    }
    LaunchedEffect(xcTracerClient) {
        var handedOver = false
        launch {
            xcTracerClient.fixes().collect { fix ->
                onDeckFix(fix)
                if (fix.hasPosition && !handedOver) {
                    handedOver = true
                    locationService.stopLocationUpdates() // vario is the better source now
                }
            }
        }
        launch {
            xcTracerClient.state().collect { st ->
                val connected = st == com.ternparagliding.flight.XcTracerBleClient.State.CONNECTED
                val scanning = st == com.ternparagliding.flight.XcTracerBleClient.State.SCANNING
                store.dispatch(MapAction.SetVarioLinkState(connected, scanning))
                if (!connected && handedOver) {
                    // Lost the vario — fall back to phone GPS until it returns.
                    handedOver = false
                    windTracker.reset()
                    if (store.state.value.hasLocationPermission) locationService.startLocationUpdates()
                }
            }
        }
    }

    // IGC bench replay: drive the deck from a bundled flight through the *same* path the live
    // vario uses (no hardware). Started/stopped from the Settings demo section.
    LaunchedEffect(state.flightDeck.replayFlightId) {
        val id = state.flightDeck.replayFlightId ?: return@LaunchedEffect
        val flight = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.ternparagliding.flight.IgcReplaySource.load(id)
        }
        if (flight == null) {
            Log.w(TAG, "Deck replay: flight '$id' not found")
            store.dispatch(MapAction.StopDeckReplay)
            return@LaunchedEffect
        }
        windTracker.reset(); averager.reset(); flightTrack.reset()
        phaseBuf.clear(); trackVersion.intValue++; resetDeckCamera()
        // Team flights (Aravis, Bir Billing) carry buddies — load their synchronized playback.
        val buddies = deckBuddies(id)
        buddyNodes.value = buddies?.second ?: emptyMap()
        buddyPlayback.value = buddies?.let { (scenario, _) ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.ternparagliding.sim.swarm.SwarmPlayback(scenario) }
                    .onFailure { Log.e(TAG, "buddy playback load failed for '$id'", it) }
                    .getOrNull()
            }
        }
        Log.i(TAG, "deck replay '$id': buddies=${buddyNodes.value.keys}, playback=${buddyPlayback.value != null}")
        // Pre-centre on the flight's first fix *before* the fix flood. Otherwise, if the map is far
        // away (e.g. browsing elsewhere), the per-fix recomposition over dense en-route airspace
        // starves the main thread before the camera can travel there — and it never arrives.
        flight.fixes.firstOrNull { it.fixValid }?.let { f0 ->
            store.dispatch(MapAction.UpdateCenter(GeoPoint(f0.latitude, f0.longitude)))
            store.dispatch(MapAction.UpdateZoom(com.ternparagliding.flight.FlightCamera.GLIDE_SLOW_ZOOM))
        }
        store.dispatch(MapAction.SetVarioLinkState(connected = true, scanning = false))
        try {
            com.ternparagliding.flight.IgcReplaySource(flight).fixes().collect { onDeckFix(it) }
        } finally {
            buddyPlayback.value = null // stop driving buddies on end/cancel
            buddyNodes.value = emptyMap()
        }
        store.dispatch(MapAction.UpdateRotation(0f)) // natural end → north-up
        store.dispatch(MapAction.StopDeckReplay)     // and reset the deck
    }

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
                        val target = CameraPosition(
                            target = Position(longitude = it.longitude, latitude = it.latitude),
                            zoom = zoom,
                            bearing = rotation.toDouble(),
                        )
                        // Snap (don't animate) across large jumps — e.g. a bench replay starting
                        // while the map is on the far side of the world. animateTo would be cancelled
                        // by the next fix every ~125ms and never traverse the gap, freezing the camera.
                        val cur = cameraState.position.target
                        val farJump = kotlin.math.abs(cur.latitude - it.latitude) > 2.0 ||
                            kotlin.math.abs(cur.longitude - it.longitude) > 2.0
                        if (farJump) cameraState.position = target else cameraState.animateTo(target)
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
            // Disable MapLibre's native compass — we render our own (Compass) and the two
            // overlapped at TopEnd on rotation. Keep the logo + attribution (OSM/Esri legal).
            options = MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false)),
        ) {
            // Route overlay
            val visibleRoutes = state.routes.filter { it.isVisible }
            if (visibleRoutes.isNotEmpty()) {
                com.ternparagliding.overlay.route.RouteLayer(
                    routes = visibleRoutes,
                    selectedWaypointId = state.selectedWaypoint?.let { "${it.routeId}:${it.waypointId}" },
                )
            }

            // Climb-tinted flight track (live vario or bench replay).
            if (state.flightDeck.varioConnected) {
                com.ternparagliding.overlay.flight.FlightTrackLayer(
                    segments = flightTrack.segments(),
                    version = trackVersion.intValue,
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
            val gruppoFont = remember {
                androidx.core.content.res.ResourcesCompat.getFont(ctx, com.ternparagliding.R.font.gruppo_regular)
            }
            com.ternparagliding.overlay.mezulla.PeerLayer(
                peers = state.peerState.peers,
                viewMode = state.mezullaViewMode,
                lastEventTime = state.peerState.lastEventTime,
                ownLocation = state.userLocation,
                nerdFont = nerdFont,
                labelFont = gruppoFont,
                altitudeUnit = state.settingsState.altitudeUnit,
            )
        }

        com.ternparagliding.overlay.route.RouteProximityOverlay(store = store)

        com.ternparagliding.overlay.mezulla.OffScreenPeerIndicators(
            peers = state.peerState.peers,
            ownLocation = state.userLocation,
            cameraState = cameraState,
            // Use the peer timeline as "now" so replayed buddies (flight-time timestamps) aren't
            // all judged LOST against the wall clock — matches how PeerLayer ages on-map peers.
            now = state.peerState.lastEventTime,
        )

        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                // systemBars (not just statusBars): in landscape the nav bar moves to the right
                // edge, so the compass must inset from it too or it sits under an un-tappable bar.
                .padding(WindowInsets.systemBars.asPaddingValues())
                .padding(COMPASS_PADDING),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.ternparagliding.mezulla.ui.MezullaStatusBadge(
                peerState = state.peerState,
            )
            if (state.compassVisible) {
                // The needle points to true north, so it rotates opposite the camera
                // bearing (MapLibre bearing is clockwise from north): bearing 90° (facing
                // east) → north is to the left → rotate −90°.
                Compass(
                    rotation = -state.rotation,
                    windFromDeg = if (state.flightDeck.varioConnected) state.flightDeck.windFromDeg else null,
                )
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

        // Combined altitude + vario tape (left edge). Height adapts to the viewport so it doesn't
        // collide with the HUD in landscape; the HUD moves to bottom-centre there to stay clear.
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val tapeH = (configuration.screenHeightDp - 64).coerceIn(180, 348).dp
        if (state.flightDeck.varioConnected) {
            AltitudeVarioTape(
                altitudeM = state.flightDeck.altitudeM,
                climbMs = state.flightDeck.climbMs,
                avgClimbMs = state.flightDeck.avgClimbMs,
                takeoffDatumM = state.flightDeck.takeoffDatumM,
                altitudeUnit = state.settingsState.altitudeUnit,
                tapeHeight = tapeH,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp),
            )
        }

        // Own-ship pilot glyph at screen-centre (the follow-camera keeps the pilot there).
        // Heading is track relative to the map, so it points up on glide and swings while circling.
        if (state.flightDeck.varioConnected && state.flightDeck.courseDeg != null) {
            PilotGlyph(
                headingDeg = (state.flightDeck.courseDeg!! - state.rotation).toFloat(),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Live flight-deck readout (vario / altitude / wind) when an external vario is streaming.
        if (state.flightDeck.varioConnected) {
            VarioHud(
                deck = state.flightDeck,
                settings = state.settingsState,
                modifier = Modifier
                    .align(if (landscape) Alignment.BottomEnd else Alignment.BottomStart)
                    // Clear the system bars (right-edge nav bar in landscape, bottom nav in portrait).
                    .padding(WindowInsets.systemBars.asPaddingValues())
                    .padding(if (landscape) 16.dp else 24.dp),
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
