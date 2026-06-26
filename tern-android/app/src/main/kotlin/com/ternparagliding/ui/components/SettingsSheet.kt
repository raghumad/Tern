package com.ternparagliding.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tornado
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.Spacer
import com.ternparagliding.device.BleDeviceScanner
import com.ternparagliding.device.DeviceType
import com.ternparagliding.device.ScanCandidate
import com.ternparagliding.mezulla.connection.LinkState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ternparagliding.R
import com.ternparagliding.ui.screens.MAP_VIEW_SATELLITE
import com.ternparagliding.ui.screens.MAP_VIEW_TERRAIN


import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.ternparagliding.device.ConnectionEvent
import com.ternparagliding.mezulla.demo.AravisDemoReplay
import com.ternparagliding.mezulla.pairing.PairingOrchestrator
import com.ternparagliding.mezulla.pairing.PairingState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    store: com.ternparagliding.redux.MapStore = viewModel(),
    demoReplay: AravisDemoReplay? = null,
    pairingOrchestrator: PairingOrchestrator? = null,
) {
    val state by store.state.collectAsState()
    val settingsState = state.settingsState
    var showAddDevice by remember { mutableStateOf(false) }

    // Team plumbing: create/join just record the team *intent*; the reconcile step in
    // MapViewContainer writes it to the board (set_team) when the link is up. So this works offline.
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // The live BLE link to the board — used to rename it (set_owner). Pulled the
    // same way MapViewContainer does; null until the app + a board are present.
    val connectionManager = remember(ctx) {
        (ctx.applicationContext as? com.ternparagliding.TernApplication)?.connectionManager
    }
    // Drives the "edit board" dialog opened by the pencil button on the board row.
    var showEditBoard by remember { mutableStateOf(false) }

    if (showAddDevice) {
        AddDeviceDialog(
            onPickVario = { candidate ->
                // Remembering a vario un-pauses it (reducer), so it auto-connects + self-heals.
                store.dispatch(com.ternparagliding.redux.MapAction.SetRememberedVario(candidate.mac, candidate.name))
                showAddDevice = false
            },
            onDismiss = { showAddDevice = false },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp).testTag("settings_list")) {

            // Connections — the two Bluetooth devices Tern pairs with, each with
            // its live status: the XC Tracer vario (sensor link) and the Mezulla
            // board (mesh). Connecting/pairing is infrequent, so it lives here
            // rather than on the flight dock.
            item {
                Text("Connections", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

                // ── XC Tracer vario — shown once the pilot has added THEIR device ──
                val varioConnected = state.flightDeck.varioConnected
                val varioScanning = state.flightDeck.varioScanning
                val rememberedMac = settingsState.rememberedVarioMac
                val rememberedName = settingsState.rememberedVarioName
                val varioPaused = settingsState.varioPaused
                val varioTint = when {
                    varioConnected -> Color(0xFF22C55E)
                    varioScanning -> Color(0xFFF59E0B)
                    else -> Color(0xFF94A3B8)
                }
                if (rememberedMac != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when {
                                varioConnected -> Icons.Filled.BluetoothConnected
                                varioScanning -> Icons.Filled.BluetoothSearching
                                else -> Icons.Filled.Bluetooth
                            },
                            contentDescription = null, tint = varioTint,
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            // Name the *specific* remembered device (with its MAC tail) so the
                            // pilot knows this is THEIRS, not whichever was closest.
                            Text("${rememberedName ?: "XC Tracer"}  ··${BleDeviceScanner.macTail(rememberedMac)}", fontSize = 14.sp)
                            Text(
                                when {
                                    varioConnected -> "Streaming"
                                    varioScanning -> "Searching…"
                                    varioPaused -> "Paused"
                                    else -> "Saved — reconnecting…"
                                },
                                fontSize = 12.sp, color = varioTint,
                            )
                        }
                        OutlinedButton(
                            // A remembered vario runs by default; this pauses/resumes it.
                            onClick = { store.dispatch(com.ternparagliding.redux.MapAction.SetVarioPaused(!varioPaused)) },
                            modifier = Modifier.height(40.dp).testTag("btn_toggle_vario"),
                        ) {
                            Text(if (varioPaused) "Connect" else "Disconnect", fontSize = 14.sp)
                        }
                    }
                    TextButton(
                        onClick = { store.dispatch(com.ternparagliding.redux.MapAction.SetRememberedVario(null, null)) },
                        modifier = Modifier.testTag("btn_forget_vario"),
                    ) { Text("Forget / choose another", fontSize = 12.sp, color = Color(0xFF94A3B8)) }

                    // Connection log — status + every drop/heal, so the pilot can see it healed.
                    VarioConnectionLog(state.flightDeck.connectionEvents)
                }

                // ── Mezulla board (mesh) — paired board or scan hint ───────
                if (pairingOrchestrator != null) {
                    val pairingState by pairingOrchestrator.state.collectAsState()
                    val pairedNodeId = pairingOrchestrator.getPairedNodeId()
                    val pairedName = pairingOrchestrator.getPairedDeviceName()
                    // While the persistent BLE link is being established
                    // (post-claim, pre-link-up) show a progress row so the
                    // pilot knows we are still working and roughly how long
                    // it takes. The persisted name above already reflects
                    // the claim, so we render this in addition rather than
                    // instead of it.
                    if (pairingState is PairingState.EstablishingLink) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .testTag("pairing_establishing_link"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                "Establishing link... (this can take up to 30 seconds)",
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                                fontSize = 14.sp,
                                color = Color(0xFF94A3B8),
                            )
                        }
                    }
                    if (pairedNodeId != null) {
                        // Prefer the board's real Meshtastic owner name (the name on
                        // its OLED), learned from its self NodeInfo — so the label here
                        // matches the board, the buddy list, and the screen. Fall back
                        // to the pairing-time label only until that NodeInfo arrives.
                        val selfBoard = state.peerState.selfBoard
                        val boardLabel = selfBoard?.longName?.takeIf { it.isNotBlank() }
                            ?: selfBoard?.shortName?.takeIf { it.isNotBlank() }
                            ?: (pairedName ?: pairedNodeId).substringBefore("_").let { "Mezulla $it" }
                        // Live link status + battery, mirroring the vario row.
                        val linkUp = state.peerState.linkState == LinkState.UP
                        val boardTint = if (linkUp) Color(0xFF22C55E) else Color(0xFFF59E0B)
                        val boardNode = runCatching { pairedNodeId.toLong(16) }.getOrNull()
                        val boardBattery = boardNode?.let { state.peerState.peers[it]?.lastTelemetry?.batteryPercent }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (linkUp) Icons.Filled.BluetoothConnected else Icons.Filled.BluetoothSearching,
                                contentDescription = null, tint = boardTint,
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(boardLabel, fontSize = 14.sp)
                                Text(if (linkUp) "Connected" else "Reconnecting…", fontSize = 12.sp, color = boardTint)
                            }
                            // Battery (when the board reports telemetry), like the vario's 🔋.
                            // Meshtastic reports 101 for "on external/USB power" — show that
                            // plainly rather than a confusing "101%".
                            boardBattery?.let {
                                val label = if (it > 100) "🔌 ext" else "🔋$it%"
                                Text(label, fontSize = 13.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(end = 8.dp))
                            }
                            // Edit (rename the board) then forget (trash) sit inline beside the
                            // board name. Edit is only meaningful on a live link (set_owner needs
                            // the board), so it's disabled while reconnecting.
                            IconButton(
                                onClick = { showEditBoard = true },
                                enabled = linkUp,
                                modifier = Modifier.testTag("btn_edit_board"),
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit board")
                            }
                            IconButton(
                                onClick = {
                                    pairingOrchestrator.forgetBoard()
                                    onDismiss()
                                },
                                modifier = Modifier.testTag("btn_forget_board"),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Forget board", tint = Color(0xFFEF4444))
                            }
                        }

                        if (showEditBoard) {
                            val currentLong = selfBoard?.longName?.takeIf { it.isNotBlank() } ?: boardLabel
                            val currentShort = selfBoard?.shortName?.takeIf { it.isNotBlank() } ?: ""
                            // Snapshot the board's current display config so the dialog can pre-select
                            // the live values and we only write the fields the pilot actually changed.
                            val curScreenSecs = connectionManager?.currentScreenOnSecs()
                            val curFlip = connectionManager?.currentFlipScreen() ?: false
                            EditBoardDialog(
                                currentLongName = currentLong,
                                currentShortName = currentShort,
                                currentScreenOnSecs = curScreenSecs,
                                currentFlipScreen = curFlip,
                                onSave = { longName, shortName, screenSecs, flip ->
                                    showEditBoard = false
                                    val node = selfBoard?.nodeNumber
                                        ?: runCatching { pairedNodeId.toLong(16) }.getOrNull()
                                    scope.launch {
                                        // Name — only if it changed. setOwner persists it on the board
                                        // AND bursts the new NodeInfo to buddies (push-on-change).
                                        // Reflect it immediately on success so the pilot sees it; on
                                        // failure (link dropped mid-write) leave the label alone — the
                                        // board re-asserts it anyway.
                                        if (longName != currentLong || shortName != currentShort) {
                                            val ok = connectionManager?.setOwner(longName, shortName) ?: false
                                            if (ok && node != null) {
                                                store.dispatch(
                                                    com.ternparagliding.mezulla.redux.PeerAction.SelfBoardIdentified(
                                                        com.ternparagliding.mezulla.connection.PeerIdentity.fromNodeNumber(
                                                            nodeNumber = node,
                                                            longName = longName,
                                                            shortName = shortName,
                                                        ),
                                                        java.time.Instant.now(),
                                                    ),
                                                )
                                            }
                                        }
                                        // Display — one write carrying just the changed fields.
                                        val screenChanged = screenSecs != null && screenSecs != curScreenSecs
                                        val flipChanged = flip != curFlip
                                        if (screenChanged || flipChanged) {
                                            connectionManager?.setDisplay(
                                                screenOnSecs = if (screenChanged) screenSecs else null,
                                                flipScreen = if (flipChanged) flip else null,
                                            )
                                        }
                                    }
                                },
                                onDismiss = { showEditBoard = false },
                            )
                        }

                        // ── Team (the board's PRIMARY channel) — create / share / join ──
                        // Create/join record *intent* (works offline); MapViewContainer's reconcile
                        // effect writes it to the board on the next live link.
                        MezullaTeamSection(
                            teamName = settingsState.teamName,
                            teamShareLink = settingsState.teamShareLink,
                            applied = settingsState.teamShareLink != null &&
                                settingsState.teamShareLink == settingsState.teamAppliedLink,
                            linkUp = linkUp,
                            onCreate = { name ->
                                if (name.isNotBlank()) {
                                    val team = com.ternparagliding.mezulla.pairing.TeamLink.create(name)
                                    store.dispatch(com.ternparagliding.redux.MapAction.SetTeam(
                                        team.name, com.ternparagliding.mezulla.pairing.TeamLink.encode(team), "manual",
                                    ))
                                }
                            },
                            onJoin = { link ->
                                com.ternparagliding.mezulla.pairing.TeamLink.parse(link)?.let { team ->
                                    store.dispatch(com.ternparagliding.redux.MapAction.SetTeam(
                                        team.name, com.ternparagliding.mezulla.pairing.TeamLink.encode(team), "manual",
                                    ))
                                }
                            },
                            onLeave = {
                                store.dispatch(com.ternparagliding.redux.MapAction.SetTeam(null, null, null))
                            },
                            onShare = { link ->
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, link)
                                }
                                ctx.startActivity(android.content.Intent.createChooser(send, "Share team link"))
                            },
                            onCopy = { link -> clipboard.setText(AnnotatedString(link)) },
                        )
                    }
                }

                // One unified entry for EVERY Bluetooth device — vario, Mezulla, future sensors.
                // Opens the same picker; the pilot taps theirs (or scans a board's QR).
                OutlinedButton(
                    onClick = { showAddDevice = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp).testTag("btn_add_device"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Add a device", fontSize = 16.sp)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            item {
                Text("Map Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                SettingsToggleRow(
                    text = "Airspaces",
                    isChecked = state.overlayState.airspaces.enabled,
                    onCheckedChange = { enabled ->
                        store.dispatch(com.ternparagliding.redux.MapAction.SetSettingsOverlayEnabled("airspaces", enabled))
                    }
                ) {
                    Icon(Icons.Filled.AirplanemodeActive, contentDescription = "Airspaces")
                }
                SettingsToggleRow(
                    text = "Hotspots",
                    isChecked = state.overlayState.thermalHotspots.enabled,
                    onCheckedChange = { enabled ->
                        store.dispatch(com.ternparagliding.redux.MapAction.SetSettingsOverlayEnabled("hotspots", enabled))
                    }
                ) {
                    Icon(Icons.Filled.Tornado, contentDescription = "Hotspots")
                }
                SettingsToggleRow(
                    text = "PGSpots",
                    isChecked = state.overlayState.pgSpots.enabled,
                    onCheckedChange = { enabled ->
                        store.dispatch(com.ternparagliding.redux.MapAction.SetSettingsOverlayEnabled("pgspots", enabled))
                    }
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.kjartan_birgisson),
                        contentDescription = "PGSpots",
                        modifier = Modifier.size(24.dp)
                    )
                }
                SettingsToggleRow(
                    text = "Waypoints",
                    isChecked = state.overlayState.waypoints.enabled,
                    onCheckedChange = { enabled ->
                        store.dispatch(com.ternparagliding.redux.MapAction.SetSettingsOverlayEnabled("waypoints", enabled))
                    }
                ) {
                    // Same flag glyph (in the map's waypoint violet) the map draws for a waypoint.
                    WaypointGlyph(tint = WaypointViolet, fontSize = 20.sp)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            item {
                Text("Units", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                SettingsPickerRow(
                    label = "Map Style",
                    items = listOf("Terrain", "Satellite"),
                    selectedItem = if (state.mapStyle == "terrain") "Terrain" else "Satellite",
                    onItemSelected = {
                        val newStyle = if (it == "Terrain") "terrain" else "satellite"
                        store.dispatch(com.ternparagliding.redux.MapAction.UpdateMapStyle(newStyle))
                        // Map style changes are handled through Redux state observation in MapViewModel
                    }
                )
                SettingsPickerRow(
                    label = "Temperature",
                    items = listOf("°F", "°C", "K"),
                    selectedItem = settingsState.temperatureUnit,
                    onItemSelected = { unit ->
                        store.dispatch(com.ternparagliding.redux.MapAction.SetUnitPreference("temperature", unit))
                    }
                )
                SettingsPickerRow(
                    label = "Distance",
                    items = listOf("km", "mi", "fur"),
                    selectedItem = settingsState.distanceUnit,
                    onItemSelected = { unit ->
                        store.dispatch(com.ternparagliding.redux.MapAction.SetUnitPreference("distance", unit))
                    }
                )
                SettingsPickerRow(
                    label = "Speed",
                    items = listOf("kn", "mph", "kph", "m/s"),
                    selectedItem = settingsState.speedUnit,
                    onItemSelected = { unit ->
                        store.dispatch(com.ternparagliding.redux.MapAction.SetUnitPreference("speed", unit))
                    }
                )
                SettingsPickerRow(
                    label = "Altitude",
                    items = listOf("ft", "m", "in"),
                    selectedItem = settingsState.altitudeUnit,
                    onItemSelected = { unit ->
                        store.dispatch(com.ternparagliding.redux.MapAction.SetUnitPreference("altitude", unit))
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Demo replay section — debug builds only (a dev/showcase tool, not for pilots).
            if (com.ternparagliding.BuildConfig.DEBUG && demoReplay != null) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    Text("Demo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                    val isRunning = demoReplay.isRunning
                    OutlinedButton(
                        onClick = {
                            if (isRunning) {
                                demoReplay.stop()
                            } else {
                                demoReplay.start()
                                onDismiss()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("demo_aravis_replay_button"),
                    ) {
                        Text(
                            text = if (isRunning) "Stop Aravis Replay" else "Demo: Aravis Replay",
                            fontSize = 16.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Flight-deck bench replay — fly a bundled IGC through the live deck (no hardware).
            // Debug builds only (dev/showcase tool, incl. the over-LoRa buddy broadcast test).
            if (com.ternparagliding.BuildConfig.DEBUG) item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text("Flight deck (bench replay)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                val replayingId = state.flightDeck.replayFlightId
                listOf("birbilling" to "Bir Billing", "aravis" to "Aravis").forEach { (id, label) ->
                    val running = replayingId == id
                    OutlinedButton(
                        onClick = {
                            if (running) {
                                store.dispatch(com.ternparagliding.redux.MapAction.StopDeckReplay)
                            } else {
                                store.dispatch(com.ternparagliding.redux.MapAction.StartDeckReplay(id))
                                onDismiss()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(bottom = 8.dp)
                            .testTag("deck_replay_$id"),
                    ) {
                        Text(text = if (running) "Stop $label" else "Replay: $label", fontSize = 16.sp)
                    }
                }

                // Two-device buddy test (debug): each phone replays one Bir Billing pilot AND
                // broadcasts it over LoRa, so the other phone sees a real moving buddy. Assign
                // Richard → Ulefone/LilyGo, Barney → Pixel/Heltec.
                if (com.ternparagliding.BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Buddy broadcast test (over LoRa) — tap both phones within ~15s to fly the gaggle in sync",
                        fontSize = 12.sp, color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    listOf(
                        "birbilling-richard" to "Richard",
                        "birbilling-barney" to "Barney",
                        "birbilling-ariel" to "Ariel",
                    ).forEach { (id, who) ->
                        val running = replayingId == id
                        OutlinedButton(
                            onClick = {
                                if (running) {
                                    store.dispatch(com.ternparagliding.redux.MapAction.StopDeckReplay)
                                } else {
                                    store.dispatch(com.ternparagliding.redux.MapAction.StartDeckReplay(id))
                                    onDismiss()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(bottom = 8.dp)
                                .testTag("deck_broadcast_$id"),
                        ) {
                            Text(
                                text = if (running) "Stop broadcasting $who" else "Broadcast as $who (Bir Billing)",
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Edit the paired Mezulla board. Covers the Meshtastic owner name (long name on
 * the OLED + the buddy label on other phones, and the ≤4-char short badge) plus the
 * OLED display knobs (**Screen timeout** / **Flip screen**).
 *
 * Renaming both persists on the board and bursts the new NodeInfo to buddies
 * (push-on-change), so there's no periodic-broadcast knob: a name change is an
 * event, pushed once when it happens, not polled. Each control pre-selects the
 * board's current value; [onSave] reports them and the caller writes only what
 * changed. Short name auto-fills from the long name as the pilot types, until they
 * edit it themselves.
 */
@Composable
private fun EditBoardDialog(
    currentLongName: String,
    currentShortName: String,
    currentScreenOnSecs: Int?,
    currentFlipScreen: Boolean,
    onSave: (longName: String, shortName: String, screenOnSecs: Int?, flipScreen: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var longName by remember { mutableStateOf(currentLongName) }
    // Once the pilot touches the short name we stop auto-deriving it.
    var shortTouched by remember { mutableStateOf(currentShortName.isNotBlank()) }
    var shortName by remember { mutableStateOf(currentShortName.ifBlank { deriveShortName(currentLongName) }) }
    var screenSecs by remember { mutableStateOf(currentScreenOnSecs) }
    var flip by remember { mutableStateOf(currentFlipScreen) }
    val trimmedLong = longName.trim()
    val trimmedShort = shortName.trim().take(4)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit board") },
        text = {
            Column {
                OutlinedTextField(
                    value = longName,
                    onValueChange = {
                        longName = it
                        if (!shortTouched) shortName = deriveShortName(it)
                    },
                    label = { Text("Board name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("edit_board_long_name"),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortTouched = true; shortName = it.take(4) },
                    label = { Text("Short name (≤4)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("edit_board_short_name"),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Shown on the board's screen and as your label to buddies. Changes are pushed to buddies right away.",
                    fontSize = 11.sp, color = Color(0xFF94A3B8),
                )

                Spacer(Modifier.height(16.dp))
                Text("Screen timeout", fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                IntChoiceRow(
                    options = listOf("1 min" to 60, "5 min" to 300, "10 min" to 600, "30 min" to 1800),
                    selectedValue = screenSecs,
                    onSelect = { screenSecs = it },
                    testTagPrefix = "edit_board_screen",
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Flip screen", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(
                        checked = flip,
                        onCheckedChange = { flip = it },
                        modifier = Modifier.testTag("edit_board_flip"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        trimmedLong,
                        trimmedShort.ifBlank { deriveShortName(trimmedLong) },
                        screenSecs,
                        flip,
                    )
                },
                enabled = trimmedLong.isNotEmpty(),
                modifier = Modifier.testTag("edit_board_save"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A compact row of selectable chips backed by Int values; highlights [selected]. */
@Composable
private fun IntChoiceRow(
    options: List<Pair<String, Int>>,
    selectedValue: Int?,
    onSelect: (Int) -> Unit,
    testTagPrefix: String,
) {
    Row {
        options.forEach { (label, value) ->
            val isSelected = value == selectedValue
            OutlinedButton(
                onClick = { onSelect(value) },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .height(36.dp)
                    .padding(horizontal = 2.dp)
                    .testTag("${testTagPrefix}_$value")
                    .semantics { selected = isSelected },
            ) {
                Text(label, fontSize = 12.sp)
            }
        }
    }
}

/** First 4 non-space chars of a name, as a sensible default Meshtastic short badge. */
private fun deriveShortName(longName: String): String =
    longName.filter { !it.isWhitespace() }.take(4)

@Composable
private fun SettingsToggleRow(
    text: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        icon()
        Text(text, modifier = Modifier.weight(1f), fontSize = 16.sp)
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("toggle_$text")
        )
    }
}

@Composable
private fun SettingsPickerRow(
    label: String,
    items: List<String>,
    selectedItem: String,
    onItemSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
        Row {
            items.forEachIndexed { index, item ->
                val isSelected = item == selectedItem
                OutlinedButton(
                    onClick = { onItemSelected(item) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .height(36.dp)
                        .padding(horizontal = 2.dp)
                        .testTag("btn_${label}_$item")
                        // Expose the highlighted choice to the semantics tree so
                        // it's announced by screen readers and assertable in tests
                        // (the colour-only selection was invisible to both).
                        .semantics { selected = isSelected }
                ) {
                    Text(item, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * The vario's connection log — its status and every drop/heal, newest first. Lets the pilot
 * confirm the link is healthy (and that a drop self-healed). Reads the bounded event tail from
 * [com.ternparagliding.redux.FlightDeckState.connectionEvents].
 */
@Composable
private fun VarioConnectionLog(events: List<ConnectionEvent>) {
    if (events.isEmpty()) return
    val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, bottom = 4.dp)
            .testTag("vario_connection_log"),
    ) {
        Text("Connection log", fontSize = 11.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(bottom = 2.dp))
        events.takeLast(6).reversed().forEach { e ->
            val (glyph, label, color) = connectionEventDisplay(e)
            Text("${fmt.format(java.util.Date(e.atMs))}  $glyph $label", fontSize = 12.sp, color = color)
        }
    }
}

/**
 * The unified "Add a device" picker — the SAME flow for every Bluetooth device. Scans nearby,
 * lists each device (vario / Mezulla) with its signal strength so the pilot can tell which is
 * theirs, and lets them tap it. A vario is added by tap; a Mezulla is added by scanning its QR
 * (its claim needs the token the QR carries) — both start here.
 */
@Composable
private fun AddDeviceDialog(onPickVario: (ScanCandidate) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scanner = remember { BleDeviceScanner(context) }
    DisposableEffect(Unit) {
        scanner.start()
        onDispose { scanner.stop() }
    }
    val candidates by scanner.candidates().collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Add a device") },
        text = {
            Column {
                Text(
                    "Hold your phone next to your device — tap yours (the closest is at the top).",
                    fontSize = 12.sp, color = Color(0xFF94A3B8),
                )
                Spacer(Modifier.height(10.dp))
                if (candidates.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text("  Searching…", fontSize = 14.sp)
                    }
                } else {
                    candidates.forEach { c ->
                        DeviceCandidateRow(c) { if (c.type == DeviceType.VARIO) onPickVario(c) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "A Mezulla board is added by scanning its QR code.",
                    fontSize = 11.sp, color = Color(0xFF94A3B8),
                )
            }
        },
    )
}

/** One row in the unified picker: type icon, name + MAC tail, and signal bars. */
@Composable
private fun DeviceCandidateRow(c: ScanCandidate, onClick: () -> Unit) {
    val isVario = c.type == DeviceType.VARIO
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = isVario, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Color(0xFF94A3B8))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text("${c.name}  ··${BleDeviceScanner.macTail(c.mac)}", fontSize = 14.sp)
            Text(
                if (isVario) "vario · tap to add" else "Mezulla board · scan its QR to add",
                fontSize = 11.sp, color = Color(0xFF94A3B8),
            )
        }
        val bars = BleDeviceScanner.signalBars(c.rssi)
        Text("▮".repeat(bars) + "▯".repeat(4 - bars), fontSize = 12.sp, color = Color(0xFF22C55E))
    }
}

/** Map a connection event to a glyph, plain-words label, and colour for the log row. */
private fun connectionEventDisplay(e: ConnectionEvent): Triple<String, String, Color> {
    val green = Color(0xFF22C55E); val red = Color(0xFFEF4444); val amber = Color(0xFFF59E0B); val slate = Color(0xFF94A3B8)
    return when (e.kind) {
        ConnectionEvent.Kind.LINKED -> {
            val healed = e.outageMs?.let { " (healed after ${it / 1000}s)" } ?: ""
            Triple("●", "linked$healed", green)
        }
        ConnectionEvent.Kind.DROPPED -> Triple("○", "dropped — link lost", red)
        ConnectionEvent.Kind.SCANNING -> Triple("◌", "scanning…", amber)
        ConnectionEvent.Kind.CONNECTING -> Triple("◌", "connecting…", amber)
        ConnectionEvent.Kind.OUT_OF_RANGE -> Triple("⊘", "out of range", slate)
        ConnectionEvent.Kind.BLUETOOTH_OFF -> Triple("○", "Bluetooth off", slate)
        ConnectionEvent.Kind.PAUSED -> Triple("○", "disconnected", slate)
    }
}
