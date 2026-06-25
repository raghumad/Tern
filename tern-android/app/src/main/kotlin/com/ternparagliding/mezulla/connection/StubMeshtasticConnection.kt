package com.ternparagliding.mezulla.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A no-real-IO [MeshtasticConnection] for unit tests.
 *
 * Tests drive inbound events via [emit] and observe outbound commands via
 * [sentPositions] / [sentAlerts]. There is no Meshtastic protobuf
 * encoding, no BLE, no simulator playback — just enough plumbing to prove
 * a subscriber sees what we hand it, in the order we hand it.
 *
 * Use cases:
 *  - WS2.1 smoke test in this package (interface compiles, subscriber
 *    receives events).
 *  - WS2.4 redux middleware test will inject events here and assert
 *    redux state changes.
 *  - WS3 UI tests can drive map state without standing up a simulator.
 *
 * For the IGC-driven swarm-simulator-backed connection see WS2.2; this
 * stub deliberately does not pretend to be that.
 *
 * Threading: [MutableSharedFlow] with `extraBufferCapacity = 64` so a test
 * can `emit()` synchronously without suspending. If a test bursts more
 * than 64 events before the subscriber catches up, [emit] will suspend —
 * that is intentional, not a bug.
 */
class StubMeshtasticConnection(
    override val pairedBoardId: String? = "!stubboard",
    initialLinkState: LinkState = LinkState.UP,
    override val selfNodeNumber: Long? = null,
) : MeshtasticConnection {

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
    private val _sentPositions = mutableListOf<PeerPosition.Fix>()
    private val _sentAlerts = mutableListOf<PeerPosition.Fix?>()

    @Volatile
    override var linkState: LinkState = initialLinkState
        private set

    /** What sendOwnPosition was called with, in call order. Read-only view. */
    val sentPositions: List<PeerPosition.Fix> get() = _sentPositions.toList()

    /**
     * What sendAlert was called with, in call order. Read-only view.
     * Each entry is the lastKnownPosition the caller passed (which may be null).
     */
    val sentAlerts: List<PeerPosition.Fix?> get() = _sentAlerts.toList()

    override fun events(): Flow<MeshEvent> = _events.asSharedFlow()

    /** Inject an event into the inbound stream. Suspends if the buffer is full. */
    suspend fun emit(event: MeshEvent) {
        if (event is MeshEvent.LinkStateChange) {
            linkState = event.newState
        }
        _events.emit(event)
    }

    /** Flip link state and publish the matching event in one call. */
    suspend fun setLinkState(newState: LinkState) {
        emit(MeshEvent.LinkStateChange(newState))
    }

    override suspend fun sendOwnPosition(position: PeerPosition.Fix) {
        if (linkState != LinkState.UP) return
        _sentPositions.add(position)
    }

    /**
     * Stub policy: returns [MeshtasticConnection.SendResult.Acked] when
     * the link is UP, [MeshtasticConnection.SendResult.NoLink] otherwise.
     * Tests that need to assert the NoAck branch should subclass and
     * override.
     */
    override suspend fun sendAlert(
        lastKnownPosition: PeerPosition.Fix?,
        maxRetries: Int,
    ): MeshtasticConnection.SendResult {
        if (linkState != LinkState.UP) return MeshtasticConnection.SendResult.NoLink
        _sentAlerts.add(lastKnownPosition)
        return MeshtasticConnection.SendResult.Acked
    }
}
