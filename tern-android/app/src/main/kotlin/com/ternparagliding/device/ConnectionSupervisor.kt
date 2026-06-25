package com.ternparagliding.device

/**
 * The **self-healing connection lifecycle** — see `docs/design/ble-device-workflow.md`.
 *
 * A pure state machine, one per supervised device, whose only job is to drive the link toward
 * CONNECTED and keep it there *forever*, recovering from every drop, GATT flake, power-cycle,
 * range loss, and Bluetooth-off automatically — **never** asking the pilot to act. The Android
 * layer feeds it [DeviceEvent]s (from the BLE callbacks) and executes the [DeviceCommand] it
 * returns; all the *decisions* live here, so they're claim-testable on the JVM with no hardware.
 *
 * The only intentional stops are [DeviceEvent.Pause] (→ OFF) and forgetting the device (the
 * registry drops it and the caller tears the supervisor down).
 */
enum class LinkState { OFF, SCANNING, CONNECTING, CONNECTED }

/** Why a link ended — recorded in the connection log so a flaky device is visible after a flight. */
enum class DropReason { LINK_LOST, OUT_OF_RANGE, BLUETOOTH_OFF, POWER_CYCLE, GATT_ERROR, USER }

/** Inputs from the Android BLE layer (and the pilot's pause/resume). */
sealed interface DeviceEvent {
    /** Remembered & unpaused — at launch or when the pilot un-pauses. */
    object Resume : DeviceEvent
    object Pause : DeviceEvent
    object BluetoothOff : DeviceEvent
    object BluetoothOn : DeviceEvent
    /** The scan found the device (rssi for diagnostics). */
    data class Sighted(val rssi: Int = 0) : DeviceEvent
    /** A scan window elapsed without finding it — still out of range. */
    object ScanTimeout : DeviceEvent
    object Connected : DeviceEvent
    data class ConnectFailed(val reason: DropReason = DropReason.GATT_ERROR) : DeviceEvent
    data class Disconnected(val reason: DropReason = DropReason.LINK_LOST) : DeviceEvent
}

/** What the Android layer should do next. The supervisor decides; the transport executes. */
sealed interface DeviceCommand {
    object StartScan : DeviceCommand
    object Connect : DeviceCommand
    object Disconnect : DeviceCommand
    /** Wait [delayMs], then scan again — the adaptive backoff while absent/failing. */
    data class ScheduleRetry(val delayMs: Long) : DeviceCommand
    object Idle : DeviceCommand
}

/** A single connection-log entry — a byproduct of each state transition (visibility, not control). */
data class ConnectionEvent(
    val atMs: Long,
    val kind: Kind,
    val reason: DropReason? = null,
    /** For a heal: how long the preceding outage lasted (ms). */
    val outageMs: Long? = null,
) {
    enum class Kind { SCANNING, CONNECTING, LINKED, DROPPED, OUT_OF_RANGE, BLUETOOTH_OFF, PAUSED }
}

/** Bounded per-device history; oldest entries drop off. Survives the session, not persisted. */
class ConnectionLog(private val capacity: Int = 50) {
    private val buf = ArrayDeque<ConnectionEvent>()
    fun add(e: ConnectionEvent) {
        buf.addLast(e)
        while (buf.size > capacity) buf.removeFirst()
    }
    fun entries(): List<ConnectionEvent> = buf.toList()
    val latest: ConnectionEvent? get() = buf.lastOrNull()
    val size: Int get() = buf.size
}

/** Supervisor state — small enough to be a pure value the [ConnectionSupervisor] folds events into. */
data class SupervisorState(
    val link: LinkState = LinkState.OFF,
    /** Consecutive failed connect/scan cycles since the last successful link (drives backoff). */
    val retries: Int = 0,
    /** Last scan sighting or successful connect (ms) — the "recently seen ⇒ retry fast" window. */
    val lastSeenMs: Long? = null,
    /** Start of the current outage (ms), set on leaving CONNECTED, cleared on re-link. */
    val droppedAtMs: Long? = null,
    /** True once we've logged OUT_OF_RANGE for the current absence (so we don't spam it). */
    val loggedOutOfRange: Boolean = false,
)

data class Transition(
    val state: SupervisorState,
    val command: DeviceCommand,
    /** A log entry to append, if this transition is worth recording. */
    val log: ConnectionEvent? = null,
)

object ConnectionSupervisor {

    /** Retry/scan cadence while the device was seen recently — fast in-flight recovery. */
    const val FAST_RETRY_MS = 2_000L
    /** Backoff ceiling once a device stays absent — conserve battery, still always trying. */
    const val MAX_RETRY_MS = 30_000L
    /** "Recently seen" window: within this of the last sighting/link, always retry fast. */
    const val FAST_WINDOW_MS = 60_000L

    /**
     * Adaptive backoff: fast while recently seen (so a mid-thermal drop heals in ~2 s), widening
     * exponentially toward the cap as an absence drags on (battery), and **snapping back to fast
     * the instant the device is sighted again** (via [SupervisorState.lastSeenMs]).
     */
    fun backoffMs(retries: Int, lastSeenMs: Long?, nowMs: Long): Long {
        val recentlySeen = lastSeenMs != null && (nowMs - lastSeenMs) < FAST_WINDOW_MS
        if (recentlySeen) return FAST_RETRY_MS
        val grown = FAST_RETRY_MS shl retries.coerceIn(0, 5) // 2s,4s,8s,16s,32s,64s
        return grown.coerceAtMost(MAX_RETRY_MS)
    }

    /**
     * Fold one [event] into the supervisor [state]. Total and deterministic — the whole recovery
     * policy, expressed as data in / data out.
     */
    fun step(state: SupervisorState, event: DeviceEvent, nowMs: Long): Transition = when (event) {

        DeviceEvent.Pause -> Transition(
            state = state.copy(link = LinkState.OFF, retries = 0, droppedAtMs = null, loggedOutOfRange = false),
            command = DeviceCommand.Disconnect,
            log = ConnectionEvent(nowMs, ConnectionEvent.Kind.PAUSED, DropReason.USER),
        )

        DeviceEvent.Resume ->
            if (state.link == LinkState.OFF) startScanning(state, nowMs)
            else Transition(state, DeviceCommand.Idle) // already working — no-op

        DeviceEvent.BluetoothOn ->
            if (state.link == LinkState.OFF) startScanning(state, nowMs)
            else Transition(state, DeviceCommand.Idle)

        DeviceEvent.BluetoothOff -> {
            val outageStart = state.droppedAtMs ?: if (state.link == LinkState.CONNECTED) nowMs else null
            Transition(
                state = state.copy(link = LinkState.OFF, droppedAtMs = outageStart, loggedOutOfRange = false),
                command = DeviceCommand.Idle, // wait for BluetoothOn; resume is automatic
                log = ConnectionEvent(nowMs, ConnectionEvent.Kind.BLUETOOTH_OFF, DropReason.BLUETOOTH_OFF),
            )
        }

        is DeviceEvent.Sighted ->
            if (state.link == LinkState.SCANNING) {
                Transition(
                    state = state.copy(link = LinkState.CONNECTING, lastSeenMs = nowMs, loggedOutOfRange = false),
                    command = DeviceCommand.Connect,
                    log = ConnectionEvent(nowMs, ConnectionEvent.Kind.CONNECTING),
                )
            } else {
                // A stray sighting while connecting/connected just refreshes the fast-window.
                Transition(state.copy(lastSeenMs = nowMs), DeviceCommand.Idle)
            }

        DeviceEvent.ScanTimeout ->
            if (state.link == LinkState.SCANNING) {
                val log = if (!state.loggedOutOfRange)
                    ConnectionEvent(nowMs, ConnectionEvent.Kind.OUT_OF_RANGE, DropReason.OUT_OF_RANGE) else null
                Transition(
                    state = state.copy(retries = state.retries + 1, loggedOutOfRange = true),
                    command = DeviceCommand.ScheduleRetry(backoffMs(state.retries + 1, state.lastSeenMs, nowMs)),
                    log = log,
                )
            } else Transition(state, DeviceCommand.Idle)

        DeviceEvent.Connected -> {
            val outage = state.droppedAtMs?.let { nowMs - it }
            Transition(
                state = state.copy(
                    link = LinkState.CONNECTED, retries = 0, lastSeenMs = nowMs,
                    droppedAtMs = null, loggedOutOfRange = false,
                ),
                command = DeviceCommand.Idle,
                log = ConnectionEvent(nowMs, ConnectionEvent.Kind.LINKED, outageMs = outage),
            )
        }

        is DeviceEvent.ConnectFailed -> Transition(
            state = state.copy(link = LinkState.SCANNING, retries = state.retries + 1),
            command = DeviceCommand.ScheduleRetry(backoffMs(state.retries + 1, state.lastSeenMs, nowMs)),
            log = ConnectionEvent(nowMs, ConnectionEvent.Kind.DROPPED, event.reason),
        )

        is DeviceEvent.Disconnected -> Transition(
            // Begin healing immediately — recently linked, so backoff is fast.
            state = state.copy(link = LinkState.SCANNING, retries = 0, droppedAtMs = nowMs, loggedOutOfRange = false),
            command = DeviceCommand.StartScan,
            log = ConnectionEvent(nowMs, ConnectionEvent.Kind.DROPPED, event.reason),
        )
    }

    private fun startScanning(state: SupervisorState, nowMs: Long): Transition = Transition(
        state = state.copy(link = LinkState.SCANNING, loggedOutOfRange = false),
        command = DeviceCommand.StartScan,
        log = ConnectionEvent(nowMs, ConnectionEvent.Kind.SCANNING),
    )
}
