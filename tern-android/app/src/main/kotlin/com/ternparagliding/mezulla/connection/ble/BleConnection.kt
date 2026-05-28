package com.ternparagliding.mezulla.connection.ble

import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.MeshtasticConnection
import com.ternparagliding.mezulla.connection.PeerPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Real BLE implementation of [MeshtasticConnection]. Talks to a
 * Meshtastic-flashed LilyGo board over GATT.
 *
 * Scope of WS4.3 (this skeleton):
 *  - Hardcoded MAC, no pairing UI (WS5).
 *  - Always-on auto-reconnect on unexpected drop.
 *  - Silent when the board is absent — no toasts, no banners; the layer
 *    above turns LinkState transitions into UX.
 *  - Subset of Meshtastic packets handled (POSITION_APP, NODEINFO_APP,
 *    TELEMETRY_APP, ALERT_APP — see [MeshPacketCodec]).
 *
 * Design notes:
 *  - The Android Bluetooth surface is behind [BleTransport]. The
 *    link-state machine and the codec are pure Kotlin so they unit-test
 *    without Robolectric.
 *  - Outbound writes are best-effort. `sendOwnPosition` ignores failure
 *    silently (position is fire-and-forget). `sendAlert` returns
 *    [MeshtasticConnection.SendResult] so the SOS UI can react.
 *  - We do not currently parse ACKs off the inbound stream. `sendAlert`
 *    returns `Acked` when the BLE write succeeds (i.e. the board accepted
 *    the packet at the GATT layer). Real per-hop LoRa ACK tracking is a
 *    follow-up — flagged in the report.
 *  - Threading: events are collected on [scope]. Subscribers to
 *    [MeshtasticConnection.events] join the same SharedFlow. We use a
 *    SharedFlow with no replay (matches the WS2.1 contract — cold to
 *    late subscribers).
 *
 * @param ourNodeNumber the Meshtastic node number we present as the sender
 *   when encoding outbound packets. For the WS4.3 skeleton this is fixed
 *   per construction; a real pairing UI (WS5) will source it from the
 *   board's reported `MyNodeInfo`.
 */
class BleConnection internal constructor(
    override val pairedBoardId: String?,
    private val ourNodeNumber: Long,
    private val transport: BleTransport,
    private val scope: CoroutineScope,
) : MeshtasticConnection {

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)

    @Volatile
    override var linkState: LinkState = LinkState.NEVER_PAIRED
        private set

    private var collectorJob: Job? = null

    /**
     * Monotonic packet-id source for outbound MeshPacket.id. Meshtastic
     * uses this for dedupe / ACK correlation; the value just has to be
     * non-zero and reasonably unique within a few minutes of traffic.
     */
    private var nextPacketId: Int = 1

    private fun allocatePacketId(): Int {
        val id = nextPacketId
        nextPacketId = if (nextPacketId == Int.MAX_VALUE) 1 else nextPacketId + 1
        return id
    }

    /** Track whether we have ever observed the board this run. */
    private var everSeenBoard: Boolean = false

    override fun events(): Flow<MeshEvent> = _events.asSharedFlow()

    /**
     * Begin scanning + connecting. Idempotent — calling twice is a no-op.
     * After [start], the connection follows the BLE board around for as
     * long as [scope] is alive; cancel [scope] (or call [stop]) to release
     * everything.
     */
    suspend fun start() {
        if (collectorJob != null) return
        // If we have a paired board, the initial state is DOWN until the
        // transport reports otherwise. If no board has ever been paired
        // (constructor was given pairedBoardId == null), we stay in
        // NEVER_PAIRED and the caller should not have constructed us at
        // all — we still cope gracefully by not starting the transport.
        if (pairedBoardId == null) {
            // Stay NEVER_PAIRED. Do not start scanning.
            return
        }
        updateLinkState(LinkState.DOWN)
        // UNDISPATCHED so the collector subscribes synchronously before we
        // start the transport — otherwise an immediate Connected event
        // from the transport (or any test that emits between start() and
        // the next suspension point) would be lost to the no-replay
        // SharedFlow.
        collectorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events().collect { handleTransportEvent(it) }
        }
        transport.start()
    }

    /** Tear down the transport. Safe to call repeatedly. */
    suspend fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        transport.stop()
    }

    private suspend fun handleTransportEvent(event: BleTransportEvent) {
        when (event) {
            BleTransportEvent.Connected -> {
                everSeenBoard = true
                updateLinkState(LinkState.UP)
            }
            BleTransportEvent.Disconnected -> {
                // Stay paired; transport keeps scanning silently.
                updateLinkState(LinkState.DOWN)
            }
            BleTransportEvent.InitialScanTimeout -> {
                // Only signal NEVER_PAIRED → DOWN, not the other way. If we
                // have never observed the board in this session AND we have
                // no record of ever pairing one (pairedBoardId would be
                // null in that case — handled in start()), we would not
                // even be here. So in practice this just leaves us at DOWN.
                if (!everSeenBoard) {
                    // Already DOWN; nothing to emit. Subscribers above
                    // know "DOWN" means "paired but unreachable" which is
                    // already true.
                }
            }
            is BleTransportEvent.FromRadioFrame -> {
                val decoded = MeshPacketCodec.decodeFromRadio(event.bytes) ?: return
                _events.emit(decoded)
            }
        }
    }

    private suspend fun updateLinkState(newState: LinkState) {
        if (linkState == newState) return
        linkState = newState
        _events.emit(MeshEvent.LinkStateChange(newState))
    }

    override suspend fun sendOwnPosition(position: PeerPosition.Fix) {
        if (linkState != LinkState.UP) return
        val bytes = MeshPacketCodec.encodeToRadioPosition(
            fromNodeNumber = ourNodeNumber,
            packetId = allocatePacketId(),
            fix = position,
        )
        // Best-effort. Failures are silent — position is by nature lossy
        // and a hidden retry storm is worse than a missed broadcast.
        runCatching { transport.writeToRadio(bytes) }
    }

    /**
     * Write a pre-encoded `ToRadio` frame straight to the transport, with
     * none of the bookkeeping `sendOwnPosition` / `sendAlert` apply (no
     * packet-id allocation, no link-state gate, no retry, no encoding).
     *
     * This is a test/sim back door for the
     * [com.ternparagliding.sim.injector.VirtualPeerInjector] loopback
     * trick: the injector pre-builds Position frames with `from = peer
     * node number`, hands them here, the board accepts them on the BLE
     * write characteristic, decides they look like packets it just heard
     * over the air (because that's what `from` says), and reflects them
     * back to us on the FromRadio stream as `PeerPositionUpdate`s.
     *
     * Named bluntly so an unsuspecting caller has to opt into the bypass.
     * Production code (UI, redux, alert path) must continue to use
     * [sendOwnPosition] / [sendAlert]; calling this from production would
     * mean an outbound packet had no `id` allocated and would skip the
     * link-state check.
     *
     * Returns whatever [BleTransport.writeToRadio] returned. Caller decides
     * what to do on `false` (typically: drop the tick — the link is down).
     */
    internal suspend fun injectRawToRadio(toRadioBytes: ByteArray): Boolean =
        runCatching { transport.writeToRadio(toRadioBytes) }.getOrDefault(false)

    override suspend fun sendAlert(
        lastKnownPosition: PeerPosition.Fix?,
        maxRetries: Int,
    ): MeshtasticConnection.SendResult {
        if (linkState != LinkState.UP) return MeshtasticConnection.SendResult.NoLink
        val bytes = MeshPacketCodec.encodeToRadioAlert(
            fromNodeNumber = ourNodeNumber,
            packetId = allocatePacketId(),
            lastKnownPosition = lastKnownPosition,
        )
        // WS4.3 scope: we treat "board accepted the write" as Acked. Real
        // per-hop LoRa ACK tracking parses the inbound ROUTING_APP
        // response — left for a follow-up story alongside SOS payload
        // schema firming up (see docs/architecture/meshtastic-connection.md
        // open questions).
        var attempt = 0
        while (attempt < maxRetries) {
            val ok = runCatching { transport.writeToRadio(bytes) }.getOrDefault(false)
            if (ok) return MeshtasticConnection.SendResult.Acked
            attempt++
        }
        return MeshtasticConnection.SendResult.NoAck
    }
}
