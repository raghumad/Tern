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
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ternparagliding.model.LocationType
import com.ternparagliding.mezulla.PositionBroadcastPolicy
import com.ternparagliding.mezulla.toPeerPositionFix
import com.ternparagliding.overlay.airspace.AirspaceOverlay
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.resolvedSelectedTask
import com.ternparagliding.redux.resolvedTasks
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

/**
 * Deck-replay ids that ALSO broadcast their track over LoRa (debug only) — the two-device buddy
 * test: each phone replays one Bir Billing pilot and transmits it so the other phone renders a real
 * moving buddy. Distinct from the plain "birbilling"/"aravis" bench demo, which renders buddies
 * locally and must never transmit. Replayed at 1× so receiver-derived climb/speed stay faithful.
 */
private val BROADCAST_REPLAY_IDS = setOf("birbilling-richard", "birbilling-barney", "birbilling-ariel")

/** Stable id so re-running the demo overwrites rather than accumulates. */
private const val BIR_DEMO_TASK_ID = "demo-birbilling-task"

/**
 * A demo task for the Bir Billing bench replay so the next-waypoint guidance has
 * something to drive. Cylinder centres are sampled *from the flown track*, so the
 * replayed position genuinely flies through each one (auto-advance fires) — and
 * each carries a cryptic code + a human description to show the readable name.
 */
private fun birBillingDemoTask(flight: com.ternparagliding.sim.igc.IgcFlight): com.ternparagliding.model.Task {
    val pts = flight.fixes.filter { it.fixValid }
    fun at(frac: Double) = pts[(pts.size * frac).toInt().coerceIn(0, pts.lastIndex)]
    data class Wp(val frac: Double, val code: String, val desc: String, val type: com.ternparagliding.model.LocationType, val r: Double)
    val plan = listOf(
        Wp(0.02, "T01", "Bir Takeoff", com.ternparagliding.model.LocationType.LAUNCH, 400.0),
        Wp(0.30, "S02", "Billing Start", com.ternparagliding.model.LocationType.SSS, 1000.0),
        Wp(0.55, "B03", "Chamera Ridge", com.ternparagliding.model.LocationType.TURNPOINT, 2000.0),
        Wp(0.80, "B04", "Dharamshala Spur", com.ternparagliding.model.LocationType.TURNPOINT, 2000.0),
        Wp(0.97, "G05", "Bir Landing", com.ternparagliding.model.LocationType.GOAL, 1000.0),
    )
    return com.ternparagliding.model.Task(
        id = BIR_DEMO_TASK_ID,
        name = "Bir Billing Demo Task",
        waypoints = plan.map { w ->
            val f = at(w.frac)
            com.ternparagliding.model.Waypoint(
                lat = f.latitude, lon = f.longitude, type = w.type,
                label = w.code, description = w.desc, radius = w.r,
            )
        },
    )
}

/** The next-waypoint read shown on/under the compass rosette. */
private data class NextWpNav(
    val bearingDeg: Double,
    val distanceM: Double,
    val number: String,
    val name: String,
)

/** Compact distance for the rosette readout (m under 1 km, else 1-dp km). */
private fun formatNavDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()}m" else String.format("%.1fkm", meters / 1000.0)

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
    // App-scoped Mezulla link, for broadcasting our own position to peers (null if not a TernApplication).
    val connectionManager = remember(context) {
        (context.applicationContext as? com.ternparagliding.TernApplication)?.connectionManager
    }

    // Register middleware
    LaunchedEffect(store) {
        store.addMiddleware(com.ternparagliding.redux.MapMiddleware(context.applicationContext))
        store.addMiddleware(com.ternparagliding.redux.TaskPlanningMiddleware(context.applicationContext))
        store.addMiddleware(com.ternparagliding.redux.WeatherMiddleware())
        store.addMiddleware(com.ternparagliding.redux.CountryPreloadMiddleware(context.applicationContext))
        store.addMiddleware(com.ternparagliding.redux.FlightRecordingMiddleware(context.applicationContext))
    }

    // Persist tasks
    LaunchedEffect(store) {
        com.ternparagliding.redux.TaskPersistence.observe(
            store,
            com.ternparagliding.utils.cache.CacheManager.taskCache,
        )
    }

    // Persist + hydrate the standalone waypoint library.
    LaunchedEffect(store) {
        com.ternparagliding.redux.WaypointLibraryPersistence.observe(
            store,
            com.ternparagliding.utils.cache.WaypointLibraryStore(context.applicationContext),
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
        com.ternparagliding.flight.XcTracerBleClient(context.applicationContext, coroutineScope).apply {
            // The vario's random BLE address can rotate on power-cycle; when we re-adopt it by
            // name, persist the new MAC (keeping the saved name) so future reconnects are instant.
            onMacResolved = { mac, name ->
                store.dispatch(MapAction.SetRememberedVario(mac, name ?: store.state.value.settingsState.rememberedVarioName))
            }
        }
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
    // "Are we flying yet?" — the follow-cam stays hands-off until airborne so the map doesn't grab
    // control while the pilot sits on launch. Latched per session; the launch datum is the first
    // positioned fix's altitude (height-above-takeoff feeds the soaring-in-light-wind case).
    val flightDetect = remember { androidx.compose.runtime.mutableStateOf(com.ternparagliding.flight.FlightDetector.State()) }
    val takeoffDatum = remember { androidx.compose.runtime.mutableStateOf(Double.NaN) }
    // Team sheet (roster + view-mode), opened by tapping the buddies chip near the compass.
    val showTeamSheet = remember { androidx.compose.runtime.mutableStateOf(false) }
    fun resetDeckCamera() {
        smoothedZoom.value = Double.NaN; smoothedBearing.value = Double.NaN
        lastZoom.value = Double.NaN; lastBearing.value = Double.NaN
        flightDetect.value = com.ternparagliding.flight.FlightDetector.State()
        takeoffDatum.value = Double.NaN
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
            // Decide airborne before touching the camera. On launch (sitting), ground speed ≈ 0 and
            // height-above-takeoff ≈ 0, so we stay grounded and leave the map under the pilot's
            // fingers. The first positioned fix sets the launch datum.
            if (takeoffDatum.value.isNaN()) fix.gpsAltitudeM?.let { takeoffDatum.value = it }
            val heightAboveTakeoff = fix.gpsAltitudeM?.let { alt ->
                if (takeoffDatum.value.isNaN()) null else alt - takeoffDatum.value
            }
            val wasAirborne = flightDetect.value.airborne
            flightDetect.value = com.ternparagliding.flight.FlightDetector.update(
                flightDetect.value, fix.groundSpeedMs, heightAboveTakeoff,
            )
            // At the moment of launch, re-engage follow — even if the pilot had panned the map
            // around while sitting on the ground (which switched follow off). Launching is the
            // signal that they now want the moving map back.
            if (!wasAirborne && flightDetect.value.airborne && !store.state.value.cameraFollow) {
                store.dispatch(MapAction.SetCameraFollow(true))
            }
            // Follow only when airborne AND the pilot hasn't taken manual control (pan/zoom). Either
            // off ⇒ no camera drive: the pilot owns the viewport.
            val following = flightDetect.value.airborne && store.state.value.cameraFollow
            if (!following) return

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
        if (grants.values.all { it }) {
            xcTracerClient.setTarget(store.state.value.settingsState.rememberedVarioMac)
            xcTracerClient.start()
        } else store.dispatch(MapAction.SetVarioPaused(true)) // denied → pause (don't keep nagging)
    }
    // Connect only to the pilot's CHOSEN vario (its remembered MAC) — never blind-grab the first
    // XC Tracer in range. A remembered + un-paused vario auto-connects on launch and self-heals;
    // it keeps scanning so the moment the vario powers back on it reconnects with zero taps. The
    // only stop is an explicit pause (Disconnect) or Forget. No vario picked ⇒ stay idle.
    val rememberedVarioMac = state.settingsState.rememberedVarioMac
    val varioShouldRun = rememberedVarioMac != null && !state.settingsState.varioPaused
    LaunchedEffect(varioShouldRun, rememberedVarioMac) {
        xcTracerClient.setTarget(rememberedVarioMac)
        if (varioShouldRun) {
            if (hasBlePerms()) xcTracerClient.start() else blePermLauncher.launch(blePerms)
        } else {
            xcTracerClient.stop()
        }
    }
    LaunchedEffect(xcTracerClient) {
        var handedOver = false
        var lastBroadcastMs: Long? = null
        launch {
            xcTracerClient.fixes().collect { fix ->
                onDeckFix(fix)
                // Broadcast our own position to peers over Mezulla, vario-synced (≈1 Hz), best-effort.
                // Live link only — the bench replay drives onDeckFix too but must never transmit its
                // synthetic fixes as ours. Carries alt+speed+track → feeds peers' Safety/Climb/Tactical.
                if (PositionBroadcastPolicy.shouldBroadcast(fix.hasPosition, lastBroadcastMs, fix.timeMs)) {
                    fix.toPeerPositionFix()?.let { pf ->
                        lastBroadcastMs = fix.timeMs
                        connectionManager?.activeBleConnection()?.let { conn ->
                            coroutineScope.launch { runCatching { conn.sendOwnPosition(pf) } }
                        }
                    }
                }
                if (fix.hasPosition && !handedOver) {
                    handedOver = true
                    locationService.stopLocationUpdates() // vario is the better source now
                }
            }
        }
        launch {
            var prev: com.ternparagliding.flight.XcTracerBleClient.State? = null
            var droppedAtMs = 0L
            xcTracerClient.state().collect { st ->
                val connected = st == com.ternparagliding.flight.XcTracerBleClient.State.CONNECTED
                val scanning = st == com.ternparagliding.flight.XcTracerBleClient.State.SCANNING
                store.dispatch(MapAction.SetVarioLinkState(connected, scanning))

                // Connection log — map each transition to one event so the pilot can SEE the
                // link status and every drop/heal (the requested visibility). Timestamps are
                // real wall-clock; an outage is measured from the drop to the next link-up.
                val now = System.currentTimeMillis()
                val event: com.ternparagliding.device.ConnectionEvent? = when {
                    st == com.ternparagliding.flight.XcTracerBleClient.State.CONNECTED ->
                        com.ternparagliding.device.ConnectionEvent(
                            now, com.ternparagliding.device.ConnectionEvent.Kind.LINKED,
                            outageMs = if (droppedAtMs > 0L) now - droppedAtMs else null,
                        ).also { droppedAtMs = 0L }
                    scanning && prev == com.ternparagliding.flight.XcTracerBleClient.State.CONNECTED -> {
                        droppedAtMs = now
                        com.ternparagliding.device.ConnectionEvent(now, com.ternparagliding.device.ConnectionEvent.Kind.DROPPED, com.ternparagliding.device.DropReason.LINK_LOST)
                    }
                    scanning && prev == null -> // first scan (pilot tapped Connect)
                        com.ternparagliding.device.ConnectionEvent(now, com.ternparagliding.device.ConnectionEvent.Kind.SCANNING)
                    st == com.ternparagliding.flight.XcTracerBleClient.State.IDLE && prev != null &&
                        prev != com.ternparagliding.flight.XcTracerBleClient.State.IDLE ->
                        com.ternparagliding.device.ConnectionEvent(now, com.ternparagliding.device.ConnectionEvent.Kind.PAUSED, com.ternparagliding.device.DropReason.USER)
                    else -> null
                }
                if (event != null && st != prev) store.dispatch(MapAction.LogVarioConnectionEvent(event))
                prev = st

                if (!connected && handedOver) {
                    // Lost the vario — fall back to phone GPS until it returns.
                    handedOver = false
                    windTracker.reset()
                    if (store.state.value.hasLocationPermission) locationService.startLocationUpdates()
                }
            }
        }
    }

    // Reconcile the board to our team. A team is phone-side *intent* (created/joined offline); this
    // is the single place it's written to the board (set_team). Fires when the link comes up OR the
    // team changes; the teamAppliedLink guard means an unchanged channel is never rewritten — so we
    // don't reconfigure the board on every launch, only when the team actually changed.
    LaunchedEffect(state.peerState.linkState, state.settingsState.teamShareLink, state.settingsState.teamAppliedLink) {
        val link = state.settingsState.teamShareLink
        if (state.peerState.linkState == com.ternparagliding.mezulla.connection.LinkState.UP &&
            link != null && link != state.settingsState.teamAppliedLink
        ) {
            com.ternparagliding.mezulla.pairing.TeamLink.parse(link)?.let { team ->
                // Retry: a single set_team write can come back false when the GATT queue is
                // momentarily busy (a position/heartbeat in flight). Without retrying, the channel
                // silently never changes — the LaunchedEffect keys don't change on a failed write,
                // so it would never re-run. A few spaced attempts make the join reliable; if all
                // fail the next link-up re-runs this effect.
                var ok = false
                var attempt = 0
                while (!ok && attempt < 6) {
                    ok = connectionManager?.setTeam(team.name, team.psk) ?: false
                    if (!ok) {
                        attempt++
                        kotlinx.coroutines.delay(500)
                    }
                }
                if (ok) {
                    store.dispatch(MapAction.SetTeamApplied(link))
                    // The board just switched LoRa channel — peers heard on the old channel aren't
                    // on this team. Drop them so the roster shows the new team, not lingering strays
                    // (e.g. the public mesh you were on before auto-joining your own private team).
                    store.dispatch(com.ternparagliding.mezulla.redux.PeerAction.PeersCleared)
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
        // away (e.g. browsing elsewhere), the per-fix recomposition over dense en-task airspace
        // starves the main thread before the camera can travel there — and it never arrives.
        flight.fixes.firstOrNull { it.fixValid }?.let { f0 ->
            store.dispatch(MapAction.UpdateCenter(GeoPoint(f0.latitude, f0.longitude)))
            store.dispatch(MapAction.UpdateZoom(com.ternparagliding.flight.FlightCamera.GLIDE_SLOW_ZOOM))
        }
        store.dispatch(MapAction.SetVarioLinkState(connected = true, scanning = false))
        // Bir Billing carries a demo task so the next-waypoint guidance has a
        // target to advance through as the replay flies its cylinders.
        val demoTask = if (id == "birbilling") birBillingDemoTask(flight) else null
        demoTask?.let {
            store.dispatch(MapAction.AddTask(it))
            store.dispatch(MapAction.SelectTask(it.id))
        }
        // Two-device buddy test: a BROADCAST_REPLAY id also transmits each fix over LoRa so the other
        // phone sees this pilot as a live buddy. Replay at 1× (not the 8× bench speed) so the
        // receiver's climb derivation (alt delta / wall-clock receipt gap) isn't inflated.
        val broadcastReplay = com.ternparagliding.BuildConfig.DEBUG && id in BROADCAST_REPLAY_IDS
        var lastReplayTxMs: Long? = null
        // Choose the fix stream: a broadcast test runs SYNCHRONISED (so both phones fly the true
        // simultaneous gaggle) at 1×; the plain bench replay runs at the 8× scrub speed.
        val replayFlow = if (broadcastReplay) {
            // The phone BECOMES this pilot: stop real GPS so it can't (a) fight the replay as our
            // own-location, or (b) double-broadcast the real desk position under our board's node
            // (which would make the buddy oscillate between here and Bir Billing). Restored in finally.
            locationService.stopLocationUpdates()
            store.dispatch(MapAction.SetCameraFollow(true))
            val sessionStart = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.ternparagliding.flight.IgcReplaySource.birBillingSyncStartMs()
            }
            // Shared wall anchor: the next 20 s boundary, ≥10 s out, computed identically on both
            // phones (clocks are NTP-synced) so tapping both within the window starts them aligned.
            val now = System.currentTimeMillis()
            val window = 20_000L
            var anchor = ((now / window) + 1) * window
            if (anchor - now < 10_000L) anchor += window
            Log.i(TAG, "synced broadcast '$id': anchorIn=${anchor - now}ms sessionStart=$sessionStart")
            if (sessionStart != null) {
                com.ternparagliding.flight.IgcReplaySource(flight, 1).fixesSynced(anchor, sessionStart)
            } else {
                com.ternparagliding.flight.IgcReplaySource(flight, 1).fixes() // fallback: unsynced 1×
            }
        } else {
            com.ternparagliding.flight.IgcReplaySource(flight, com.ternparagliding.flight.IgcReplaySource.DEFAULT_SPEED).fixes()
        }
        try {
            replayFlow.collect { fix ->
                onDeckFix(fix)
                if (broadcastReplay && fix.hasPosition) {
                    // Adopt the replay as our own position so distance / relative-altitude to the
                    // buddy read true (both pilots are at Bir Billing) and the camera follows it.
                    fix.lat?.let { la -> fix.lon?.let { lo ->
                        store.dispatch(MapAction.UpdateUserLocation(GeoPoint(la, lo, fix.gpsAltitudeM ?: 0.0)))
                    } }
                    if (com.ternparagliding.mezulla.PositionBroadcastPolicy.shouldBroadcast(true, lastReplayTxMs, fix.timeMs)) {
                        fix.toPeerPositionFix()?.let { pf ->
                            lastReplayTxMs = fix.timeMs
                            connectionManager?.activeBleConnection()?.let { conn ->
                                coroutineScope.launch { runCatching { conn.sendOwnPosition(pf) } }
                            }
                        }
                    }
                }
            }
        } finally {
            buddyPlayback.value = null // stop driving buddies on end/cancel
            buddyNodes.value = emptyMap()
            demoTask?.let {
                store.dispatch(MapAction.DeselectTask)
                store.dispatch(MapAction.RemoveTask(it.id))
            }
            if (broadcastReplay && store.state.value.hasLocationPermission) {
                locationService.startLocationUpdates() // restore real GPS after the test
            }
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
        // Track the last centre we drove the camera to ourselves (not read back from cameraState,
        // whose position getter throws until the map is ready). Lets us detect a large jump.
        var lastDriven: GeoPoint? = null
        store.state
            .map { Triple(it.center, it.zoom, it.rotation) }
            .distinctUntilChanged()
            .collectLatest { (center, zoom, rotation) ->
                // Only stand down while the user is *actively* gesturing. moveReason is sticky — it
                // reports the *last* move's reason and stays GESTURE long after a drag ends — so
                // gating on it alone permanently blocks programmatic drives (recenter, and the
                // replay follow-cam) until some other move happens. isCameraMoving is the live signal.
                val userGesturing = cameraState.isCameraMoving &&
                    cameraState.moveReason == CameraMoveReason.GESTURE
                if (!userGesturing) {
                    center?.let {
                        val target = CameraPosition(
                            target = Position(longitude = it.longitude, latitude = it.latitude),
                            zoom = zoom,
                            bearing = rotation.toDouble(),
                        )
                        // Snap (don't animate) across large jumps — e.g. a bench replay starting
                        // while the map is on the far side of the world. animateTo would be cancelled
                        // by the next fix every ~125ms and never traverse the gap, freezing the camera.
                        val prev = lastDriven
                        val farJump = prev == null ||
                            kotlin.math.abs(prev.latitude - it.latitude) > 2.0 ||
                            kotlin.math.abs(prev.longitude - it.longitude) > 2.0
                        if (farJump) cameraState.position = target else cameraState.animateTo(target)
                        lastDriven = it
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
                        // The pilot grabbed the map — hand them control. Follow re-engages only when
                        // they tap Recenter. (No-op on the ground, where follow is gated off anyway.)
                        if (store.state.value.cameraFollow) store.dispatch(MapAction.SetCameraFollow(false))
                        lastDispatchedPos = target
                    }
                }
            }
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
            // Move-mode commit: when a waypoint is armed for moving (the "Move on Map"
            // button → StartWaypointDrag), a single tap drops it at the tapped point and
            // ends the move. Outside move-mode a plain tap does nothing here (taps on
            // waypoints/spots are handled by their own layers). Consume only when we act.
            onMapClick = { pos, _ ->
                val st = store.state.value
                val finite = pos.latitude.isFinite() && pos.longitude.isFinite()
                when {
                    // Move a standalone library spot (Workflow A) to the tapped point.
                    st.movingSpotId != null && finite -> {
                        store.dispatch(MapAction.CommitSpotMove(pos.latitude, pos.longitude))
                        ClickResult.Consume
                    }
                    // Move a task point (move-mode armed from its editor).
                    st.selectedWaypoint?.isDragging == true && finite -> {
                        store.dispatch(MapAction.UpdateWaypointDrag(pos.latitude, pos.longitude))
                        store.dispatch(MapAction.EndWaypointDrag)
                        ClickResult.Consume
                    }
                    else -> ClickResult.Pass
                }
            },
            // Long-press is intentionally inert on the map. Task/waypoint creation goes
            // through the explicit paths — "Create New Task" (task list) and the
            // "Add from map" crosshair (which reuses the LongPressMap *action* with
            // forceCreate). Auto-creating a task on every long-press was redundant with
            // those flows and the source of accidental tasks (the recurring stray "1").
            // Pass the gesture through so it never drops a point.
        ) {
            // Nerd Font for marker glyphs (task goal checkered-flag, waypoint flag, peers).
            val markerCtx = LocalContext.current
            val nerdFont = remember(markerCtx) {
                androidx.core.content.res.ResourcesCompat.getFont(
                    markerCtx, com.ternparagliding.R.font.jetbrains_mono_nerd_regular,
                )
            }

            // Task overlay — resolve library references so linked points render at
            // their current library position/identity (Stage B2).
            val visibleTasks = state.resolvedTasks().filter { it.isVisible }
            if (visibleTasks.isNotEmpty()) {
                // While any move-mode is armed (task point or spot), disable the waypoint
                // tap-to-select so the tap falls through to onMapClick and commits the move
                // — otherwise a tap landing on a marker (the moved point is often centred)
                // would re-select instead of moving.
                val moving = state.selectedWaypoint?.isDragging == true || state.movingSpotId != null
                com.ternparagliding.overlay.task.TaskLayer(
                    tasks = visibleTasks,
                    selectedWaypointId = state.selectedWaypoint?.let { "${it.taskId}:${it.waypointId}" },
                    activeWaypointId = state.activeWaypointId,
                    nerdFont = nerdFont,
                    // Phase 0 — tap a waypoint to select it (hit-test → selection). The
                    // foundation for every touch interaction; selection opens the editor today.
                    onWaypointClick = if (moving) null else { taskId, wpId ->
                        store.dispatch(MapAction.SelectWaypoint(taskId, wpId))
                    },
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

            // Standalone waypoint library — first-class map markers (tap → weather/
            // Flyability), the same treatment PG spots get.
            com.ternparagliding.overlay.waypoint.WaypointLibraryOverlay(store = store)

            // Thermal-hotspot overlay (kk7.ch) — colour-coded reliability dots
            com.ternparagliding.overlay.thermal.ThermalHotspotOverlay(
                store = store,
                cameraState = cameraState,
            )

            // Weather-hazard halos
            com.ternparagliding.overlay.hazard.HazardOverlay(store = store)

            // Peer markers (reuse the marker nerdFont loaded above)
            val ctx = LocalContext.current
            val gruppoFont = remember {
                androidx.core.content.res.ResourcesCompat.getFont(ctx, com.ternparagliding.R.font.gruppo_regular)
            }
            com.ternparagliding.overlay.mezulla.PeerLayer(
                peers = state.peerState.peers,
                lastEventTime = state.peerState.lastEventTime,
                ownLocation = state.userLocation,
                nerdFont = nerdFont,
                labelFont = gruppoFont,
                altitudeUnit = state.settingsState.altitudeUnit,
            )
        }

        com.ternparagliding.overlay.task.TaskProximityOverlay(store = store)

        // Active-task navigation: derive the "next" waypoint + auto-advance on
        // cylinder entry, then point at it buddy-style (on-map highlight above +
        // off-screen edge chip below).
        com.ternparagliding.overlay.task.TaskProgressOverlay(store = store)

        val activeWaypoint = remember(state.selectedTaskId, state.activeWaypointId, state.tasks, state.waypointLibrary) {
            state.resolvedSelectedTask()
                ?.waypoints?.find { it.id == state.activeWaypointId }
        }
        activeWaypoint?.let { wp ->
            com.ternparagliding.overlay.task.OffScreenWaypointIndicator(
                target = GeoPoint(wp.lat, wp.lon),
                label = (wp.displayName ?: "WP").uppercase(),
                roleColor = androidx.compose.ui.graphics.Color(com.ternparagliding.overlay.task.cylinderColor(wp.type)),
                ownLocation = state.userLocation,
                ownAltitudeM = if (state.flightDeck.varioConnected) state.flightDeck.altitudeM else null,
                targetAltM = wp.alt,
                cameraState = cameraState,
            )
        }

        com.ternparagliding.overlay.mezulla.OffScreenPeerIndicators(
            peers = state.peerState.peers,
            ownLocation = state.userLocation,
            cameraState = cameraState,
            // Use the peer timeline as "now" so replayed buddies (flight-time timestamps) aren't
            // all judged LOST against the wall clock — matches how PeerLayer ages on-map peers.
            now = state.peerState.lastEventTime,
        )

        // Next-waypoint navigation read for the rosette + the readout under it: the
        // bearing/distance to the active waypoint and its task ordinal + place name.
        // Driven by the plain GPS fix (state.userLocation) so it works with or without
        // a vario, exactly like the off-screen chip.
        val nextWpNav = remember(activeWaypoint, state.userLocation, state.selectedTaskId, state.tasks, state.waypointLibrary) {
            val wp = activeWaypoint
            val own = state.userLocation
            if (wp == null || own == null) null
            else {
                val task = state.resolvedSelectedTask()
                val ordinal = task?.waypoints?.indexOfFirst { it.id == wp.id }?.takeIf { it >= 0 }?.plus(1)
                val target = GeoPoint(wp.lat, wp.lon)
                NextWpNav(
                    bearingDeg = own.bearingTo(target),
                    distanceM = own.distanceToAsDouble(target),
                    number = ordinal?.toString() ?: "•",
                    name = wp.displayName ?: "Next",
                )
            }
        }

        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                // systemBars (not just statusBars): in landscape the nav bar moves to the right
                // edge, so the compass must inset from it too or it sits under an un-tappable bar.
                .padding(WindowInsets.systemBars.asPaddingValues())
                .padding(COMPASS_PADDING),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            com.ternparagliding.mezulla.ui.MezullaStatusBadge(
                peerState = state.peerState,
                onClick = { showTeamSheet.value = true },
            )
            if (state.compassVisible) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
                    // The needle points to true north, so it rotates opposite the camera
                    // bearing (MapLibre bearing is clockwise from north): bearing 90° (facing
                    // east) → north is to the left → rotate −90°.
                    Compass(
                        rotation = -state.rotation,
                        size = 56.dp,
                        windFromDeg = if (state.flightDeck.varioConnected) state.flightDeck.windFromDeg else null,
                        waypointBearingDeg = nextWpNav?.bearingDeg,
                        waypointLabel = nextWpNav?.number,
                        // Tap to reset north-up. (While a replay is track-up following, the next fix
                        // re-orients — the gesture is for the free/idle map.)
                        onTap = {
                            store.dispatch(MapAction.UpdateRotation(0f))
                            smoothedBearing.value = Double.NaN; lastBearing.value = Double.NaN
                        },
                    )
                    // The whole nav read in one place (design-locked): name + distance
                    // under the compass so direction + identity + range glance together.
                    nextWpNav?.let {
                        NextWaypointReadout(
                            name = it.name.uppercase(),
                            distanceText = formatNavDistance(it.distanceM),
                        )
                    }
                }
            }
        }

        // Team sheet: roster + the SAFETY/CLIMB/TACTICAL read mode, opened from the buddies chip.
        if (showTeamSheet.value) {
            MezullaTeamSheet(
                store = store,
                onDismiss = { showTeamSheet.value = false },
            )
        }

        // The planning HUD used to float here (task distance / FAI / trajectory weather),
        // but it overlapped the task panel + control dock. Its stats now live in the task
        // panel header and its 4D weather is a collapsible "Trajectory weather" section
        // inside the panel — one task surface, no overlap.

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

        // Tag confirmation (haptic + brief flash) when a cylinder is reached — drawn
        // last so the flash sits on top of the whole deck.
        com.ternparagliding.overlay.task.TagFeedbackOverlay(store = store)

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

        // ── Add-from-map mode: centre crosshair + drop bar ──────────────────
        // Chrome (HUD + task panel) is hidden by the addingWaypoint flag, so the
        // map is fully visible. Pan a target under the crosshair, then "Add point
        // here" drops it at the camera centre into the selected task.
        if (state.addingWaypoint) {
            AddWaypointReticle(modifier = Modifier.align(Alignment.Center))
            val addCount = state.tasks.find { it.id == state.selectedTaskId }?.waypoints?.size ?: 0
            // Snap radius around the crosshair — fingertip-sized, so panning a marker
            // "under" the crosshair reliably picks it.
            val snapTolPx = with(projectionDensity) { 56.dp.toPx() }
            AddWaypointBar(
                pointCount = addCount,
                onAddHere = {
                    val taskId = state.selectedTaskId
                    val t = cameraState.position.target
                    if (taskId != null && t.latitude.isFinite() && t.longitude.isFinite()) {
                        val proj = runCatching { cameraState.projection }.getOrNull()
                        fun pxOf(lat: Double, lon: Double): androidx.compose.ui.geometry.Offset? =
                            proj?.let { p ->
                                val dp = runCatching {
                                    p.screenLocationFromPosition(Position(longitude = lon, latitude = lat))
                                }.getOrNull() ?: return@let null
                                with(projectionDensity) { androidx.compose.ui.geometry.Offset(dp.x.toPx(), dp.y.toPx()) }
                            }
                        // The crosshair sits at the camera centre; snap to the nearest
                        // *existing* point under it — a library waypoint OR a PG spot — and
                        // only mint a new USER point when nothing is there. PG spots are
                        // added by capturing them; library spots by reference.
                        val centerPx = pxOf(t.latitude, t.longitude)
                        // (px-distance, action) for every candidate within the snap radius.
                        val candidates = if (centerPx == null) emptyList() else buildList {
                            state.waypointLibrary.forEach { wp ->
                                pxOf(wp.lat, wp.lon)?.let { px ->
                                    val d = (px - centerPx).getDistance()
                                    if (d <= snapTolPx) add(d to { store.dispatch(MapAction.AddLibraryWaypointsToTask(taskId, listOf(wp.id))) })
                                }
                            }
                            com.ternparagliding.overlay.pgspot.pgSpotPoints(state.pgSpotGeoJson).forEach { pg ->
                                pxOf(pg.lat, pg.lon)?.let { px ->
                                    val d = (px - centerPx).getDistance()
                                    if (d <= snapTolPx) add(d to {
                                        store.dispatch(MapAction.AddPgSpotToTask(
                                            taskId = taskId, pgSpotId = pg.id, code = pg.name,
                                            name = pg.name, lat = pg.lat, lon = pg.lon, alt = pg.alt,
                                        ))
                                    })
                                }
                            }
                        }
                        val snapAction = candidates.minByOrNull { it.first }?.second
                        if (snapAction != null) {
                            snapAction()
                        } else {
                            store.dispatch(MapAction.LongPressMap(GeoPoint(t.latitude, t.longitude), forceCreate = true))
                        }
                    }
                },
                onDone = { store.dispatch(MapAction.StopAddWaypoint) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(WindowInsets.systemBars.asPaddingValues())
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
