package com.ternparagliding.mezulla

import android.content.Context
import android.util.Log
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.connection.ble.buildBleConnection
import com.ternparagliding.mezulla.pairing.PairingOrchestrator
import com.ternparagliding.mezulla.pairing.PairingState
import com.ternparagliding.mezulla.redux.PeerMiddleware
import com.ternparagliding.redux.MapStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        val savedMac = pairingOrchestrator.getPairedDeviceAddress()
        val savedNodeId = pairingOrchestrator.getPairedNodeId()
        if (savedMac != null && savedNodeId != null) {
            Log.i(TAG, "Previously paired board found: node=$savedNodeId mac=$savedMac")
            startConnection(savedMac, savedNodeId)
        } else {
            Log.i(TAG, "No previously paired board — waiting for pairing")
        }

        // Observe pairing state for new pairings.
        scope.launch {
            pairingOrchestrator.state.collect { state ->
                if (state is PairingState.Success) {
                    Log.i(TAG, "Pairing succeeded: node=${state.nodeIdHex} mac=${state.deviceAddress}")
                    startConnection(state.deviceAddress, state.nodeIdHex)
                }
            }
        }
    }

    /**
     * Start (or restart) the persistent BLE connection for the given board.
     * Tears down any existing connection first. Idempotent for the same MAC.
     */
    private fun startConnection(macAddress: String, nodeIdHex: String) {
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

        Log.i(TAG, "Starting persistent BLE connection: mac=$macAddress node=$nodeIdHex")

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

        scope.launch {
            connection.start()
            Log.i(TAG, "BLE connection started for $macAddress")
        }
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
        activeConnection = null
        activeMac = null
        activePeerMiddleware = null
    }
}
