package com.ternparagliding.flight

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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connects to an XC Tracer vario over BLE and emits parsed [SensorFix]es.
 *
 * The XC Tracer Mini II GPS exposes the **FFE0/FFE1** BLE-serial profile (verified by
 * sniffing the real device — *not* Nordic UART): it streams `$XCTRC` NMEA lines as ~20-byte
 * notifications on characteristic [FFE1]. This client scans for the vario (by advertised
 * name — XC Tracers don't advertise their service UUID), subscribes to FFE1, reassembles the
 * fragments with [NmeaLineAssembler], parses each line with [XcTracerParser], and publishes
 * the fixes. It is the *transport*; all the format/checksum logic lives in those two pure,
 * claim-tested classes.
 *
 * It runs as a **second** BLE peripheral alongside the Meshtastic LoRa board (separate GATT
 * connection, no interaction). Auto-reconnects on drop — a vario that goes silent mid-thermal
 * is the nightmare the K7 "Resilient" axis guards against.
 *
 * Threading mirrors [com.ternparagliding.mezulla.connection.ble.AndroidBleTransport]: Android
 * invokes the [BluetoothGattCallback] on a binder thread; we never block in callbacks and do
 * emissions/backoff on the injected [scope]. Caller owns BLE runtime permissions.
 */
class XcTracerBleClient(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Exact MAC of the pilot's chosen vario; when null we match any peripheral whose name looks
     *  like an XC Tracer (legacy/first-run). Settable via [setTarget] once the pilot picks. */
    @Volatile private var targetMac: String? = null,
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter,
) {
    enum class State { IDLE, SCANNING, CONNECTED }

    /** Pin (or clear) the exact vario to connect to — the pilot's pick from the scan list. */
    fun setTarget(mac: String?) { targetMac = mac }

    /**
     * Called when we reconnect to the remembered vario but at a **new MAC** (XC Tracers use a
     * random *static* BLE address that can change on power-cycle). The app updates the remembered
     * MAC so future reconnects match immediately. (mac, name).
     */
    var onMacResolved: ((String, String?) -> Unit)? = null

    private val _fixes = MutableSharedFlow<SensorFix>(extraBufferCapacity = 64)
    private val _state = MutableStateFlow(State.IDLE)

    /** Parsed fixes as they arrive (typically several per second once it has a GPS lock). */
    fun fixes(): Flow<SensorFix> = _fixes.asSharedFlow()
    fun state(): StateFlow<State> = _state.asStateFlow()

    private val assembler = NmeaLineAssembler()

    @Volatile private var scanner: BluetoothLeScanner? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var scanning = false
    @Volatile private var running = false
    /** When the current search began (ms) — the grace window before the name fallback kicks in. */
    @Volatile private var searchStartMs = 0L
    @Volatile private var watchdog: kotlinx.coroutines.Job? = null
    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "BLE adapter unavailable/off; staying idle (graceful degradation).")
            return
        }
        if (running) return // already searching/connected — don't stack watchdogs
        running = true
        searchStartMs = System.currentTimeMillis()
        startScanning()
        // Watchdog: a single startScan can go silent (Android throttles/stops long low-latency
        // scans). While we're still searching, periodically re-issue it so the search is truly
        // perpetual — the vario reconnects the moment it powers back on, with no taps.
        watchdog = scope.launch {
            while (running) {
                delay(SCAN_REFRESH_MS)
                if (running && gatt == null) {
                    synchronized(lock) {
                        scanner?.let { runCatching { it.stopScan(scanCallback) } }
                        scanning = false
                    }
                    startScanning()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        watchdog?.cancel(); watchdog = null
        synchronized(lock) {
            scanner?.let { runCatching { it.stopScan(scanCallback) } }
            scanner = null
            scanning = false
        }
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        assembler.reset()
        _state.value = State.IDLE
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        synchronized(lock) {
            if (!running || scanning || gatt != null) return
            val s = adapter?.bluetoothLeScanner ?: return
            scanner = s
            scanning = true
        }
        // Low-latency (continuous) scan: this is a deliberate, user-initiated connect, so find
        // the vario fast rather than dribble battery on a low duty cycle.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No ScanFilter: XC Tracers don't reliably advertise the FFE0 service UUID, so we
        // match on the advertised name in onScanResult instead.
        Log.i(TAG, "Scanning for an XC Tracer…")
        runCatching { scanner?.startScan(null, settings, scanCallback) }
        _state.value = State.SCANNING
    }

    private fun isTracerName(result: ScanResult): Boolean {
        val name = result.device?.name ?: result.scanRecord?.deviceName ?: return false
        return name.contains("tracer", ignoreCase = true) || name.startsWith("XCT", ignoreCase = true)
    }

    private fun looksLikeXcTracer(result: ScanResult): Boolean {
        if (targetMac != null) {
            if (result.device?.address.equals(targetMac, ignoreCase = true)) return true
            // XC Tracers use a random *static* BLE address that can change on power-cycle, so the
            // saved MAC may no longer match. After a grace window with no exact-MAC sighting,
            // accept a name-matching tracer and re-adopt its MAC. Exact-MAC still wins early, so
            // when several varios are present the right one is preferred before the fallback.
            return isTracerName(result) && (System.currentTimeMillis() - searchStartMs > NAME_FALLBACK_GRACE_MS)
        }
        return isTracerName(result)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val seen = result.device?.name ?: result.scanRecord?.deviceName
            Log.d(TAG, "scan saw: name=$seen addr=${result.device?.address}")
            if (!looksLikeXcTracer(result)) return
            val device = result.device ?: return
            synchronized(lock) {
                // A low-latency scan can deliver several hits before stopScan lands; only the
                // first wins, or we'd call connectGatt twice and the second clobbers the first.
                if (gatt != null || !scanning) return
                scanner?.let { runCatching { it.stopScan(this) } }
                scanner = null
                scanning = false
            }
            // If we matched via the name fallback (rotated MAC), adopt the new address and tell
            // the app so it persists it — next reconnect matches by MAC immediately.
            val addr = device.address
            if (targetMac != null && !addr.equals(targetMac, ignoreCase = true)) {
                Log.i(TAG, "Vario MAC rotated $targetMac → $addr; re-adopting")
                targetMac = addr
                onMacResolved?.invoke(addr, device.name)
            }
            Log.i(TAG, "Found ${device.name ?: device.address}; connecting…")
            connectGatt(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed ($errorCode); retrying.")
            synchronized(lock) { scanning = false }
            scope.launch { delay(RECONNECT_BACKOFF_MS); if (running) startScanning() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION") device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected; discovering services…")
                    runCatching { g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { g.close() }
                    gatt = null
                    assembler.reset()
                    _state.value = if (running) State.SCANNING else State.IDLE
                    if (running) scope.launch { delay(RECONNECT_BACKOFF_MS); startScanning() }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { runCatching { g.disconnect() }; return }
            val data = g.getService(SERVICE)?.getCharacteristic(FFE1)
            if (data == null) {
                Log.w(TAG, "FFE0/FFE1 not found; disconnecting.")
                runCatching { g.disconnect() }
                return
            }
            runCatching { g.setCharacteristicNotification(data, true) }
            val ccc = data.getDescriptor(CCC_DESCRIPTOR)
            if (ccc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                } else {
                    @Suppress("DEPRECATION") run { ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE }
                    @Suppress("DEPRECATION") runCatching { g.writeDescriptor(ccc) }
                }
            }
            _state.value = State.CONNECTED
            Log.i(TAG, "Subscribed to XC Tracer FFE1 stream.")
        }

        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("Pre-API-33 signature.")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == FFE1) @Suppress("DEPRECATION") ingest(ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == FFE1) ingest(value)
        }
    }

    /** Reassemble notification bytes into lines, parse, publish. Runs on the binder thread; cheap. */
    private fun ingest(bytes: ByteArray) {
        val lines = assembler.append(bytes)
        if (lines.isEmpty()) return
        for (line in lines) {
            val fix = XcTracerParser.parse(line) ?: continue
            scope.launch { _fixes.emit(fix) }
        }
    }

    companion object {
        private const val TAG = "XcTracerBle"
        val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val FFE1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val RECONNECT_BACKOFF_MS: Long = 2_000L
        /** Re-issue the scan this often while searching, so it survives Android stopping it. */
        const val SCAN_REFRESH_MS: Long = 12_000L
        /** Grace before the name fallback (rotated random MAC) kicks in — exact MAC wins first. */
        const val NAME_FALLBACK_GRACE_MS: Long = 8_000L
    }
}
