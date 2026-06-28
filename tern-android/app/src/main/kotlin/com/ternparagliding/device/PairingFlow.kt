package com.ternparagliding.device

/**
 * The **add-a-device flow** — the user-facing pairing logic, designed to *just work* and, when
 * it can't, fail with one clear message and one clear fix, **never** cascading into a new reason
 * after the pilot acts. See `docs/design/ble-device-workflow.md`.
 *
 * Pure and device-agnostic so the whole policy — including the headline *no-cascade* guarantee —
 * is claim-testable on the JVM. The Compose UI renders [PairingPhase] and the [UserMessage]s;
 * the Android layer supplies the [PairingEnvironment] and the scan/connect/claim events.
 */

/** A friendly, reviewable, testable piece of UI copy. Centralised here, not scattered in views. */
data class UserMessage(val title: String, val body: String, val cta: String)

/** A precondition that must hold before we even scan — the gate that prevents the cascade. */
enum class Precondition { BLUETOOTH_PERMISSION, BLUETOOTH_ON, LOCATION_ON }

/**
 * The live environment the gate reads. [locationRequiredForScan] is supplied by the Android
 * layer (false on API 31+ declaring `neverForLocation`) so we never demand a needless action.
 */
data class PairingEnvironment(
    val hasBluetoothPermission: Boolean,
    val bluetoothOn: Boolean,
    val locationOn: Boolean,
    val locationRequiredForScan: Boolean,
)

/** One row of the "Get ready" checklist. */
data class ReadinessItem(val precondition: Precondition, val met: Boolean)

object PairingReadiness {

    /**
     * The full readiness checklist for [env], in display order. This returns **every** applicable
     * precondition at once (met and unmet) — the structural anti-cascade move: the pilot sees all
     * they need up front, so fixing one never reveals a fresh blocker on the next screen.
     */
    fun checklist(env: PairingEnvironment): List<ReadinessItem> = buildList {
        add(ReadinessItem(Precondition.BLUETOOTH_PERMISSION, env.hasBluetoothPermission))
        add(ReadinessItem(Precondition.BLUETOOTH_ON, env.bluetoothOn))
        if (env.locationRequiredForScan) add(ReadinessItem(Precondition.LOCATION_ON, env.locationOn))
    }

    /** Unmet preconditions only (what still needs fixing). Empty ⇒ ready to scan. */
    fun unmet(env: PairingEnvironment): List<Precondition> =
        checklist(env).filterNot { it.met }.map { it.precondition }

    /** All applicable preconditions satisfied — safe to start scanning. */
    fun isReady(env: PairingEnvironment): Boolean = unmet(env).isEmpty()

    /** The friendly prompt for a precondition the pilot needs to fix. */
    fun message(p: Precondition): UserMessage = when (p) {
        Precondition.BLUETOOTH_PERMISSION -> UserMessage(
            "Allow Bluetooth", "Tern needs Bluetooth access to find your device.", "Grant access",
        )
        Precondition.BLUETOOTH_ON -> UserMessage(
            "Turn on Bluetooth", "Bluetooth is off — turn it on so Tern can connect.", "Turn on",
        )
        Precondition.LOCATION_ON -> UserMessage(
            "Turn on Location",
            "Android needs Location on to find Bluetooth devices nearby. Tern never tracks where you are.",
            "Turn on location",
        )
    }
}

/** Where the add flow is. The UI is a pure render of this. */
sealed interface PairingPhase {
    /** Blocked on preconditions — show the checklist; auto-advances when all green. */
    data class GetReady(val items: List<ReadinessItem>) : PairingPhase
    /** Scanning for nearby devices (RSSI-sorted as they arrive). */
    object Searching : PairingPhase
    /** Found candidates — the pilot picks theirs (closest/strongest first). */
    data class Choosing(val candidates: List<ScanCandidate>) : PairingPhase
    /** Connecting to (and, for a Mezulla, claiming) the chosen device. */
    data class Connecting(val candidate: ScanCandidate) : PairingPhase
    /** Done — the device is remembered and will auto-connect from now on. */
    data class Added(val device: RememberedDevice) : PairingPhase
    /** A recoverable failure: a clear message + the single corrective action. */
    data class Failed(val message: UserMessage) : PairingPhase
}

/** Why a pairing *attempt* (not a precondition) failed — each maps to one friendly message. */
enum class PairingError { DEVICE_NOT_FOUND, CONNECT_FAILED, CLAIM_ALREADY_OWNED, CLAIM_FAILED }

object PairingFlow {

    fun message(error: PairingError, deviceName: String? = null): UserMessage {
        val who = deviceName ?: "your device"
        return when (error) {
            PairingError.DEVICE_NOT_FOUND -> UserMessage(
                "Can't find your device",
                "Make sure it's switched on, then hold your phone right next to it.",
                "Search again",
            )
            PairingError.CONNECT_FAILED -> UserMessage(
                "Couldn't connect", "Move closer to $who — we'll keep trying.", "Search again",
            )
            PairingError.CLAIM_ALREADY_OWNED -> UserMessage(
                "Already paired elsewhere",
                "$who is paired to another phone. Reset it (hold the button 5 s), then search again.",
                "Search again",
            )
            PairingError.CLAIM_FAILED -> UserMessage(
                "Pairing didn't finish", "Move closer to the board and try again.", "Try again",
            )
        }
    }

    /**
     * The opening phase: the readiness gate if anything's unmet, else straight to searching.
     */
    fun begin(env: PairingEnvironment): PairingPhase =
        if (PairingReadiness.isReady(env)) PairingPhase.Searching
        else PairingPhase.GetReady(PairingReadiness.checklist(env))

    /**
     * Re-evaluate after **any** corrective action — a CTA, a "Search again", or an environment
     * change. This is the no-cascade rule in code: we always recompute the *whole* readiness gate
     * and only proceed to searching when every precondition is green, so a fix never drops the
     * pilot straight into a retry that then fails for a different precondition reason.
     */
    fun recheck(env: PairingEnvironment): PairingPhase = begin(env)
}
