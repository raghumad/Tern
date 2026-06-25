package com.ternparagliding.device

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * The unified Bluetooth-device model — see `docs/design/ble-device-workflow.md`.
 *
 * One flow for every device (XC Tracer vario, Mezulla LoRa board, future sensors): the pilot
 * adds a device once, it's **remembered**, and a self-healing supervisor keeps it linked forever
 * after. This file is the pure, device-agnostic core: the remembered model, the registry
 * operations, the proximity sort that disambiguates "which one is mine", and the persistence
 * codec. No Android/BLE here — all claim-testable on the JVM.
 */

/** What kind of device — drives the driver (scan match, connect, whether a claim is needed). */
enum class DeviceType { VARIO, MEZULLA }

/**
 * A device the pilot has added. Persisted; on launch the manager starts a supervisor for each
 * non-[paused] one. [claimToken] is the Mezulla ownership token (null for a read-only vario).
 */
data class RememberedDevice(
    val id: String,
    val type: DeviceType,
    val displayName: String,
    val mac: String,
    val claimToken: String? = null,
    /** The one deliberate "stop trying" control. Paused devices aren't supervised. */
    val paused: Boolean = false,
)

/**
 * The pilot's remembered-device list. Pure operations; the registry is the source of truth the
 * supervisors are spun up from. Devices are keyed by [mac] (case-insensitive) — re-adding the
 * same physical device updates it in place rather than duplicating.
 */
data class DeviceRegistry(val devices: List<RememberedDevice> = emptyList()) {

    /** Add or replace by MAC (re-pairing the same device refreshes its name/token, no dupes). */
    fun add(device: RememberedDevice): DeviceRegistry =
        copy(devices = devices.filterNot { it.mac.equals(device.mac, ignoreCase = true) } + device)

    /** Remove a device entirely (its supervisor is torn down by the caller). */
    fun forget(id: String): DeviceRegistry =
        copy(devices = devices.filterNot { it.id == id })

    /** Pause/resume a device — the only intentional connect/disconnect the pilot performs. */
    fun setPaused(id: String, paused: Boolean): DeviceRegistry =
        copy(devices = devices.map { if (it.id == id) it.copy(paused = paused) else it })

    fun find(id: String): RememberedDevice? = devices.firstOrNull { it.id == id }

    /** The devices the manager should actively supervise (remembered and not paused). */
    fun active(): List<RememberedDevice> = devices.filter { !it.paused }
}

/**
 * One result from a live scan during "Add a device". RSSI is the disambiguator: the pilot's own
 * device, on their harness/phone, is the strongest.
 */
data class ScanCandidate(
    val name: String,
    val mac: String,
    val rssi: Int,
    val type: DeviceType,
)

/**
 * Sort scan results so the pilot's device is at the top: **strongest signal first** (closest =
 * yours), then by name and MAC so two identical-named varios are stable and distinguishable.
 */
fun sortByProximity(candidates: List<ScanCandidate>): List<ScanCandidate> =
    candidates.sortedWith(
        compareByDescending<ScanCandidate> { it.rssi }.thenBy { it.name }.thenBy { it.mac },
    )

/**
 * Pure (de)serialisation of the remembered list. The Android layer just hands the encoded string
 * to/from its prefs store; the JVM round-trip is claim-testable here. JSON via Jackson (already a
 * project dependency) — order-preserving and lossless including the nullable claim token.
 */
object DeviceCodec {
    private val mapper = jacksonObjectMapper()

    fun encode(devices: List<RememberedDevice>): String = mapper.writeValueAsString(devices)

    fun decode(json: String?): List<RememberedDevice> =
        if (json.isNullOrBlank()) emptyList() else runCatching { mapper.readValue<List<RememberedDevice>>(json) }.getOrDefault(emptyList())
}
