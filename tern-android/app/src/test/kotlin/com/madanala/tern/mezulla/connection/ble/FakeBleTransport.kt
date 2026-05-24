package com.madanala.tern.mezulla.connection.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test-only [BleTransport] that lets a test inject transport events and
 * observe outbound `ToRadio` writes. No Android types touched — the whole
 * point is to exercise [BleConnection]'s link-state machine and codec
 * routing under pure-JVM `runTest`.
 */
internal class FakeBleTransport : BleTransport {

    private val _events = MutableSharedFlow<BleTransportEvent>(extraBufferCapacity = 64)
    private val _writes = mutableListOf<ByteArray>()

    /** Pretend the GATT writes always succeed unless [writesShouldFail] flips. */
    @Volatile
    var writesShouldFail: Boolean = false

    val writes: List<ByteArray> get() = _writes.toList()

    @Volatile var started: Boolean = false
        private set

    @Volatile var stopped: Boolean = false
        private set

    override fun events(): Flow<BleTransportEvent> = _events.asSharedFlow()

    override suspend fun start() {
        started = true
    }

    override suspend fun stop() {
        stopped = true
    }

    override suspend fun writeToRadio(toRadioBytes: ByteArray): Boolean {
        if (writesShouldFail) return false
        _writes.add(toRadioBytes)
        return true
    }

    /** Push a transport event into [BleConnection]. */
    suspend fun emit(event: BleTransportEvent) = _events.emit(event)
}
