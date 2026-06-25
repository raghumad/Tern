package com.ternparagliding.mezulla.pairing

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class PairingOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "PairingOrchestrator"
        private const val PREFS_NAME = "tern_pairing"
        private const val KEY_PAIRED_NODE_ID = "paired_node_id"
        private const val KEY_PAIRED_DEVICE_NAME = "paired_device_name"
        private const val KEY_PAIRED_DEVICE_ADDRESS = "paired_device_address"
        private const val KEY_OWNER_ID = "owner_id"
    }

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    /** The QR pair link currently being processed (if any). Surfaced
     *  so the priming UI can show the expected PIN, node ID, etc. */
    private val _currentLink = MutableStateFlow<TernPairLink?>(null)
    val currentLink: StateFlow<TernPairLink?> = _currentLink

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val blePairingService = BlePairingService(context)

    /** Called at the very start of a new pair flow so the caller can
     *  tear down any stale persistent connection that might race with
     *  the new pair's GATT/SMP handshake. Wired by the activity. */
    var onPairStart: (() -> Unit)? = null

    /** Returns true if the persistent link to this board is already UP.
     *  When it is, a re-scan is pointless and harmful: the board isn't
     *  advertising while connected, so the claim scan would fail with
     *  "Board not found", and the teardown-then-failed-rescan would drop
     *  a perfectly healthy link. Wired by the activity to query
     *  MezullaConnectionManager.isLinkedTo. */
    var isAlreadyLinked: ((nodeIdHex: String) -> Boolean)? = null

    fun handleIntent(intent: Intent): Boolean {
        val uri = intent.data?.toString() ?: return false
        val link = TernPairLink.parse(uri) ?: return false
        handlePairLink(link)
        return true
    }

    fun handlePairLink(link: TernPairLink) {
        Log.i(TAG, "Pairing link received: node=${link.nodeIdHex}")
        _currentLink.value = link

        // Idempotent re-scan: if the persistent link to this exact board is
        // already UP, scanning the QR again should be a no-op success. The
        // board isn't advertising while connected, so re-running the claim
        // scan would fail with "Board not found" — and the teardown below
        // would needlessly drop a healthy link. Short-circuit to Success.
        if (isAlreadyLinked?.invoke(link.nodeIdHex) == true) {
            Log.i(TAG, "Already linked to ${link.nodeIdHex} — skipping re-pair (idempotent)")
            _state.value = PairingState.Success(link.nodeIdHex, getPairedDeviceAddress() ?: "")
            return
        }

        // Tear down any stale persistent connection before we start.
        // Without this, the previous session's BleConnection (started
        // by MezullaConnectionManager.initialize() from the saved pair
        // record) can race ahead and trigger an SMP pair request with
        // the *previous* PIN — failing the pair before our new flow
        // has stored the correct PIN.
        onPairStart?.invoke()

        _state.value = PairingState.Received(link)

        scope.launch {
            executePairing(link)
        }
    }

    private suspend fun executePairing(link: TernPairLink) {
        _state.value = PairingState.Scanning(link.nodeIdHex)
        Log.i(TAG, "Scanning for board ${link.nodeIdHex}...")

        val ownerId = getOwnerId()
        val result = blePairingService.claimBoard(
            pairingToken = link.pairingToken,
            ownerId = ownerId,
            boardNodeNumber = link.nodeNumber,
            bleMac = link.bleMac,
        )

        when (result) {
            is ClaimResult.Success -> {
                persistPairing(link.nodeIdHex, result.deviceName, result.deviceAddress)
                // Claim packet was accepted by the board. The persistent BLE
                // connection is not yet up — that takes ~30s after the claim
                // because the board reboots into paired-only mode. Hand off
                // to EstablishingLink; MezullaConnectionManager will call
                // confirmLinkEstablished() once the persistent link reaches UP.
                _state.value = PairingState.EstablishingLink(link.nodeIdHex, result.deviceAddress)
                Log.i(TAG, "Claim accepted, establishing link: node=${link.nodeIdHex} name=${result.deviceName} device=${result.deviceAddress}")
            }
            is ClaimResult.BoardNotFound -> {
                _state.value = PairingState.Failed("Board not found. Make sure it's powered on and nearby.")
                Log.w(TAG, "Board not found")
            }
            is ClaimResult.BluetoothUnavailable -> {
                _state.value = PairingState.Failed("Bluetooth not available on this device.")
                Log.w(TAG, "Bluetooth unavailable")
            }
            is ClaimResult.BluetoothDisabled -> {
                _state.value = PairingState.Failed("Please enable Bluetooth.")
                Log.w(TAG, "Bluetooth disabled")
            }
            is ClaimResult.ConnectionFailed -> {
                _state.value = PairingState.Failed("Connection failed: ${result.reason}")
                Log.w(TAG, "Connection failed: ${result.reason}")
            }
            is ClaimResult.ClaimRejected -> {
                val msg = when (result.status) {
                    PairingStatus.TOKEN_MISMATCH -> "Pairing failed — try scanning again."
                    PairingStatus.ALREADY_CLAIMED -> "Board is already paired. Reset the board to re-pair."
                    else -> "Pairing rejected."
                }
                _state.value = PairingState.Failed(msg)
                Log.w(TAG, "Claim rejected: ${result.status}")
            }
        }
    }

    /**
     * Called by [com.ternparagliding.mezulla.MezullaConnectionManager] when
     * the persistent BLE link to the freshly-paired board reaches
     * [com.ternparagliding.mezulla.connection.LinkState.UP].
     *
     * No-op if the current state is anything other than
     * [PairingState.EstablishingLink] — e.g. the link came up after a
     * Failed/timeout transition, or this is a previously-paired board
     * coming up at app launch (Idle, not part of an active pairing flow).
     */
    fun confirmLinkEstablished() {
        val current = _state.value
        if (current is PairingState.EstablishingLink) {
            _state.value = PairingState.Success(current.nodeIdHex, current.deviceAddress)
            Log.i(TAG, "Link established: node=${current.nodeIdHex}")
        }
    }

    /**
     * Called when establishing the persistent BLE link times out or fails.
     * No-op if the current state is anything other than
     * [PairingState.EstablishingLink] — the link can drop later for other
     * reasons (board off, out of range) and that is not a pairing failure.
     */
    fun confirmLinkFailed(reason: String) {
        val current = _state.value
        if (current is PairingState.EstablishingLink) {
            _state.value = PairingState.Failed(reason)
            Log.w(TAG, "Link failed: $reason")
        }
    }

    fun getPairedNodeId(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PAIRED_NODE_ID, null)
    }

    fun persistPairing(nodeIdHex: String, deviceName: String? = null, deviceAddress: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PAIRED_NODE_ID, nodeIdHex)
            .putString(KEY_PAIRED_DEVICE_NAME, deviceName)
            .putString(KEY_PAIRED_DEVICE_ADDRESS, deviceAddress)
            .apply()
        Log.i(TAG, "Pairing persisted: node=$nodeIdHex name=$deviceName mac=$deviceAddress")
    }

    fun getPairedDeviceName(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PAIRED_DEVICE_NAME, null)
    }

    fun getPairedDeviceAddress(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PAIRED_DEVICE_ADDRESS, null)
    }

    fun forgetBoard() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PAIRED_NODE_ID)
            .remove(KEY_PAIRED_DEVICE_NAME)
            .remove(KEY_PAIRED_DEVICE_ADDRESS)
            .apply()
        _state.value = PairingState.Idle
        _currentLink.value = null
        Log.i(TAG, "Board forgotten")
    }

    fun getOwnerId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_OWNER_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_OWNER_ID, id).apply()
            Log.i(TAG, "Generated owner ID: $id")
        }
        return id
    }
}

sealed interface PairingState {
    data object Idle : PairingState
    data class Received(val link: TernPairLink) : PairingState
    data class Scanning(val nodeIdHex: String) : PairingState
    data class Connecting(val nodeIdHex: String) : PairingState
    data class Claiming(val nodeIdHex: String) : PairingState
    /**
     * The board accepted the claim packet, but the persistent BLE
     * connection is not yet up. Sits between [Claiming] and [Success];
     * the gap is the ~30s the board takes to reboot into paired-only mode
     * and start advertising under its new identity. Showing this state
     * (instead of jumping straight to Success) tells the pilot "we're not
     * done yet, hang on" so they don't put the phone away too early.
     */
    data class EstablishingLink(val nodeIdHex: String, val deviceAddress: String) : PairingState
    data class Success(val nodeIdHex: String, val deviceAddress: String) : PairingState
    data class Failed(val reason: String) : PairingState
}
