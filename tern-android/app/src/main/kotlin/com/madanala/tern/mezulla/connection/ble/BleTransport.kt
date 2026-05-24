package com.madanala.tern.mezulla.connection.ble

import kotlinx.coroutines.flow.Flow

/**
 * The thin seam between [BleConnection] and the Android Bluetooth APIs.
 *
 * Everything above this interface is plain Kotlin (no Android types) so the
 * link-state machine and the protobuf codec can be exercised under
 * pure-JVM unit tests, no Robolectric required. The Android-specific
 * scan-then-GATT lifecycle lives in [AndroidBleTransport]; tests use
 * `FakeBleTransport` in the test sources.
 *
 * The contract is intentionally tiny:
 *  - [start] kicks off "find and stay connected to the target board." The
 *    transport scans, connects, discovers services, subscribes to
 *    FromNum, and emits events. After unexpected disconnect it MUST keep
 *    trying — `BleConnection` does not own reconnection logic.
 *  - [stop] tears everything down (cancel scan, disconnect, close GATT)
 *    silently. Safe to call repeatedly.
 *  - [events] is the single inbound stream of transport events.
 *  - [writeToRadio] pushes a `ToRadio` protobuf to the board. Returns false
 *    if the link is not currently UP — the caller decides what to do.
 *
 * "Connection absent" is not an error: per project-tern-graceful-degradation
 * the transport simply keeps scanning silently.
 */
internal interface BleTransport {

    fun events(): Flow<BleTransportEvent>

    suspend fun start()

    suspend fun stop()

    /** Returns true if the board accepted the write (queued at GATT). */
    suspend fun writeToRadio(toRadioBytes: ByteArray): Boolean
}

/** Events the [BleTransport] emits up to [BleConnection]. */
internal sealed interface BleTransportEvent {

    /** The transport finished service discovery and is ready to exchange data. */
    data object Connected : BleTransportEvent

    /**
     * Connection dropped for any reason (peer disappeared, GATT error,
     * stack reset). The transport will resume scanning silently.
     */
    data object Disconnected : BleTransportEvent

    /**
     * Initial scan window has elapsed and we have never seen this board.
     * Used to drive `NEVER_PAIRED → DOWN` for boards we have a MAC for but
     * have not yet observed. Emitted at most once per [BleTransport.start].
     */
    data object InitialScanTimeout : BleTransportEvent

    /** One FromRadio protobuf frame from the board. */
    data class FromRadioFrame(val bytes: ByteArray) : BleTransportEvent {
        override fun equals(other: Any?): Boolean =
            other is FromRadioFrame && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}
