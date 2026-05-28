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
    @Volatile private var scanTimeoutJob: Job? = null

    /**
     * Serialises GATT writes. BluetoothGatt only allows one in-flight
     * operation; concurrent writes from the app coroutine surface as
     * `false` returns and undefined ordering.
     */
    private val writeMutex = Mutex()

    override fun events(): Flow<BleTransportEvent> = _events.asSharedFlow()

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "BLE adapter unavailable or off; staying silent per graceful-degradation policy.")
            return
        }
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

    @SuppressLint("MissingPermission")
    override suspend fun writeToRadio(toRadioBytes: ByteArray): Boolean {
        val g = gatt ?: return false
        if (!connected) return false
        val characteristic = g.getService(MeshtasticGattUuids.SERVICE)
            ?.getCharacteristic(MeshtasticGattUuids.TO_RADIO)
            ?: return false
        return writeMutex.withLock {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeCharacteristic(
                    characteristic,
                    toRadioBytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                rc == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = toRadioBytes
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val s = adapter?.bluetoothLeScanner ?: return
        scanner = s
        val filter = ScanFilter.Builder()
            .setDeviceAddress(targetMacAddress)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        runCatching { s.startScan(listOf(filter), settings, scanCallback) }
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(initialScanTimeoutMillis)
            // If we still have not seen the board, surface the timeout
            // once so BleConnection can drive its state machine.
            if (!connected && gatt == null) {
                _events.emit(BleTransportEvent.InitialScanTimeout)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            // Stop scanning to save battery; we have our target.
            scanner?.let { runCatching { it.stopScan(this) } }
            scanner = null
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
            // Regardless of the negotiated MTU value, proceed to service
            // discovery. Some boards negotiate lower than 517 -- that's fine,
            // Meshtastic packets are small enough.
            Log.i(TAG, "Discovering services...")
            runCatching { g.discoverServices() }
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
            if (descriptor.characteristic.uuid != MeshtasticGattUuids.FROM_NUM) return
            // FROM_NUM notification subscription is established. NOW we can
            // safely issue the initial FROM_RADIO read — the first empty
            // result will trigger emit(Connected) and the link transitions
            // to UP.
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
    }

    private fun handleFromRadioRead(g: BluetoothGatt, uuid: java.util.UUID, bytes: ByteArray) {
        if (uuid != MeshtasticGattUuids.FROM_RADIO) return
        if (bytes.isEmpty()) {
            // FIFO drained.
            if (!connected) {
                connected = true
                scope.launch { _events.emit(BleTransportEvent.Connected) }
            }
            return
        }
        scope.launch { _events.emit(BleTransportEvent.FromRadioFrame(bytes)) }
        // Keep reading until empty.
        drainFromRadio(g, isFirstDrain = false)
    }

    @SuppressLint("MissingPermission")
    private fun drainFromRadio(g: BluetoothGatt, isFirstDrain: Boolean) {
        val ch = g.getService(MeshtasticGattUuids.SERVICE)
            ?.getCharacteristic(MeshtasticGattUuids.FROM_RADIO)
            ?: return
        runCatching { g.readCharacteristic(ch) }
        // The actual emission happens in onCharacteristicRead. isFirstDrain
        // is just a hint kept for future telemetry; not load-bearing.
        @Suppress("UNUSED_PARAMETER") val unused = isFirstDrain
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
    }
}
