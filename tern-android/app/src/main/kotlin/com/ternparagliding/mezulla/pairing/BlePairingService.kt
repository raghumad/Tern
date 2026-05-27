package com.ternparagliding.mezulla.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.ternparagliding.mezulla.connection.ble.MeshtasticGattUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Handles the one-time BLE scan → connect → claim handshake for
 * pairing with a Mezulla board. This is NOT the always-on connection
 * — it's a transient operation that runs once during pairing and
 * then hands off to [BleConnection] for ongoing communication.
 *
 * The Meshtastic service UUID is used for scanning. Once connected,
 * we write the claim packet on PRIVATE_APP and read the response.
 */
@SuppressLint("MissingPermission")
class BlePairingService(private val context: Context) {

    companion object {
        private const val TAG = "BlePairingService"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val CLAIM_TIMEOUT_MS = 5_000L
        private val MESHTASTIC_SERVICE_UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    }

    /**
     * Scan for a Meshtastic board, connect, send claim, return result.
     * This is a suspend function — call from a coroutine.
     */
    suspend fun claimBoard(
        pairingToken: String,
        ownerId: String,
    ): ClaimResult {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return ClaimResult.BluetoothUnavailable

        if (!adapter.isEnabled) return ClaimResult.BluetoothDisabled

        // Check BLE permissions at runtime (Android 12+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val scanPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
            val connectPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            if (scanPerm != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                connectPerm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing BLE permissions (BLUETOOTH_SCAN or BLUETOOTH_CONNECT)")
                return ClaimResult.BluetoothUnavailable
            }
        }

        // Step 1: Scan for Meshtastic devices
        Log.i(TAG, "Scanning for Meshtastic devices...")
        val device = try {
            scanForMeshtasticDevice(adapter)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Scan timed out")
            return ClaimResult.BoardNotFound
        }

        Log.i(TAG, "Found device: ${device.name ?: device.address}")

        // Step 2: Connect GATT
        Log.i(TAG, "Connecting to ${device.address}...")
        val gatt = try {
            connectGatt(device)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "GATT connect timed out")
            return ClaimResult.ConnectionFailed("Connect timeout")
        } catch (e: Exception) {
            Log.w(TAG, "GATT connect failed", e)
            return ClaimResult.ConnectionFailed(e.message ?: "Unknown error")
        }

        // Step 3: Send claim packet
        Log.i(TAG, "Sending claim packet...")
        try {
            val payload = MezullaPairingCodec.encodeClaimPacket(pairingToken, ownerId)
            val toRadio = gatt.getService(MESHTASTIC_SERVICE_UUID)
                ?.getCharacteristic(MeshtasticGattUuids.TO_RADIO)

            if (toRadio == null) {
                gatt.disconnect()
                gatt.close()
                return ClaimResult.ConnectionFailed("ToRadio characteristic not found")
            }

            // Write the claim as a raw ToRadio frame on PRIVATE_APP
            val frame = MezullaPairingCodec.encodeToRadioPrivateApp(
                fromNodeNumber = 0L, // We don't know our node number yet
                toNodeNumber = 0xFFFFFFFFL, // Broadcast — the board checks the token
                packetId = 1,
                payload = payload,
            )

            toRadio.value = frame
            val writeOk = gatt.writeCharacteristic(toRadio)
            Log.i(TAG, "Claim write result: $writeOk")

            if (!writeOk) {
                gatt.disconnect()
                gatt.close()
                return ClaimResult.ConnectionFailed("Write failed")
            }

            // For now, treat successful write as claimed.
            // Reading the response requires subscribing to FromRadio
            // notifications and parsing the PRIVATE_APP response —
            // that's the next step after this basic flow works.
            gatt.disconnect()
            gatt.close()

            Log.i(TAG, "Claim sent successfully")
            return ClaimResult.Success(device.address)

        } catch (e: Exception) {
            Log.e(TAG, "Claim failed", e)
            runCatching { gatt.disconnect(); gatt.close() }
            return ClaimResult.ConnectionFailed(e.message ?: "Claim error")
        }
    }

    private suspend fun scanForMeshtasticDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val deferred = CompletableDeferred<BluetoothDevice>()
        val scanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("BLE scanner unavailable")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: ""
                val addr = result.device.address
                Log.d(TAG, "Scan hit: name='$name' addr=$addr rssi=${result.rssi}")

                // Match Meshtastic devices by service UUID in the advertisement
                // or by device name prefix (Meshtastic boards advertise as "Meshtastic XXXX")
                val serviceUuids = result.scanRecord?.serviceUuids ?: emptyList()
                val isMeshtastic = serviceUuids.any { it.uuid == MESHTASTIC_SERVICE_UUID }
                        || name.startsWith("Meshtastic", ignoreCase = true)
                        || name.startsWith("Mezulla", ignoreCase = true)

                if (isMeshtastic && !deferred.isCompleted) {
                    Log.i(TAG, "Found Meshtastic device: $name ($addr)")
                    deferred.complete(result.device)
                    scanner.stopScan(this)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                deferred.completeExceptionally(RuntimeException("BLE scan failed: $errorCode"))
            }
        }

        // Scan without service UUID filter — some boards don't include
        // the UUID in their advertisement data. We match by name or UUID.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, callback)

        return withTimeout(SCAN_TIMEOUT_MS) {
            deferred.await()
        }
    }

    private suspend fun connectGatt(device: BluetoothDevice): BluetoothGatt {
        val deferred = CompletableDeferred<BluetoothGatt>()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "GATT connected, discovering services...")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!deferred.isCompleted) {
                            deferred.completeExceptionally(RuntimeException("Disconnected during connect"))
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered")
                    deferred.complete(gatt)
                } else {
                    deferred.completeExceptionally(RuntimeException("Service discovery failed: $status"))
                }
            }
        }

        device.connectGatt(context, false, callback)

        return withTimeout(CONNECT_TIMEOUT_MS) {
            deferred.await()
        }
    }
}

sealed interface ClaimResult {
    data class Success(val deviceAddress: String) : ClaimResult
    data object BoardNotFound : ClaimResult
    data object BluetoothUnavailable : ClaimResult
    data object BluetoothDisabled : ClaimResult
    data class ConnectionFailed(val reason: String) : ClaimResult
    data class ClaimRejected(val status: PairingStatus) : ClaimResult
}
