package com.ternparagliding.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Scan-only** discovery for the unified "Add a device" picker — the same flow for *every*
 * Bluetooth device (XC Tracer vario, Mezulla board, future sensors). It just *lists* what's
 * nearby with live **signal strength**, sorted strongest-first (your device, on your harness, is
 * loudest), so the pilot can tell which one is theirs and tap it — instead of the app blind-
 * grabbing the first match. The chosen device is then remembered.
 *
 * The matching/sorting is the claim-tested [sortByProximity] over [ScanCandidate]; this class is
 * just the Android transport. Caller owns BLE permissions + lifecycle (start on open, stop on close).
 */
class BleDeviceScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter,
) {
    private val _candidates = MutableStateFlow<List<ScanCandidate>>(emptyList())
    fun candidates(): StateFlow<List<ScanCandidate>> = _candidates.asStateFlow()

    private val seen = linkedMapOf<String, ScanCandidate>()
    @Volatile private var scanning = false

    @SuppressLint("MissingPermission")
    fun start() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            Log.i(TAG, "BLE unavailable/off; picker shows nothing (graceful).")
            return
        }
        if (scanning) return
        scanning = true
        seen.clear()
        _candidates.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, cb) }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        scanning = false
        adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner?.let { runCatching { it.stopScan(cb) } }
    }

    /** Classify a scan hit as a device type we support, or null to ignore it. */
    private fun classify(result: ScanResult, name: String?): DeviceType? {
        val services = result.scanRecord?.serviceUuids
        if (services?.any { it == MESHTASTIC_SERVICE } == true) return DeviceType.MEZULLA
        if (name == null) return null
        if (name.contains("tracer", ignoreCase = true) || name.startsWith("XCT", ignoreCase = true)) return DeviceType.VARIO
        if (name.contains("meshtastic", ignoreCase = true) || name.contains("mezulla", ignoreCase = true)) return DeviceType.MEZULLA
        return null
    }

    private val cb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device?.name ?: result.scanRecord?.deviceName
            val mac = result.device?.address ?: return
            val type = classify(result, name) ?: return
            seen[mac] = ScanCandidate(name = name ?: "Unknown", mac = mac, rssi = result.rssi, type = type)
            _candidates.value = sortByProximity(seen.values.toList())
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Device scan failed ($errorCode)")
            scanning = false
        }
    }

    companion object {
        private const val TAG = "BleDeviceScanner"
        private val MESHTASTIC_SERVICE = ParcelUuid(UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd"))

        /** Coarse signal bars [1..4] from RSSI (dBm) for the picker rows. */
        fun signalBars(rssi: Int): Int = when {
            rssi >= -55 -> 4
            rssi >= -67 -> 3
            rssi >= -80 -> 2
            else -> 1
        }

        /** Short, human MAC tail for telling identical-named devices apart, e.g. "61:84". */
        fun macTail(mac: String): String = mac.takeLast(5)
    }
}
