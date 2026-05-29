package com.ternparagliding.test

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Readiness probe used as the LAST gate before the heavy hardware
 * tests run. Scans for any BLE peripheral advertising the Meshtastic
 * service UUID and asserts found within [SCAN_DEADLINE_MS].
 *
 * Why this exists: a freshly-flashed board takes a variable amount
 * of time to come up with its BLE radio advertising findably. Rather
 * than "tune the timeout" in the heavy tests (which gives flakes when
 * the board is slow), we run this fast probe first. If it fails, the
 * Gradle pipeline can take corrective action (esptool hard-reset)
 * before re-probing — async by observation, not by guess-timing.
 */
@RunWith(AndroidJUnit4::class)
class BoardReadinessTest {

    @get:Rule
    val blePermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Before
    fun requireRealHardware() {
        Assume.assumeFalse(
            "Skipping BoardReadinessTest: requires real phone hardware",
            isEmulator(),
        )
    }

    private fun isEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT
        return fp.startsWith("generic") || fp.startsWith("unknown") ||
            fp.contains("emulator") ||
            android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.HARDWARE.contains("goldfish")
    }

    @Test
    fun mezulla_board_is_advertising_findably() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: error("BluetoothAdapter unavailable")
        if (!adapter.isEnabled) error("Bluetooth is OFF on the phone — enable it first")
        val scanner = adapter.bluetoothLeScanner ?: error("BluetoothLeScanner unavailable")

        val found = AtomicReference<ScanResult?>(null)
        val latch = CountDownLatch(1)
        val totalCallbacks = java.util.concurrent.atomic.AtomicInteger(0)
        val distinctDevices = java.util.concurrent.ConcurrentHashMap<String, String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                totalCallbacks.incrementAndGet()
                distinctDevices.putIfAbsent(result.device.address, result.device.name ?: "")
                val uuids = result.scanRecord?.serviceUuids ?: emptyList()
                if (uuids.any { it.uuid == MESHTASTIC_SERVICE_UUID }) {
                    found.compareAndSet(null, result)
                    latch.countDown()
                }
            }
            override fun onScanFailed(errorCode: Int) {
                android.util.Log.e(TAG, "Scan failed: errorCode=$errorCode")
                latch.countDown()
            }
        }

        val filter = ScanFilter.Builder().build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        android.util.Log.i(TAG, "Probing for a Meshtastic peripheral, deadline=${SCAN_DEADLINE_MS}ms")
        scanner.startScan(listOf(filter), settings, callback)
        try {
            latch.await(SCAN_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        val hit = found.get()
        android.util.Log.i(
            TAG,
            "Scan complete: totalCallbacks=${totalCallbacks.get()}, " +
                "distinctDevices=${distinctDevices.size}, mezullaFound=${hit != null}",
        )
        if (hit == null && distinctDevices.isEmpty()) {
            error(
                "Phone BT scanner returned ZERO callbacks for ANY device during " +
                    "${SCAN_DEADLINE_MS}ms. Phone's BT receiver is deaf — try " +
                    "rebooting the phone, or check for BT permission/airplane issues."
            )
        }
        if (hit == null) {
            val sample = distinctDevices.entries.take(8).joinToString { "${it.key}/'${it.value}'" }
            error(
                "Phone BT scanner saw ${distinctDevices.size} distinct devices " +
                    "($sample) but NONE advertising the Meshtastic service UUID. " +
                    "Board's BLE is either not advertising the service UUID, " +
                    "or is too far/blocked. Sample devices shown."
            )
        }
        android.util.Log.i(
            TAG,
            "Board found: addr=${hit.device.address} rssi=${hit.rssi} name='${hit.device.name}'",
        )
    }

    companion object {
        private const val TAG = "BoardReadinessTest"
        private val MESHTASTIC_SERVICE_UUID =
            UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

        /**
         * 15 s is the time budget for ONE probe. The Gradle wrapper
         * retries (with hard-reset between attempts) on failure — short
         * window so we don't waste time scanning a dead board.
         */
        private const val SCAN_DEADLINE_MS = 15_000L
    }
}
