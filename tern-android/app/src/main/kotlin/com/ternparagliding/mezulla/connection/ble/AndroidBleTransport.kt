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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
     * Serialises GATT writes. BluetoothGatt only allows one in-flight
     * operation; concurrent writes from the app coroutine surface as
     * `false` returns and undefined ordering.
     */
    private val writeMutex = Mutex()

    /**
     * Completed by [onCharacteristicWrite] when the current TO_RADIO write
     * finishes (with its status). [writeToRadio] holds [writeMutex] until this
     * fires, so the NEXT write doesn't start while this one is still in flight
     * — the collision that made `want_config_id` 100 ms after the handshake
     * heartbeat return `false` and stall Stage-1 for 30 s. Only one is pending
     * at a time (guarded by the mutex).
     */
    @Volatile private var writeAck: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    override fun events(): Flow<BleTransportEvent> = _events.asSharedFlow()

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "BLE adapter unavailable or off; staying silent per graceful-degradation policy.")
            return
        }
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
        val g = gatt
        if (g == null) {
            Log.w(TAG, "writeToRadio: gatt is null — rejecting")
            return false
        }
        if (!connected) {
            Log.w(TAG, "writeToRadio: connected=false — rejecting ${toRadioBytes.size}B")
            return false
        }
        val characteristic = g.getService(MeshtasticGattUuids.SERVICE)
            ?.getCharacteristic(MeshtasticGattUuids.TO_RADIO)
        if (characteristic == null) {
            Log.w(TAG, "writeToRadio: TO_RADIO characteristic not found — rejecting")
            return false
        }
        // Hold the mutex for the WHOLE write round-trip — start the write,
        // then await its onCharacteristicWrite completion — so the next
        // writeToRadio can't start while this one is in flight. Android only
        // allows one outstanding GATT op; a second writeCharacteristic issued
        // before the first completes returns `false` and is silently dropped.
        // (That dropped the handshake's want_config_id and stalled Stage-1 for
        // 30 s; it could equally drop a position/team/region write.)
        return writeMutex.withLock {
            val ack = kotlinx.coroutines.CompletableDeferred<Boolean>()
            writeAck = ack
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    characteristic,
                    toRadioBytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = toRadioBytes
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
            if (!started) {
                // Couldn't even hand the write to the stack — report failure
                // immediately so the caller can retry; no callback will come.
                writeAck = null
                Log.w(TAG, "writeToRadio: writeCharacteristic not accepted (busy) — rejecting ${toRadioBytes.size}B")
                return@withLock false
            }
            val ok = runCatching {
                kotlinx.coroutines.withTimeout(WRITE_ACK_TIMEOUT_MS) { ack.await() }
            }.getOrDefault(false)
            writeAck = null
            ok
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun pollDrain() {
        val g = gatt ?: return
        if (!connected) return
        drainFromRadio(g, isFirstDrain = false)
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
            drainFromRadio(g, isFirstDrain = true)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("Pre-API-33 BluetoothGattCallback signature.")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // FROM_NUM notification — drain FROM_RADIO.
            if (characteristic.uuid == MeshtasticGattUuids.FROM_NUM) {
                drainFromRadio(g, isFirstDrain = false)
            }
        }

        // Newer overload (API 33+). Android calls the right one.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == MeshtasticGattUuids.FROM_NUM) {
                drainFromRadio(g, isFirstDrain = false)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("Pre-API-33 BluetoothGattCallback signature.")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            @Suppress("DEPRECATION")
            handleFromRadioRead(g, characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handleFromRadioRead(g, characteristic.uuid, value)
        }

        // Peer ACK for a write arrives here. We always kick a drain on
        // successful TO_RADIO writes — this is the canonical Meshtastic
        // pattern (matches official meshtastic-android: write then
        // trigger drain). The firmware queues a response to every
        // ToRadio request, and the BLE FromNum notification is
        // unreliable during the handshake window. Draining on
        // write-ack is the correct request/response semantics and
        // doesn't race the write transaction's serialization queue.
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.i(TAG, "onCharacteristicWrite: uuid=${characteristic.uuid} status=$status (0=ack, non-zero=error)")
            if (characteristic.uuid == MeshtasticGattUuids.TO_RADIO) {
                // Release the writer waiting in writeToRadio (success or not)
                // so the mutex frees and the next write can start.
                writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    drainFromRadio(g, isFirstDrain = false)
                }
            }
        }
    }

    private fun handleFromRadioRead(g: BluetoothGatt, uuid: java.util.UUID, bytes: ByteArray) {
        if (uuid != MeshtasticGattUuids.FROM_RADIO) return
        Log.i(TAG, "handleFromRadioRead: ${bytes.size} bytes, connected=$connected")
        if (bytes.isEmpty()) {
            if (!connected) {
                connected = true
                Log.i(TAG, "FIFO drained — emitting Connected")
                scope.launch { _events.emit(BleTransportEvent.Connected) }
            }
            return
        }
        scope.launch { _events.emit(BleTransportEvent.FromRadioFrame(bytes)) }
        drainFromRadio(g, isFirstDrain = false)
    }

    @SuppressLint("MissingPermission")
    private fun drainFromRadio(g: BluetoothGatt, isFirstDrain: Boolean) {
        val ch = g.getService(MeshtasticGattUuids.SERVICE)
            ?.getCharacteristic(MeshtasticGattUuids.FROM_RADIO)
        if (ch == null) {
            Log.w(TAG, "drainFromRadio: FROM_RADIO characteristic not found (isFirstDrain=$isFirstDrain)")
            return
        }
        val ok = runCatching { g.readCharacteristic(ch) }.getOrDefault(false)
        Log.i(TAG, "drainFromRadio: readCharacteristic returned $ok (isFirstDrain=$isFirstDrain)")
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
         * How long [writeToRadio] waits for a TO_RADIO write to complete
         * (onCharacteristicWrite) before giving up and returning false. A
         * healthy write acks in well under a second; this is the stuck-write
         * backstop so the mutex can never wedge the write path.
         */
        const val WRITE_ACK_TIMEOUT_MS: Long = 3_000L
    }
}
