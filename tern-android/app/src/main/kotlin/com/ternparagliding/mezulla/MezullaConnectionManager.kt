package com.ternparagliding.mezulla

import android.content.Context
import android.util.Log
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.connection.ble.buildBleConnection
import com.ternparagliding.mezulla.pairing.PairingOrchestrator
import com.ternparagliding.mezulla.pairing.PairingState
import com.ternparagliding.mezulla.redux.PeerAction
import com.ternparagliding.mezulla.redux.PeerMiddleware
import com.ternparagliding.mezulla.pairing.TeamLink
import com.ternparagliding.mezulla.region.LoraRegion
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * Owns the lifecycle of the persistent BLE connection to the paired
 * Mezulla board. Bridges the gap between:
 *
 *  - [PairingOrchestrator] — knows which board is paired (node ID + MAC)
 *  - [BleConnection] / [AndroidBleTransport] — the always-on BLE pipe
 *  - [PeerMiddleware] — pumps mesh events into Redux
 *
 * Lifecycle rules:
 *  - One instance per application, created with application context so
 *    it survives activity recreation.
 *  - On [initialize], if a board is already paired (MAC in SharedPrefs),
 *    starts the persistent connection immediately.
 *  - Observes [PairingOrchestrator.state]; when pairing succeeds, tears
 *    down any existing connection and starts a new one for the freshly
 *    paired board.
 *  - Never creates a second connection if one is already running for the
 *    same MAC.
 */
class MezullaConnectionManager(
    private val appContext: Context,
    private val pairingOrchestrator: PairingOrchestrator,
) {
    companion object {
        private const val TAG = "MezullaConnectionMgr"

        /** SharedPreferences file the team intent is persisted to (see SettingsPersistence). */
        private const val SETTINGS_PREFS = "tern_unit_prefs"

        /**
         * How long we wait for the persistent BLE link to reach
         * [LinkState.UP] after a successful claim before declaring the
         * pairing failed. Observed worst case is ~33s on a real board
         * (board reboots into paired-only mode + re-advertises + GATT
         * handshake). 60s gives generous headroom without leaving the
         * pilot staring at a spinner forever.
         */
        private const val LINK_ESTABLISH_TIMEOUT_MS = 60_000L
    }

    /**
     * Application-scoped coroutine scope. Uses SupervisorJob so a
     * failure in one child (e.g. BLE transport crash) doesn't cancel
     * the pairing-state observer or vice versa.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The currently active BLE connection, if any. */
    private var activeConnection: BleConnection? = null

    /** MAC address of the currently active connection. */
    private var activeMac: String? = null

    /** Node id (hex) of the currently active connection's board. */
    private var activeNodeId: String? = null

    /** The PeerMiddleware wired to the active connection. */
    private var activePeerMiddleware: PeerMiddleware? = null

    /** The middleware's collector job, so we can cancel it on stop. */
    private var middlewareJob: Job? = null

    /**
     * Subscriber that watches the active connection's events for
     * [MeshEvent.LinkStateChange] so we can confirm the pairing flow once
     * the link reaches UP. Separate from the middleware job because the
     * middleware lives for the connection's lifetime; this one is only
     * meaningful during the EstablishingLink window.
     */
    private var linkWatcherJob: Job? = null

    /**
     * Timeout coroutine for the EstablishingLink window. Fires after
     * [LINK_ESTABLISH_TIMEOUT_MS] and flips PairingState to Failed if the
     * link never came up. Cancelled when UP is observed or when the
     * connection is torn down.
     */
    private var linkEstablishTimeoutJob: Job? = null

    /**
     * Watches the live connection for link-UP to reconcile the board's LoRa
     * region to the pilot's current location. Cancelled on teardown.
     */
    private var regionLinkWatcherJob: Job? = null

    /**
     * Watches the Redux GPS fix to reconcile region when the pilot crosses
     * into a different region mid-session (e.g. flies to Europe). Cancelled
     * on teardown.
     */
    private var regionLocationWatcherJob: Job? = null

    /** The MapStore to dispatch peer actions into. Set via [initialize]. */
    private var mapStore: MapStore? = null

    /**
     * Wire everything up and start the persistent connection if a board
     * is already paired. Call once from the activity's onCreate (or
     * Application.onCreate if one is added later).
     *
     * @param store the Redux store to dispatch peer events into
     */
    fun initialize(store: MapStore) {
        if (mapStore != null) {
            // The Activity (and its store ViewModel) was recreated, but we
            // are process-scoped and the BLE link is still live. Keep the
            // connection; just re-point the peer-event stream at the new
            // store so the recreated map gets peer updates.
            Log.i(TAG, "Re-initializing (Activity recreated) — rebinding peer stream to new store, keeping live connection")
            mapStore = store
            rebindMiddleware(store)
            return
        }
        mapStore = store
        Log.i(TAG, "Initializing MezullaConnectionManager")

        // If a board was previously paired AND we still have an OS bond
        // for it, start the connection now. Without a bond, attempting
        // connection is futile — the board (FIXED_PIN) will demand
        // encryption, Android will start SMP with no stored keys, the
        // bond attempt will fail in ~1 s, and the BLE stack ends up in
        // a corrupted state that breaks any pair flow that follows.
        //
        // Common reasons the bond would be missing: pilot tapped
        // "Forget" in Settings, OS bond store got wiped, or the board
        // was reflashed (new BLE identity, old keys no longer valid).
        // In all cases the right answer is "wait for a fresh pair via
        // QR scan", not "thrash trying to reconnect".
        val savedMac = pairingOrchestrator.getPairedDeviceAddress()
        val savedNodeId = pairingOrchestrator.getPairedNodeId()
        if (savedMac != null && savedNodeId != null) {
            // The board runs **NO_PIN** (unencrypted GATT; ownership is the app-layer claim
            // token, not a BLE bond — see docs/architecture/mezulla-security.md). So there is
            // NO OS bond by design. The old `osHasBondFor()` gate assumed a FIXED_PIN firmware
            // and therefore silently blocked EVERY auto-reconnect after an app restart, leaving a
            // paired board "disconnected" until a fresh QR re-pair. Reconnect directly instead —
            // the persistent transport already connects unencrypted, exactly like the claim did.
            Log.i(TAG, "Previously paired board: node=$savedNodeId mac=$savedMac — reconnecting")
            startConnection(savedMac, savedNodeId, freshPairing = false)
        } else {
            Log.i(TAG, "No previously paired board — waiting for pairing")
        }

        // Observe pairing state for new pairings. We now start the BLE
        // connection on EstablishingLink (the moment after the claim
        // packet is accepted) rather than Success, so the orchestrator's
        // Success transition can be driven by the link actually coming
        // up.
        scope.launch {
            pairingOrchestrator.state.collect { state ->
                if (state is PairingState.EstablishingLink) {
                    Log.i(TAG, "Claim accepted, starting connection: node=${state.nodeIdHex} mac=${state.deviceAddress}")
                    startConnection(state.deviceAddress, state.nodeIdHex, freshPairing = true)
                }
            }
        }
    }

    /**
     * Start (or restart) the persistent BLE connection for the given board.
     * Tears down any existing connection first. Idempotent for the same MAC.
     *
     * @param freshPairing true iff this connection is being started as the
     *   tail end of a pairing flow (PairingState is currently
     *   EstablishingLink). When true, we install a watcher that confirms
     *   the pairing back to the orchestrator when the link reaches UP,
     *   plus a timeout that fails the pairing if it never does. When
     *   false (auto-reconnect on app launch), we leave PairingState alone
     *   — that flow has nothing to do with the pairing wizard.
     */
    private fun startConnection(
        macAddress: String,
        nodeIdHex: String,
        freshPairing: Boolean,
    ) {
        val store = mapStore
        if (store == null) {
            Log.w(TAG, "MapStore not set — cannot start connection")
            return
        }

        // Idempotency: dedupe duplicate auto-reconnect calls for the same
        // MAC. Critically, this check does NOT apply to freshPairing=true:
        // the pairing flow has its own watchers (installFreshPairingWatchers)
        // that MUST be installed to drive the EstablishingLink → Success
        // transition. ESP32 MACs are baked in efuse — they survive reflash
        // — so a freshly-paired board legitimately has the same MAC as a
        // previously-paired stale session. Skipping startConnection in that
        // case leaves the orchestrator stuck at EstablishingLink forever.
        if (!freshPairing && activeMac == macAddress && activeConnection != null) {
            Log.d(TAG, "Connection already active for $macAddress — skipping (auto-reconnect)")
            return
        }

        // Tear down existing connection if switching boards.
        stopConnection()

        Log.i(TAG, "Starting persistent BLE connection: mac=$macAddress node=$nodeIdHex freshPairing=$freshPairing")

        // Convert hex node ID to a Long for the BleConnection's ourNodeNumber.
        // This is the node number Tern claims when sending outbound packets.
        // Using 0 as a safe default if parsing fails — the board will still
        // accept our packets (Meshtastic doesn't validate sender node numbers
        // at the GATT layer).
        val nodeNumber = runCatching { nodeIdHex.toLong(16) }.getOrDefault(0L)

        val connection = buildBleConnection(
            context = appContext,
            targetMacAddress = macAddress,
            ourNodeNumber = nodeNumber,
            scope = scope,
            pairedBoardId = nodeIdHex,
        )
        Log.i(TAG, "Constructed BleConnection@${System.identityHashCode(connection)} for $macAddress")

        val middleware = PeerMiddleware(
            connection = connection,
            dispatch = { action -> store.dispatch(action) },
            scope = scope,
        )

        activeConnection = connection
        activeMac = macAddress
        activeNodeId = nodeIdHex
        activePeerMiddleware = middleware

        // Start the middleware first so it subscribes to events before
        // the connection starts emitting them.
        middlewareJob = middleware.start()

        if (freshPairing) {
            installFreshPairingWatchers(connection)
        }

        // Keep the board's LoRa region matched to where the pilot is — for
        // both a fresh pair and an auto-reconnect on app launch.
        installRegionReconciler(connection, store)

        scope.launch {
            connection.start()
            Log.i(TAG, "BLE connection started for $macAddress")
        }
    }

    /**
     * Keep the board's LoRa region matched to the pilot's GPS location,
     * seamlessly — US in the USA, EU in Europe. Reconciles on every link-UP
     * (fresh pair and auto-reconnect alike) and whenever the GPS fix moves
     * into a different region mid-session. Pushes only when the board's known
     * region differs from the location-derived one, so the steady state sends
     * nothing.
     *
     * The link-UP collector is UNDISPATCHED so it subscribes before the
     * transport starts emitting — the events flow has no replay, so a UP
     * emitted in between would otherwise be missed.
     */
    private fun installRegionReconciler(connection: BleConnection, store: MapStore) {
        regionLinkWatcherJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.events().collect { event ->
                if (event is MeshEvent.LinkStateChange && event.newState == LinkState.UP) {
                    ensureTeamProvisioned(store)
                    reconcileRegion(connection, store.state.value.userLocation)
                }
            }
        }
        regionLocationWatcherJob = scope.launch {
            store.state
                .map { it.userLocation }
                .distinctUntilChanged()
                .collect { loc -> reconcileRegion(connection, loc) }
        }
    }

    /**
     * Make "my buddies only" the default: if the pilot has no team yet,
     * auto-create a private one (unique random key) the first time a board
     * comes up. Without this, a board with no team sits on Meshtastic's
     * public default channel — so the roster fills with every stranger in
     * range and the pilot's position is broadcast to all of them. With it,
     * a fresh pilot starts on their own private channel (empty roster) and
     * forms a group only by sharing/joining a team link.
     *
     * No-op once any team exists (created, joined, or restored from prefs),
     * so a real team is never clobbered. The dispatched intent is applied to
     * the board by the team reconcile in MapViewContainer.
     */
    private fun ensureTeamProvisioned(store: MapStore) {
        if (store.state.value.settingsState.teamName != null) return
        // Respect a SAVED team even if SettingsPersistence hasn't hydrated it into
        // the store yet: the link can come UP before the first composition's
        // hydrate runs, and auto-provisioning here would then silently replace the
        // pilot's real team with a fresh throwaway one (and persist over it).
        // Prefs are the durable truth, so read them directly to close that race.
        val sp = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val savedTeam = sp.getString("team_name", null)
        if (savedTeam != null) {
            Log.i(TAG, "Team '$savedTeam' saved but not yet hydrated — restoring it (not auto-provisioning)")
            store.dispatch(
                MapAction.SetTeam(savedTeam, sp.getString("team_link", null), sp.getString("team_source", null)),
            )
            sp.getString("team_applied_link", null)?.let { store.dispatch(MapAction.SetTeamApplied(it)) }
            return
        }
        val suffix = activeNodeId?.takeLast(4) ?: "team"
        val team = TeamLink.create("Tern $suffix")
        Log.i(TAG, "No team set — auto-provisioning private team '${team.name}'")
        store.dispatch(MapAction.SetTeam(team.name, TeamLink.encode(team), "auto"))
    }

    /**
     * Push the location-derived region to [connection] iff it differs from
     * what the board currently reports. No-op when the link is down, the
     * location is unknown / unmapped, or the board's region hasn't been read
     * yet (we wait for the next fix or reconnect rather than guess).
     */
    private suspend fun reconcileRegion(connection: BleConnection, location: GeoPoint?) {
        if (connection.linkState != LinkState.UP) return
        val derived = location?.let { LoraRegion.regionForLocation(it.latitude, it.longitude) } ?: return
        val current = connection.boardRegion
        if (current == null) {
            Log.i(TAG, "Region reconcile: board region not read yet — retrying on next fix/UP")
            return
        }
        if (current == derived) return
        Log.i(
            TAG,
            "Region reconcile: board=${LoraRegion.name(current)} → ${LoraRegion.name(derived)} (from GPS) — setting",
        )
        val ok = connection.setRegion(derived)
        Log.i(TAG, "Region reconcile: setRegion(${LoraRegion.name(derived)}) accepted=$ok")
    }

    /**
     * Subscribe to the freshly-started connection's events flow and notify
     * the orchestrator the first time the link reaches UP (or, after
     * [LINK_ESTABLISH_TIMEOUT_MS], that the link never came up). The
     * subscription is cancelled after either firing; we don't want this
     * watcher reacting to link drops later in the session — those are
     * "board off" UX, not "pairing failed" UX.
     *
     * UNDISPATCHED launch so the collect is subscribed before the caller
     * starts the transport; the SharedFlow has no replay, so a UP event
     * emitted between here and our subscription would otherwise be lost.
     */
    private fun installFreshPairingWatchers(connection: BleConnection) {
        linkWatcherJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.events().collect { event ->
                if (event is MeshEvent.LinkStateChange && event.newState == LinkState.UP) {
                    linkEstablishTimeoutJob?.cancel()
                    linkEstablishTimeoutJob = null
                    pairingOrchestrator.confirmLinkEstablished()
                    // Done — this watcher only cares about the first UP.
                    linkWatcherJob?.cancel()
                    linkWatcherJob = null
                }
            }
        }
        linkEstablishTimeoutJob = scope.launch {
            delay(LINK_ESTABLISH_TIMEOUT_MS)
            Log.w(TAG, "Link establishment timed out after ${LINK_ESTABLISH_TIMEOUT_MS}ms")
            pairingOrchestrator.confirmLinkFailed(
                "Could not establish connection — try rebooting the board"
            )
            linkWatcherJob?.cancel()
            linkWatcherJob = null
        }
    }

    /**
     * The currently active BLE connection, if any. Exposed so on-device
     * test harnesses (notably the Aravis replay) can pump synthetic
     * Position frames through the live link via
     * [com.ternparagliding.sim.replay.AravisReplayRunner].
     *
     * Returns null when no board is paired (or the connection has been
     * torn down). Callers must tolerate that — production UX paths should
     * not depend on this; they go through Redux peerState the normal way.
     */
    fun activeBleConnection(): BleConnection? {
        val c = activeConnection
        Log.i(TAG, "activeBleConnection() returning ${c?.let { "BleConnection@${System.identityHashCode(it)}" } ?: "null"}")
        return c
    }

    /**
     * Set the active board's team (its PRIMARY LoRa channel) — the "set_team" the Team UI calls
     * when a pilot creates or joins a team. Returns false if there's no live board link.
     */
    suspend fun setTeam(name: String, psk: ByteArray): Boolean =
        activeConnection?.setTeam(name, psk) ?: false

    /**
     * Rename the active board — the "set_owner" the Settings edit dialog calls.
     * Sets the Meshtastic owner name (long + short) shown on the OLED and
     * broadcast as NodeInfo, so the board's label matches everywhere. Returns
     * false if there's no live board link.
     */
    suspend fun setOwner(longName: String, shortName: String): Boolean =
        activeConnection?.setOwner(longName, shortName) ?: false

    /**
     * Change how often the active board re-announces its name (NodeInfo). Lower =
     * a buddy's name shows up faster. Returns false if there's no live board link.
     */
    suspend fun setNodeInfoBroadcastSecs(secs: Int): Boolean =
        activeConnection?.setNodeInfoBroadcastSecs(secs) ?: false

    /** Change the active board's OLED display settings (on-time, flip). False if no live link. */
    suspend fun setDisplay(screenOnSecs: Int?, flipScreen: Boolean?): Boolean =
        activeConnection?.setDisplay(screenOnSecs, flipScreen) ?: false

    /** Current `node_info_broadcast_secs` read from the board's config stream, or null if not seen yet. */
    fun currentNodeInfoBroadcastSecs(): Int? =
        activeConnection?.deviceConfigBytes?.let {
            com.ternparagliding.mezulla.connection.ble.MeshPacketCodec.deviceNodeInfoBroadcastSecs(it)
        }

    /** Current OLED on-time (`screen_on_secs`), or null if not seen yet. */
    fun currentScreenOnSecs(): Int? =
        activeConnection?.displayConfigBytes?.let {
            com.ternparagliding.mezulla.connection.ble.MeshPacketCodec.displayScreenOnSecs(it)
        }

    /** Current `flip_screen`, or null if not seen yet. */
    fun currentFlipScreen(): Boolean? =
        activeConnection?.displayConfigBytes?.let {
            com.ternparagliding.mezulla.connection.ble.MeshPacketCodec.displayFlipScreen(it)
        }

    /**
     * True iff we already have a live, UP persistent link to this exact
     * board. Used by [PairingOrchestrator] to short-circuit a re-scan of a
     * board we're already connected to: while connected, the board isn't
     * advertising, so a fresh claim scan would fail with "Board not found"
     * and the teardown-then-failed-rescan would leave us with no active
     * connection even though the link was healthy. Re-scanning the QR of an
     * already-connected board should be an idempotent success.
     */
    fun isLinkedTo(nodeIdHex: String): Boolean {
        val c = activeConnection ?: return false
        return activeNodeId == nodeIdHex && c.linkState == LinkState.UP
    }

    /**
     * Re-point the live connection's peer-event stream at a new [MapStore].
     * Called from [initialize] when the Activity (and its store ViewModel)
     * is recreated: we keep the BLE connection alive and only rebuild the
     * Redux bridge so the recreated map receives peer updates. No-op if
     * there is no active connection.
     */
    private fun rebindMiddleware(store: MapStore) {
        val conn = activeConnection ?: return
        middlewareJob?.cancel()
        val middleware = PeerMiddleware(
            connection = conn,
            dispatch = { action -> store.dispatch(action) },
            scope = scope,
        )
        activePeerMiddleware = middleware
        middlewareJob = middleware.start()
        // Seed the new store with the current link state. The connection is
        // already UP and the events flow has no replay, so it won't re-emit
        // LinkStateChange — without this seed the recreated Activity's map
        // status badge never learns the link is live (T7).
        store.dispatch(PeerAction.LinkStateChanged(conn.linkState))
        Log.i(TAG, "Rebound PeerMiddleware to new store for BleConnection@${System.identityHashCode(conn)} (seeded linkState=${conn.linkState})")
    }

    /**
     * Public wrapper for [stopConnection]. Used by
     * [com.ternparagliding.mezulla.pairing.PairingOrchestrator.forgetBoard]
     * to close the race where the previous session's persistent
     * connection is still in the middle of a GATT connect when a NEW
     * pair starts — that mid-flight connect would trigger an SMP pair
     * request with the stale PIN, before the new flow has had a chance
     * to store the right one.
     */
    fun stopActiveConnection() {
        stopConnection()
    }

    /**
     * Stop the active connection and middleware. Safe to call when nothing
     * is running.
     */
    private fun stopConnection() {
        activeConnection?.let { conn ->
            Log.i(TAG, "Stopping existing connection for $activeMac")
            scope.launch {
                runCatching { conn.stop() }
            }
        }
        // Cancel the middleware's collector so a re-pair doesn't leave
        // a stale subscriber dispatching into Redux.
        middlewareJob?.cancel()
        middlewareJob = null
        // Cancel any in-flight fresh-pairing watchers so they don't fire
        // against the next connection or the orchestrator from a stale
        // session.
        linkWatcherJob?.cancel()
        linkWatcherJob = null
        linkEstablishTimeoutJob?.cancel()
        linkEstablishTimeoutJob = null
        // Stop reconciling region against a connection we're tearing down.
        regionLinkWatcherJob?.cancel()
        regionLinkWatcherJob = null
        regionLocationWatcherJob?.cancel()
        regionLocationWatcherJob = null
        activeConnection = null
        activeMac = null
        activeNodeId = null
        activePeerMiddleware = null
    }
}
