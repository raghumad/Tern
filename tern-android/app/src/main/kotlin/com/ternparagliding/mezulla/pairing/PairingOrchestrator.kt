package com.ternparagliding.mezulla.pairing

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Coordinates the QR pairing flow: deep link → BLE scan → connect →
 * claim → persist.
 *
 * Lifecycle: created when a `tern://` deep link arrives, runs the
 * flow, then reports the result. The activity observes [state] and
 * shows appropriate UI (progress, success, error).
 *
 * This class does NOT own the BLE connection lifecycle — it uses
 * [BleConnection] transiently for the claim handshake, then hands
 * off to the normal always-on connection manager.
 */
class PairingOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "PairingOrchestrator"
        private const val PREFS_NAME = "tern_pairing"
        private const val KEY_PAIRED_NODE_ID = "paired_node_id"
        private const val KEY_OWNER_ID = "owner_id"
    }

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    /**
     * Handle an incoming deep link intent. Returns true if the intent
     * was a valid `tern://` pairing link, false otherwise.
     */
    fun handleIntent(intent: Intent): Boolean {
        val uri = intent.data?.toString() ?: return false
        val link = TernPairLink.parse(uri) ?: return false
        handlePairLink(link)
        return true
    }

    /**
     * Start the pairing flow from a parsed link. Called from either
     * the deep link intent handler or the in-app QR scanner.
     */
    fun handlePairLink(link: TernPairLink) {
        Log.i(TAG, "Pairing link received: node=${link.nodeIdHex}")
        _state.value = PairingState.Received(link)

        // TODO: next steps (BLE scan → connect → claim) will be
        // implemented when we're ready for the human test. For now,
        // receiving and parsing the link is the milestone.
    }

    /**
     * Get the persisted paired board's node ID, or null if unpaired.
     */
    fun getPairedNodeId(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PAIRED_NODE_ID, null)
    }

    /**
     * Persist the paired board's node ID after a successful claim.
     */
    fun persistPairing(nodeIdHex: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PAIRED_NODE_ID, nodeIdHex)
            .apply()
        Log.i(TAG, "Pairing persisted: node=$nodeIdHex")
    }

    /**
     * Clear the persisted pairing (forget board).
     */
    fun forgetBoard() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PAIRED_NODE_ID).apply()
        _state.value = PairingState.Idle
        Log.i(TAG, "Board forgotten")
    }

    /**
     * Get or create a stable owner ID for this phone. Generated once
     * on first pairing attempt and persisted forever.
     */
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
    data class Success(val nodeIdHex: String) : PairingState
    data class Failed(val reason: String) : PairingState
}
