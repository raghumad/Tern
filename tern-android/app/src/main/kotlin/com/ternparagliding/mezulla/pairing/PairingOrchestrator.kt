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
        private const val KEY_OWNER_ID = "owner_id"
    }

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val blePairingService = BlePairingService(context)

    fun handleIntent(intent: Intent): Boolean {
        val uri = intent.data?.toString() ?: return false
        val link = TernPairLink.parse(uri) ?: return false
        handlePairLink(link)
        return true
    }

    fun handlePairLink(link: TernPairLink) {
        Log.i(TAG, "Pairing link received: node=${link.nodeIdHex}")
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
        )

        when (result) {
            is ClaimResult.Success -> {
                persistPairing(link.nodeIdHex, result.deviceName)
                _state.value = PairingState.Success(link.nodeIdHex)
                Log.i(TAG, "Pairing successful: node=${link.nodeIdHex} name=${result.deviceName} device=${result.deviceAddress}")
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

    fun getPairedNodeId(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PAIRED_NODE_ID, null)
    }

    fun persistPairing(nodeIdHex: String, deviceName: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PAIRED_NODE_ID, nodeIdHex)
            .putString(KEY_PAIRED_DEVICE_NAME, deviceName)
            .apply()
        Log.i(TAG, "Pairing persisted: node=$nodeIdHex name=$deviceName")
    }

    fun getPairedDeviceName(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PAIRED_DEVICE_NAME, null)
    }

    fun forgetBoard() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PAIRED_NODE_ID).apply()
        _state.value = PairingState.Idle
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
    data class Success(val nodeIdHex: String) : PairingState
    data class Failed(val reason: String) : PairingState
}
