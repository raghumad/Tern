package com.ternparagliding.mezulla.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.ble.AndroidBleTransport
import com.ternparagliding.mezulla.connection.ble.BleTransport
import com.ternparagliding.mezulla.connection.ble.BleTransportEvent
import com.ternparagliding.mezulla.connection.ble.MeshPacketCodec
import com.ternparagliding.mezulla.connection.ble.MeshtasticGattUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * One-time BLE connect → claim handshake for pairing with a Mezulla board.
 * Transient: runs once during pairing, then hands off to the persistent
 * [com.ternparagliding.mezulla.connection.ble.BleConnection].
 *
 * Two correctness guarantees, both absent from the original fire-and-forget
 * version (see docs/architecture/mezulla-pairing-audit):
 *
 *  1. **Right board.** When the QR carries the board's BLE MAC (`m=`), we
 *     connect to that exact address — no broad scan, so we can't latch onto
 *     whichever Meshtastic board answers first (the bug that paired a phone
 *     to a neighbour's board). Legacy QRs without `m=` fall back to a scan.
 *
 *  2. **Real acknowledgement.** We read the board's claim reply and require
 *     `status == OK` AND the reply's `from` == the node we intended. So
 *     "paired" means the board actually accepted us — never a hopeful guess.
 *
 * The claim rides on the **documented Meshtastic phone-protocol handshake**
 * (heartbeat → `want_config_id` → drain config) exactly like the persistent
 * [com.ternparagliding.mezulla.connection.ble.BleConnection]. This matters:
 * the firmware's PhoneAPI serves nothing on `FromRadio` until the client has
 * announced itself with `want_config_id` and drained the config stream. The
 * previous bespoke GATT session skipped the handshake, so the board's claim
 * ack — even once the firmware delivered it to the phone queue — was never
 * served, and pairing hung at "finding board". We reuse the proven
 * [AndroidBleTransport] (write serialisation + drain-on-write-ack) rather
 * than hand-rolling the GATT state machine a second time.
 */
@SuppressLint("MissingPermission")
class BlePairingService(private val context: Context) {

    companion object {
        private const val TAG = "BlePairingService"
        private const val SCAN_TIMEOUT_MS = 30_000L
        // Time to find + connect + drain to the board (the transport scans by
        // MAC and emits Connected once the FromRadio FIFO is first drained).
        private const val CONNECT_TIMEOUT_MS = 25_000L
        // Time to complete the want_config_id config download. Generous — the
        // board streams its whole nodeDB/config; the reply can't be served
        // until this finishes (PhoneAPI stays in send-config until then).
        private const val CONFIG_TIMEOUT_MS = 25_000L
        // Time to wait for the board's claim ack after we write the claim.
        private const val REPLY_TIMEOUT_MS = 10_000L
        // Gap between the heartbeat and want_config_id writes — lets the
        // heartbeat's GATT write land before the next op (see runClaim).
        private const val HANDSHAKE_SETTLE_MS = 150L
        // The claim write can collide with the tail of the config drain;
        // retry a few times with a short backoff before giving up.
        private const val CLAIM_WRITE_ATTEMPTS = 8
        private const val CLAIM_WRITE_RETRY_MS = 250L
        // Backup raw-read drain cadence while awaiting the ack (covers a
        // missed FromNum notify).
        private const val REPLY_POLL_MS = 600L
        private val MESHTASTIC_SERVICE_UUID = MeshtasticGattUuids.SERVICE
    }

    /**
     * Test seam: how a [BleTransport] is built for a target MAC. Production
     * uses [AndroidBleTransport]; tests can inject a fake to exercise the
     * handshake/claim/verify flow without real Bluetooth.
     */
    internal var transportFactory: (String, CoroutineScope) -> BleTransport =
        { mac, scope -> AndroidBleTransport(context, mac, scope) }

    /**
     * Connect, handshake, claim, verify. Call from a coroutine.
     *
     * @param boardNodeNumber the node id from the QR (`n=`) — used to address
     *   the claim and to verify the reply's `from`.
     * @param bleMac the board's BLE address from the QR (`m=`), or null for a
     *   legacy QR. When non-null we connect straight to it (no broad scan).
     */
    suspend fun claimBoard(
        pairingToken: String,
        ownerId: String,
        boardNodeNumber: Long,
        bleMac: String? = null,
    ): ClaimResult = coroutineScope {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null) {
            Log.w(TAG, "BluetoothAdapter is null. btManager=$btManager")
            return@coroutineScope ClaimResult.BluetoothUnavailable
        }
        if (!adapter.isEnabled) return@coroutineScope ClaimResult.BluetoothDisabled

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
            val connectPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            if (scanPerm != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                connectPerm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing BLE permissions (BLUETOOTH_SCAN or BLUETOOTH_CONNECT)")
                return@coroutineScope ClaimResult.BluetoothUnavailable
            }
        }

        // Resolve the target MAC: straight from the QR when present
        // (deterministic), otherwise a one-off scan for any Meshtastic board
        // (legacy QRs). Identity is still enforced afterwards by the
        // claim-reply `from` check, so a wrong board here fails honestly.
        val targetMac: String = if (bleMac != null) {
            Log.i(TAG, "Pairing to board MAC $bleMac (node ${"%08x".format(boardNodeNumber)})")
            bleMac
        } else {
            Log.i(TAG, "No MAC in QR — scanning for a Meshtastic board (legacy)")
            try {
                scanForMeshtasticDevice(adapter).address
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Scan timed out — board not found")
                return@coroutineScope ClaimResult.BoardNotFound
            }
        }

        val transport = transportFactory(targetMac, this)
        try {
            runClaim(transport, targetMac, pairingToken, ownerId, boardNodeNumber)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Pairing timed out", e)
            ClaimResult.ConnectionFailed("Timed out talking to the board — try again")
        } catch (e: Exception) {
            Log.e(TAG, "Pairing session failed", e)
            ClaimResult.ConnectionFailed(e.message ?: "Pairing error")
        } finally {
            runCatching { transport.stop() }
        }
    }

    /**
     * Drive one connect → handshake → claim → verify cycle over [transport].
     * Runs inside the caller's [coroutineScope]; the event collector is a
     * child job cancelled on return.
     */
    private suspend fun runClaim(
        transport: BleTransport,
        targetMac: String,
        pairingToken: String,
        ownerId: String,
        expectedNode: Long,
    ): ClaimResult = coroutineScope {
        val connected = CompletableDeferred<Unit>()
        val configDone = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<PairingReply>()

        val collector = launch {
            transport.events().collect { ev ->
                when (ev) {
                    BleTransportEvent.Connected ->
                        if (!connected.isCompleted) connected.complete(Unit)

                    BleTransportEvent.Disconnected -> {
                        val err = RuntimeException("Board disconnected during pairing")
                        if (!connected.isCompleted) connected.completeExceptionally(err)
                        if (!reply.isCompleted) reply.completeExceptionally(err)
                    }

                    BleTransportEvent.InitialScanTimeout -> {
                        // Keep waiting; the CONNECT_TIMEOUT below bounds this.
                    }

                    is BleTransportEvent.FromRadioFrame -> {
                        // The claim ack is a PRIVATE_APP packet; check that first.
                        val pr = MezullaPairingCodec.decodePairingReplyFromRadio(ev.bytes)
                        if (pr != null) {
                            Log.i(TAG, "Claim reply: from=${"%08x".format(pr.fromNode)} status=${pr.status}")
                            if (!reply.isCompleted) reply.complete(pr)
                            return@collect
                        }
                        // Otherwise watch for our config-complete sentinel.
                        val me = MeshPacketCodec.decodeFromRadio(ev.bytes)
                        if (me is MeshEvent.ConfigComplete &&
                            me.configId == MeshPacketCodec.HANDSHAKE_CONFIG_NONCE &&
                            !configDone.isCompleted
                        ) {
                            configDone.complete(Unit)
                        }
                    }
                }
            }
        }

        try {
            transport.start()
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }

            // Documented Meshtastic handshake: heartbeat wakes the phone-API
            // state machine, want_config_id pulls config (and moves the API
            // into send-packets state so our claim ack will actually be
            // served back to us). Drain is handled by the transport.
            //
            // The 100ms gap is load-bearing: writeToRadio returns as soon as
            // the GATT write is *issued*, not when it's acked, and Android
            // allows only one in-flight GATT op. Firing want_config_id
            // immediately after the heartbeat drops it (the heartbeat write is
            // still in flight), config never streams, and the claim ack stays
            // gated. Mirrors BleConnection.runHandshake.
            transport.writeToRadio(MeshPacketCodec.encodeHeartbeat())
            delay(HANDSHAKE_SETTLE_MS)
            transport.writeToRadio(
                MeshPacketCodec.encodeWantConfigId(MeshPacketCodec.HANDSHAKE_CONFIG_NONCE)
            )
            val configOk = runCatching {
                withTimeout(CONFIG_TIMEOUT_MS) { configDone.await() }
                true
            }.getOrDefault(false)
            Log.i(TAG, "Handshake config drained (complete=$configOk)")

            // Write the claim (want_response set by the codec; the firmware
            // also pushes the ack straight to the phone queue).
            val payload = MezullaPairingCodec.encodeClaimPacket(pairingToken, ownerId)
            val frame = MezullaPairingCodec.encodeToRadioPrivateApp(
                fromNodeNumber = 0L, // firmware rewrites 0 → its own node num
                toNodeNumber = expectedNode,
                packetId = (System.nanoTime() and 0x7FFFFFFF).toInt().coerceAtLeast(1),
                payload = payload,
            )
            // Retry the write: right after config the transport may still be
            // draining FromRadio (one in-flight GATT op at a time), so the
            // first writeToRadio can come back false. Back off and re-issue.
            var claimSent = false
            for (attempt in 1..CLAIM_WRITE_ATTEMPTS) {
                if (transport.writeToRadio(frame)) { claimSent = true; break }
                Log.i(TAG, "Claim write busy (attempt $attempt) — retrying")
                delay(CLAIM_WRITE_RETRY_MS)
            }
            if (!claimSent) {
                return@coroutineScope ClaimResult.ConnectionFailed("Claim write rejected")
            }

            // The board enqueues the ack a beat after the claim (it persists +
            // redraws first). It normally arrives via a FromNum notify, but a
            // single notify can be missed, so we also nudge a raw-read drain on
            // a timer. NOT a heartbeat poll: the firmware answers a heartbeat
            // with a queue-status frame that preempts the queued reply.
            val r = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                val poller = launch {
                    while (true) {
                        delay(REPLY_POLL_MS)
                        runCatching { transport.pollDrain() }
                    }
                }
                try {
                    reply.await()
                } finally {
                    poller.cancel()
                }
            }
            if (r == null) {
                return@coroutineScope ClaimResult.ConnectionFailed("No claim reply from board")
            }

            // Identity gate: the board that replied must be the one we meant
            // to claim. With connect-by-MAC this always holds; the check
            // catches a legacy/scanned mismatch or a spoof.
            if (r.fromNode != expectedNode) {
                Log.w(TAG, "Reply from wrong node: got ${"%08x".format(r.fromNode)} expected ${"%08x".format(expectedNode)}")
                return@coroutineScope ClaimResult.ConnectionFailed("Connected to the wrong board — try again")
            }
            when (r.status) {
                PairingStatus.OK -> ClaimResult.Success(targetMac, targetMac)
                PairingStatus.TOKEN_MISMATCH -> ClaimResult.ClaimRejected(PairingStatus.TOKEN_MISMATCH)
                PairingStatus.ALREADY_CLAIMED -> ClaimResult.ClaimRejected(PairingStatus.ALREADY_CLAIMED)
                PairingStatus.UNKNOWN -> ClaimResult.ClaimRejected(PairingStatus.UNKNOWN)
            }
        } finally {
            collector.cancel()
        }
    }

    /**
     * Legacy fallback: scan for any Meshtastic board (used only for QRs
     * without `m=`). Completes on the first service-UUID match and returns
     * the device so the caller can take its MAC. Identity is still enforced
     * afterwards by the claim-reply `from` check.
     */
    private suspend fun scanForMeshtasticDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val deferred = CompletableDeferred<BluetoothDevice>()
        val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("BLE scanner unavailable")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val serviceUuids = result.scanRecord?.serviceUuids ?: emptyList()
                val isMeshtastic = serviceUuids.any { it.uuid == MESHTASTIC_SERVICE_UUID }
                if (isMeshtastic && !deferred.isCompleted) {
                    val name = result.scanRecord?.deviceName ?: result.device.name
                    Log.i(TAG, "Legacy scan found Meshtastic device: $name (${result.device.address})")
                    deferred.complete(result.device)
                    scanner.stopScan(this)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                deferred.completeExceptionally(RuntimeException("BLE scan failed: $errorCode"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(listOf(ScanFilter.Builder().build()), settings, callback)
        try {
            return withTimeout(SCAN_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            scanner.stopScan(callback)
            throw e
        }
    }
}

sealed interface ClaimResult {
    data class Success(val deviceAddress: String, val deviceName: String) : ClaimResult
    data object BoardNotFound : ClaimResult
    data object BluetoothUnavailable : ClaimResult
    data object BluetoothDisabled : ClaimResult
    data class ConnectionFailed(val reason: String) : ClaimResult
    data class ClaimRejected(val status: PairingStatus) : ClaimResult
}
