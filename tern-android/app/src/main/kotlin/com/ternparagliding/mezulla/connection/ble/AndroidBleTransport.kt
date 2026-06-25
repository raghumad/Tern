package com.ternparagliding.mezulla.connection.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Concrete [BleTransport] that drives Android's BluetoothLeScanner +
 * BluetoothGatt against a Meshtastic-flashed board at a known MAC.
 *
 * Lifecycle:
 *  1. [start] kicks off scanning with a MAC filter.
 *  2. On scan hit, scanning stops and we call `BluetoothDevice.connectGatt`.
 *  3. On `STATE_CONNECTED` we call `discoverServices`.
 *  4. Once services are discovered we enable notifications on FROM_NUM,
 *     then read FROM_RADIO in a drain loop. The first successful drain
 *     emits [BleTransportEvent.Connected].
 *  5. Every FROM_NUM notify triggers another drain.
 *  6. On unexpected disconnect we emit [BleTransportEvent.Disconnected],
 *     close the GATT handle, wait briefly, then re-scan.
 *
 * Threading: Android calls our [BluetoothGattCallback] on its own binder
 * thread. We do not block in callbacks; the heavy lifting (drain loops,
 * reconnect backoff) runs in coroutines on the injected [scope].
 *
 * Permissions: this class assumes the caller has already obtained
 * `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` (Android 12+) or
 * `BLUETOOTH` + `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` (Android 6–11).
 * Permission UX belongs in WS5; for this skeleton the
 * `@SuppressLint("MissingPermission")` annotations document that the
 * caller is responsible.
 *
 * NOTE on bonding: the Meshtastic board uses a fixed PIN (default 123456).
 * Android pops the system PIN dialog the first time the phone tries to
 * exchange secured GATT characteristics. There is nothing for this class
 * to do beyond initiating GATT operations; the bond completes out-of-band
 * and subsequent connects reuse the cached bond.
 */
internal class AndroidBleTransport(
    private val context: Context,
    private val targetMacAddress: String,
    private val scope: CoroutineScope,
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter,
    private val initialScanTimeoutMillis: Long = INITIAL_SCAN_TIMEOUT_MS,
) : BleTransport {

    private val _events = MutableSharedFlow<BleTransportEvent>(extraBufferCapacity = 64)

    @Volatile private var scanner: BluetoothLeScanner? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connected: Boolean = false
    @Volatile private var currentMtuValue: Int? = null
    @Volatile private var currentPhyValue: Int? = null
    @Volatile private var scanTimeoutJob: Job? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var scanning: Boolean = false
    @Volatile private var everEmittedInitialTimeout: Boolean = false
    private val stateLock = Any()

    /**
     * Single serial GATT operation queue.
     *
     * Android's BluetoothGatt allows only ONE outstanding operation at a time,
     * per `gatt` object; the documented best practice is to serialise ALL reads
     * and writes through one queue and issue the next op only after the previous
     * op's completion callback fires. Without it, the drain reads (issued from
     * GATT callbacks) and our writes raced for the one slot — writes were
     * rejected `not accepted (busy)` and reads even collided with reads.
     *
     * Producers ([writeToRadio], [requestDrain]) enqueue ops; the single
     * [consumerJob] coroutine runs them one at a time, awaiting each op's
     * [GattOp.completion] (fired by the matching callback, or by a timeout
     * backstop) before starting the next.
     */
    private val opQueue = Channel<GattOp>(Channel.UNLIMITED)

    /** The single consumer coroutine draining [opQueue]. Started in [start]. */
    @Volatile private var consumerJob: Job? = null

    /**
     * The op currently issued to the stack and awaiting its callback. Exactly
     * one at a time (the consumer is serial), so the GATT callbacks complete
     * `inFlight` to release the lane.
     */
    @Volatile private var inFlight: GattOp? = null

    /**
     * Coalesces drain cycles: at most one "read FROM_RADIO until empty" chain is
     * in flight at a time. A trigger ([requestDrain]) while one is already
     * running is a no-op; the running chain picks up any freshly-queued frames.
     */
    @Volatile private var drainActive: Boolean = false

    /** One queued GATT operation. */
    private sealed class GattOp {
        /**
         * Completed (true = success) when the matching GATT callback fires, the
         * op fails to initiate, or it times out — whichever first. Frees the lane.
         */
        val completion = CompletableDeferred<Boolean>()
    }

    /** Read FROM_RADIO once. [bytes] is filled by [onCharacteristicRead]. */
    private class ReadOp : GattOp() {
        @Volatile var bytes: ByteArray? = null
    }

    /** Write one ToRadio frame. The caller awaits [GattOp.completion] for the ack. */
    private class WriteOp(val value: ByteArray) : GattOp()

    override fun events(): Flow<BleTransportEvent> = _events.asSharedFlow()

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "BLE adapter unavailable or off; staying silent per graceful-degradation policy.")
            return
        }
        startOpConsumer()
        everEmittedInitialTimeout = false
        startScanning()
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        scanner?.let { runCatching { it.stopScan(scanCallback) } }
        scanner = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        connected = false
        consumerJob?.cancel()
        consumerJob = null
        failPendingOps()
    }

    override fun currentMtu(): Int? = currentMtuValue
    override fun currentPhy(): Int? = currentPhyValue

    @SuppressLint("MissingPermission")
    override fun simulateDisconnectForTest() {
        Log.i(TAG, "simulateDisconnectForTest: requesting GATT disconnect")
        runCatching { gatt?.disconnect() }
        // onConnectionStateChange will fire STATE_DISCONNECTED → standard
        // reconnect flow takes over (backoff + scan + connect).
    }

    @SuppressLint("MissingPermission")
    override suspend fun writeToRadio(toRadioBytes: ByteArray): Boolean {
        if (gatt == null) {
            Log.w(TAG, "writeToRadio: gatt is null — rejecting")
            return false
        }
        if (!connected) {
            Log.w(TAG, "writeToRadio: connected=false — rejecting ${toRadioBytes.size}B")
            return false
        }
        // Enqueue and await the op. The consumer issues it only when the lane is
        // free (no read/write in flight), so it can no longer be rejected "busy"
        // by an overlapping drain read. completion is always settled by the
        // consumer (callback, failed-issue, or timeout), so this never hangs.
        val op = WriteOp(toRadioBytes)
        opQueue.send(op)
        return op.completion.await()
    }

    override suspend fun pollDrain() {
        if (gatt == null || !connected) return
        requestDrain()
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        // Idempotency gate. Three things can race onto this method:
        // the initial start(), the timeout retry, and the disconnect
        // reconnect path. Without this guard, two parallel scans hit
        // BluetoothLeScanner.startScan and the second returns
        // SCAN_FAILED_ALREADY_STARTED (error code 1).
        synchronized(stateLock) {
            if (scanning || connected || gatt != null) return
            val scn = adapter?.bluetoothLeScanner
            if (scn == null) {
                // Bluetooth is off or the LE scanner is momentarily
                // unavailable (user toggled BT, or the stack is recovering
                // from Doze). A *persistent* connection must NOT die here —
                // the old code returned silently, so once BT went off the
                // scan loop never restarted and the board showed "link down"
                // forever until an app relaunch. Schedule a retry so we
                // resume the moment BT comes back.
                Log.i(TAG, "startScanning: no LE scanner (BT off?) — retrying in ${RECONNECT_BACKOFF_MS}ms")
                scheduleRescan()
                return
            }
            scanner = scn
            scanning = true
        }
        val s = scanner ?: return
        // Defensively clear any filter this callback may still have
        // registered from a prior scan that wasn't cleanly stopped. Leaked
        // filters stack up in the BT stack (we observed several for one MAC)
        // and can exhaust the per-app scan quota, after which startScan
        // silently returns no results.
        runCatching { s.stopScan(scanCallback) }
        val filter = ScanFilter.Builder()
            .setDeviceAddress(targetMacAddress)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        Log.i(TAG, "startScanning(mac=$targetMacAddress, timeout=${initialScanTimeoutMillis}ms)")
        runCatching { s.startScan(listOf(filter), settings, scanCallback) }
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(initialScanTimeoutMillis)
            if (connected || gatt != null) return@launch
            // Surface the timeout the first time so BleConnection can
            // drive its state machine. After that we keep cycling
            // scan→backoff→scan: the pilot may walk back into range at
            // any moment (e.g. phone woke up before the board did, or
            // the radio was momentarily blocked by their body), and a
            // "persistent" connection has to keep trying.
            if (!everEmittedInitialTimeout) {
                everEmittedInitialTimeout = true
                _events.emit(BleTransportEvent.InitialScanTimeout)
            }
            synchronized(stateLock) {
                scanner?.let { runCatching { it.stopScan(scanCallback) } }
                scanner = null
                scanning = false
            }
            delay(RECONNECT_BACKOFF_MS)
            if (!connected && gatt == null) startScanning()
        }
    }

    /**
     * Re-attempt [startScanning] after a backoff. Used when the LE scanner
     * isn't available yet (Bluetooth off / adapter recovering) so the
     * persistent connection keeps polling and resumes the moment BT returns,
     * instead of silently giving up. Reuses [scanTimeoutJob] so we never stack
     * multiple pending retries.
     */
    private fun scheduleRescan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(RECONNECT_BACKOFF_MS)
            if (!connected && gatt == null) startScanning()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            // Stop scanning to save battery; we have our target. Release the
            // `scanning` latch under the same lock startScanning() takes —
            // otherwise it stays true forever after the first successful
            // connect, and every later reconnect's startScanning() trips the
            // `if (scanning) return` guard and silently never re-scans. That
            // was the auto-reconnect-never-recovers bug (T2/T3).
            //
            // ALSO: bail if we've already handled a result (scanning already
            // cleared, or a gatt exists). A LOW_LATENCY scan with the default
            // ALL_MATCHES callback delivers several hits in a burst before
            // stopScan takes effect; without this guard each one called
            // connectGatt, opening 3-4 parallel GATT links to the same board.
            // The extras leak past stop() (only the last `gatt` is tracked),
            // wedge the board (held connections → not advertising), and split
            // the notify/drain across connections.
            synchronized(stateLock) {
                if (!scanning || gatt != null) return
                scanner?.let { runCatching { it.stopScan(this) } }
                scanner = null
                scanning = false
            }
            scanTimeoutJob?.cancel()
            scanTimeoutJob = null
            connectGatt(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed with code $errorCode; will retry on next start().")
            scope.launch {
                delay(RECONNECT_BACKOFF_MS)
                if (gatt == null) startScanning()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, /* autoConnect = */ false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, /* autoConnect = */ false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected (status=$status), requesting MTU 517...")
                    runCatching { g.requestMtu(517) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = connected
                    connected = false
                    runCatching { g.close() }
                    gatt = null
                    // Fail any op waiting on a callback that will never come now,
                    // so its awaiter (e.g. writeToRadio) doesn't hang to timeout,
                    // and clear stale queued ops. The consumer stays alive for the
                    // reconnect; fresh ops flow once the link is back.
                    failPendingOps()
                    if (wasConnected) {
                        scope.launch { _events.emit(BleTransportEvent.Disconnected) }
                    }
                    // Silent reconnect with a small backoff.
                    scope.launch {
                        delay(RECONNECT_BACKOFF_MS)
                        startScanning()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed: mtu=$mtu status=$status")
            // Cache the negotiated MTU so the transport can report it
            // (consumed by BleConnection and BleReliabilityTest's T4).
            currentMtuValue = mtu
            // Regardless of the negotiated MTU value, proceed to service
            // discovery. Some boards negotiate lower than 517 -- that's fine,
            // Meshtastic packets are small enough.
            // F5: also request PHY 2M upgrade for higher throughput +
            // lower airtime. Quietly best-effort — older boards stay on
            // PHY 1M without complaint and we'll observe via readPhy.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    g.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                    )
                }
                runCatching { g.readPhy() }
            }
            Log.i(TAG, "Discovering services...")
            runCatching { g.discoverServices() }
        }

        @SuppressLint("MissingPermission")
        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(TAG, "PHY updated: tx=$txPhy rx=$rxPhy status=$status")
            currentPhyValue = txPhy
        }

        @SuppressLint("MissingPermission")
        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(TAG, "PHY read: tx=$txPhy rx=$rxPhy status=$status")
            currentPhyValue = txPhy
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runCatching { g.disconnect() }
                return
            }
            val service = g.getService(MeshtasticGattUuids.SERVICE) ?: run {
                Log.w(TAG, "Meshtastic GATT service missing; disconnecting.")
                runCatching { g.disconnect() }
                return
            }
            val fromNum = service.getCharacteristic(MeshtasticGattUuids.FROM_NUM)
            if (fromNum != null) {
                runCatching { g.setCharacteristicNotification(fromNum, true) }
                val descriptor = fromNum.getDescriptor(MeshtasticGattUuids.CCC_DESCRIPTOR)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching {
                            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        runCatching { g.writeDescriptor(descriptor) }
                    }
                }
            }
            // Initial drain is now kicked from onDescriptorWrite — Android
            // GATT serializes operations and a readCharacteristic issued
            // here while the FROM_NUM descriptor write is in flight is
            // silently dropped, so the first drain never returns, Connected
            // never fires, and linkState never reaches UP.
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "onDescriptorWrite: uuid=${descriptor.characteristic.uuid} status=$status")
            if (descriptor.characteristic.uuid != MeshtasticGattUuids.FROM_NUM) return
            // Notifications are enabled — kick off the first FROM_RADIO drain
            // (through the op queue). Its terminating empty read emits Connected.
            requestDrain()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("Pre-API-33 BluetoothGattCallback signature.")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // FROM_NUM notification — there's data; request a drain.
            if (characteristic.uuid == MeshtasticGattUuids.FROM_NUM) requestDrain()
        }

        // Newer overload (API 33+). Android calls the right one.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == MeshtasticGattUuids.FROM_NUM) requestDrain()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("Pre-API-33 BluetoothGattCallback signature.")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != MeshtasticGattUuids.FROM_RADIO) return
            @Suppress("DEPRECATION")
            val bytes = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value ?: ByteArray(0) else null
            completeReadInFlight(bytes, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid != MeshtasticGattUuids.FROM_RADIO) return
            completeReadInFlight(if (status == BluetoothGatt.GATT_SUCCESS) value else null, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.i(TAG, "onCharacteristicWrite: uuid=${characteristic.uuid} status=$status (0=ack, non-zero=error)")
            if (characteristic.uuid != MeshtasticGattUuids.TO_RADIO) return
            // Release the in-flight write op so the lane frees; the consumer then
            // kicks a drain (request/response: the firmware queued a reply).
            (inFlight as? WriteOp)?.let { if (it.completion.isActive) it.completion.complete(status == BluetoothGatt.GATT_SUCCESS) }
        }
    }

    /** Hand a FROM_RADIO read result to the in-flight [ReadOp] and free the lane. */
    private fun completeReadInFlight(bytes: ByteArray?, ok: Boolean) {
        (inFlight as? ReadOp)?.let { op ->
            op.bytes = bytes
            if (op.completion.isActive) op.completion.complete(ok)
        }
    }

    /**
     * The single op-queue consumer: pull one op, issue it, await its callback
     * (or timeout), post-process, repeat. Serial by construction, so only one
     * GATT operation is ever outstanding.
     */
    private fun startOpConsumer() {
        if (consumerJob != null) return
        consumerJob = scope.launch {
            for (op in opQueue) executeOp(op)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun executeOp(op: GattOp) {
        val g = gatt
        if (g == null) {
            if (op.completion.isActive) op.completion.complete(false)
            return
        }
        inFlight = op
        val started = issue(g, op)
        if (!started) {
            inFlight = null
            if (op.completion.isActive) op.completion.complete(false)
            if (op is ReadOp) onDrainResult(null) // end the cycle
            return
        }
        // Await the matching callback (completeReadInFlight / onCharacteristicWrite),
        // with a backstop so a lost callback can't wedge the lane forever.
        val ok = try {
            withTimeout(OP_TIMEOUT_MS) { op.completion.await() }
        } catch (_: TimeoutCancellationException) {
            if (op.completion.isActive) op.completion.complete(false)
            false
        }
        inFlight = null
        when (op) {
            is ReadOp -> onDrainResult(if (ok) op.bytes else null)
            is WriteOp -> if (ok) requestDrain() // write acked → pull the firmware's reply
        }
    }

    /** Issue [op] to the stack. Returns whether it was accepted for execution. */
    @SuppressLint("MissingPermission")
    private fun issue(g: BluetoothGatt, op: GattOp): Boolean = when (op) {
        is ReadOp -> {
            val ch = g.getService(MeshtasticGattUuids.SERVICE)?.getCharacteristic(MeshtasticGattUuids.FROM_RADIO)
            if (ch == null) false else runCatching { g.readCharacteristic(ch) }.getOrDefault(false)
        }
        is WriteOp -> {
            val ch = g.getService(MeshtasticGattUuids.SERVICE)?.getCharacteristic(MeshtasticGattUuids.TO_RADIO)
            when {
                ch == null -> false
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    g.writeCharacteristic(ch, op.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothGatt.GATT_SUCCESS
                else -> {
                    @Suppress("DEPRECATION")
                    run {
                        ch.value = op.value
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        g.writeCharacteristic(ch)
                    }
                }
            }
        }
    }

    /** Process one FROM_RADIO read: emit a frame and continue, or end the drain. */
    private fun onDrainResult(bytes: ByteArray?) {
        when {
            // Read failed (or no gatt) — end the cycle; a later trigger restarts it.
            bytes == null -> drainActive = false
            // FIFO empty — drained. End the cycle; on the first drain, signal Connected.
            bytes.isEmpty() -> {
                drainActive = false
                if (!connected) {
                    connected = true
                    Log.i(TAG, "FIFO drained — emitting Connected")
                    scope.launch { _events.emit(BleTransportEvent.Connected) }
                }
            }
            // A frame — surface it and keep draining (stay in this cycle).
            else -> {
                scope.launch { _events.emit(BleTransportEvent.FromRadioFrame(bytes)) }
                opQueue.trySend(ReadOp())
            }
        }
    }

    /**
     * Ask for a FROM_RADIO drain. Coalesced: if a drain cycle is already running
     * it's a no-op (that cycle reads everything available). Called on FROM_NUM
     * notifications, after each successful write, and after notifications are
     * enabled (first drain).
     */
    private fun requestDrain() {
        if (drainActive) return
        drainActive = true
        if (!opQueue.trySend(ReadOp()).isSuccess) drainActive = false
    }

    /** Settle the in-flight op and discard any queued ops (link gone / stopping). */
    private fun failPendingOps() {
        inFlight?.let { if (it.completion.isActive) it.completion.complete(false) }
        inFlight = null
        while (true) {
            val op = opQueue.tryReceive().getOrNull() ?: break
            if (op.completion.isActive) op.completion.complete(false)
        }
        drainActive = false
    }

    companion object {
        private const val TAG = "AndroidBleTransport"

        /**
         * How long we scan before reporting `InitialScanTimeout`. The
         * scanner keeps running afterwards; this is just the "we've never
         * seen this board this session" signal that drives
         * NEVER_PAIRED → DOWN inside [BleConnection].
         */
        const val INITIAL_SCAN_TIMEOUT_MS: Long = 10_000L

        /** Cooldown between reconnect attempts. Small to keep recovery snappy. */
        const val RECONNECT_BACKOFF_MS: Long = 2_000L

        /**
         * How long the op-queue consumer waits for any GATT operation's
         * completion callback before treating it as failed and moving on. A
         * healthy read/write completes in well under a second; this is the
         * lost-callback backstop so one stuck op can never wedge the lane.
         */
        const val OP_TIMEOUT_MS: Long = 3_000L
    }
}
