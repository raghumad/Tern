package com.ternparagliding.mezulla

import android.content.Context
import android.util.Log
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.connection.ble.buildBleConnection
import com.ternparagliding.mezulla.pairing.PairingOrchestrator
import com.ternparagliding.mezulla.pairing.PairingState
import com.ternparagliding.mezulla.redux.PeerMiddleware
import com.ternparagliding.redux.MapStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            Log.d(TAG, "Already initialized — skipping")
            return
        }
        mapStore = store
        Log.i(TAG, "Initializing MezullaConnectionManager")

        // If a board was previously paired, start the connection now.
        // freshPairing = false → don't touch PairingState; the Idle->Success
        // arc is irrelevant for a session that didn't go through pairing.
        val savedMac = pairingOrchestrator.getPairedDeviceAddress()
        val savedNodeId = pairingOrchestrator.getPairedNodeId()
        if (savedMac != null && savedNodeId != null) {
            Log.i(TAG, "Previously paired board found: node=$savedNodeId mac=$savedMac")
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

        // Don't create a duplicate connection for the same board.
        if (activeMac == macAddress && activeConnection != null) {
            Log.d(TAG, "Connection already active for $macAddress — skipping")
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

        val middleware = PeerMiddleware(
            connection = connection,
            dispatch = { action -> store.dispatch(action) },
            scope = scope,
        )

        activeConnection = connection
        activeMac = macAddress
        activePeerMiddleware = middleware

        // Start the middleware first so it subscribes to events before
        // the connection starts emitting them.
        middlewareJob = middleware.start()

        if (freshPairing) {
            installFreshPairingWatchers(connection)
        }

        scope.launch {
            connection.start()
            Log.i(TAG, "BLE connection started for $macAddress")
        }
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
    fun activeBleConnection(): BleConnection? = activeConnection

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
        activeConnection = null
        activeMac = null
        activePeerMiddleware = null
    }
}
