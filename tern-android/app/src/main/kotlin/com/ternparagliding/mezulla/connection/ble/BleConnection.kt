package com.ternparagliding.mezulla.connection.ble

import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.MeshtasticConnection
import com.ternparagliding.mezulla.connection.PeerPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

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

    override fun negotiatedMtu(): Int? = transport.currentMtu()
    override fun activePhy(): Int? = transport.currentPhy()
    override fun simulateDisconnectForTest() = transport.simulateDisconnectForTest()

    /**
     * Command the board to reboot in [rebootSeconds] (admin reboot over the
     * live link). This is a *real* link-loss drop — the board goes away,
     * re-advertises after boot, and the transport reconnects — unlike
     * [simulateDisconnectForTest], which is a graceful local GATT teardown
     * that leaves the board half-open. Used by T2/T3.
     */
    override suspend fun rebootBoardForTest(rebootSeconds: Int) {
        val boardNode = adminTargetNode()
        if (boardNode == null) {
            android.util.Log.w("BleConnection", "rebootBoardForTest: no board node yet — cannot address admin reboot")
            return
        }
        val bytes = MeshPacketCodec.encodeToRadioReboot(boardNode, allocatePacketId(), rebootSeconds)
        val ok = runCatching { transport.writeToRadio(bytes) }.getOrDefault(false)
        android.util.Log.i(
            "BleConnection",
            "[@${System.identityHashCode(this)}] rebootBoardForTest(${rebootSeconds}s) sent=$ok board=0x${boardNode.toString(16)}",
        )
    }

    /**
     * Set the board's PRIMARY channel to a Tern team (name + psk) — "set_team". An admin
     * `set_channel` over the live link; once the board applies it, only boards sharing this team
     * hear each other. Returns whether the GATT write was accepted (the best the BLE layer can
     * confirm — the LoRa-level apply happens async on the board). No-op + false when the link is
     * down or no board is paired.
     */
    suspend fun setTeam(name: String, psk: ByteArray): Boolean {
        if (linkState != LinkState.UP) {
            android.util.Log.w("BleConnection", "setTeam: link not UP ($linkState) — ignoring")
            return false
        }
        val boardNode = adminTargetNode()
        if (boardNode == null) {
            android.util.Log.w("BleConnection", "setTeam: no board node yet — cannot address admin set_channel")
            return false
        }
        val bytes = MeshPacketCodec.encodeToRadioSetChannel(boardNode, allocatePacketId(), name, psk)
        val ok = runCatching { transport.writeToRadio(bytes) }.getOrDefault(false)
        android.util.Log.i(
            "BleConnection",
            "[@${System.identityHashCode(this)}] setTeam('$name') sent=$ok board=0x${boardNode.toString(16)}",
        )
        return ok
    }

    /**
     * Rename the board — Tern's "set_owner". An admin `set_owner` over the live
     * link carrying the new Meshtastic owner name (long + short). Once applied,
     * the board's OLED, its NodeInfo broadcast, and therefore its label on every
     * phone reflect the new name. Returns whether the GATT write was accepted
     * (the LoRa-level apply happens async on the board). No-op + false when the
     * link is down or no board is paired.
     */
    suspend fun setOwner(longName: String, shortName: String): Boolean {
        if (linkState != LinkState.UP) {
            android.util.Log.w("BleConnection", "setOwner: link not UP ($linkState) — ignoring")
            return false
        }
        val boardNode = adminTargetNode()
        if (boardNode == null) {
            android.util.Log.w("BleConnection", "setOwner: no board node yet — cannot address admin set_owner")
            return false
        }
        val bytes = MeshPacketCodec.encodeToRadioSetOwner(boardNode, allocatePacketId(), longName, shortName)
        val ok = runCatching { transport.writeToRadio(bytes) }.getOrDefault(false)
        android.util.Log.i(
            "BleConnection",
            "[@${System.identityHashCode(this)}] setOwner('$longName'/'$shortName') sent=$ok board=0x${boardNode.toString(16)}",
        )
        return ok
    }

    /**
     * Set the board's LoRa **region** — Tern's automatic region-follows-location
     * (the reconcile lives in [com.ternparagliding.mezulla.MezullaConnectionManager]).
     * An admin `set_config(lora)` over the live link; the firmware applies it
     * live (no reboot) and, coming from UNSET, enables TX and regenerates keys.
     * Returns whether the GATT write was accepted (the LoRa-level apply happens
     * async on the board). On success we optimistically cache [boardRegion] so
     * a near-simultaneous reconcile tick doesn't re-send before the board's next
     * config stream confirms it. No-op + false when the link is down or no board
     * is paired.
     */
    suspend fun setRegion(regionCode: Int): Boolean {
        if (linkState != LinkState.UP) {
            android.util.Log.w("BleConnection", "setRegion: link not UP ($linkState) — ignoring")
            return false
        }
        val boardNode = adminTargetNode()
        if (boardNode == null) {
            android.util.Log.w("BleConnection", "setRegion: no board node yet — cannot address admin set_config")
            return false
        }
        val bytes = MeshPacketCodec.encodeToRadioSetLoraConfig(boardNode, allocatePacketId(), regionCode)
        val ok = runCatching { transport.writeToRadio(bytes) }.getOrDefault(false)
        if (ok) boardRegion = regionCode
        android.util.Log.i(
            "BleConnection",
            "[@${System.identityHashCode(this)}] setRegion($regionCode) sent=$ok board=0x${boardNode.toString(16)}",
        )
        return ok
    }

    private val heartbeatCounter = java.util.concurrent.atomic.AtomicInteger(0)
    override fun heartbeatsSent(): Int = heartbeatCounter.get()
    private var heartbeatJob: Job? = null

    // Bumped on every Connected, Disconnected, and stop(). An in-flight
    // runHandshake captures the epoch it was launched under; if the epoch
    // has moved on by the time it would drive the link UP, it bails. This
    // stops a handshake from a torn-down/superseded connection from
    // resurrecting the link state or starting a second heartbeat loop —
    // the bug behind the zombie BleConnection whose heartbeat kept firing
    // against a null gatt.
    private val connectionEpoch = java.util.concurrent.atomic.AtomicInteger(0)

    private var collectorJob: Job? = null

    /**
     * Monotonic packet-id source for outbound MeshPacket.id. Meshtastic
     * uses this for dedupe / ACK correlation.
     *
     * Seeded from epoch SECONDS, not 1: the board remembers recently-seen
     * (from, id) pairs and silently drops repeats. If every app launch
     * restarted ids at 1, the first admin/position after a reconnect would
     * collide with one the board saw in a previous session and be dropped
     * ("Ignore dupe incoming msg" — that's what was eating set_channel and
     * the first position on every reconnect). Epoch seconds guarantees each
     * launch starts ABOVE any id a prior session used, so no collision; it
     * fits a 32-bit packet id with hundreds of millions of headroom before
     * it would wrap within a session.
     */
    private var nextPacketId: Int = (System.currentTimeMillis() / 1000L).toInt()

    private fun allocatePacketId(): Int {
        val id = nextPacketId
        nextPacketId = if (nextPacketId == Int.MAX_VALUE) 1 else nextPacketId + 1
        return id
    }

    /** Track whether we have ever observed the board this run. */
    private var everSeenBoard: Boolean = false

    /**
     * The board's current LoRa region, as last read from its config stream
     * during the handshake. Null until the LoRa-config frame has been seen on
     * this connection; 0 means the board reports UNSET. The connection manager
     * reads this to decide whether to reconcile region to the pilot's GPS
     * location (see [com.ternparagliding.mezulla.MezullaConnectionManager]).
     * Volatile: written from the transport-event coroutine, read from the
     * manager's reconcile coroutine.
     */
    @Volatile
    var boardRegion: Int? = null
        private set

    /**
     * The board's own node number, as reported in its `MyNodeInfo` during the
     * handshake. This is the **authoritative** address for local admin commands
     * (set_team / set_region / reboot): the firmware handles an admin packet
     * locally only when `to` matches its own node number — otherwise it routes
     * it out as a PKI direct message and drops it for lack of a key (NAK /
     * Error=39), so the command never applies. The QR/pairing-derived
     * [pairedBoardId] can disagree (observed on LilyGo/ESP32, whose QR
     * advertises a node that differs from the firmware's real nodeNum), which is
     * exactly why set_team was silently dropped. [adminTargetNode] prefers this
     * value. Null until the my_info frame has been seen on this connection.
     * Volatile: written from the transport-event coroutine, read from the
     * setTeam / setRegion callers.
     */
    @Volatile
    var boardNodeNumber: Long? = null
        private set

    /**
     * The board's own node number, surfaced to [com.ternparagliding.mezulla.redux.PeerMiddleware]
     * so it can drop the board's own node from the peer roster (you are not
     * your own buddy). Same value as [boardNodeNumber]; named per the
     * [MeshtasticConnection] contract.
     */
    override val selfNodeNumber: Long? get() = boardNodeNumber

    /**
     * The node number to address local admin commands to. Prefers the board's
     * self-reported [boardNodeNumber] (from MyNodeInfo); falls back to the
     * QR/pairing-derived [pairedBoardId] until the handshake reports it. Logs
     * when the two disagree — that mismatch is what made admin packets get
     * dropped as remote PKI DMs.
     */
    private fun adminTargetNode(): Long? {
        val paired = pairedBoardId?.removePrefix("!")?.toLongOrNull(16)
        val reported = boardNodeNumber
        if (reported != null && paired != null && reported != paired) {
            android.util.Log.w(
                "BleConnection",
                "admin target: board reports node 0x${reported.toString(16)} but pairedBoardId is " +
                    "0x${paired.toString(16)} — using the board's reported node (admin to the QR node " +
                    "is dropped as a remote DM)",
            )
        }
        return reported ?: paired
    }

    override fun events(): Flow<MeshEvent> = _events.asSharedFlow()

    /**
     * Begin scanning + connecting. Idempotent — calling twice is a no-op.
     * After [start], the connection follows the BLE board around for as
     * long as [scope] is alive; cancel [scope] (or call [stop]) to release
     * everything.
     */
    suspend fun start() {
        if (collectorJob != null) return
        android.util.Log.i("BleConnection", "[@${System.identityHashCode(this)}] start() called, pairedBoardId=$pairedBoardId")
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
        // Invalidate any in-flight handshake so it can't drive state or
        // start a heartbeat after we've stopped.
        connectionEpoch.incrementAndGet()
        collectorJob?.cancel()
        collectorJob = null
        // Without these the heartbeat coroutine (launched on the shared
        // manager scope) outlives the connection and keeps firing against
        // a dead/null gatt, and handshake waiters leak. This was the
        // zombie-connection leak across back-to-back pairs.
        stopPeriodicHeartbeat()
        handshakeStages.values.forEach { it.cancel() }
        handshakeStages.clear()
        transport.stop()
    }

    private suspend fun handleTransportEvent(event: BleTransportEvent) {
        android.util.Log.i("BleConnection", "[@${System.identityHashCode(this)}] event=${event.javaClass.simpleName}")
        when (event) {
            BleTransportEvent.Connected -> {
                everSeenBoard = true
                // Drive the Meshtastic phone-protocol handshake in a
                // separate coroutine so we don't block the transport
                // event pump while we wait for config_complete_id
                // replies. updateLinkState(UP) only after the handshake
                // succeeds, so consumers don't try to send positions
                // before the firmware is actually ready.
                val epoch = connectionEpoch.incrementAndGet()
                scope.launch { runHandshake(epoch) }
            }
            BleTransportEvent.Disconnected -> {
                // Stay paired; transport keeps scanning silently. Bump the
                // epoch so any handshake still awaiting a stage can't drive
                // the link back UP after we've gone DOWN.
                connectionEpoch.incrementAndGet()
                // Cancel (not complete) the waiters: completion was read as
                // "firmware replied" and let the handshake march on to UP.
                // Cancellation surfaces as a failed stage instead.
                handshakeStages.values.forEach { it.cancel() }
                handshakeStages.clear()
                stopPeriodicHeartbeat()
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
                // Capture the board's LoRa region from config frames in the
                // handshake bundle. Separate from decodeFromRadio (which only
                // models packets we surface as MeshEvents) so the manager can
                // reconcile region to the pilot's GPS fix. Cheap on hot frames:
                // a position packet is one tag read + one skip before null.
                MeshPacketCodec.decodeLoraRegion(event.bytes)?.let { boardRegion = it }
                // Capture the board's own node number from MyNodeInfo so we
                // address admin commands (set_team / set_region) to the real
                // node, not the QR-derived id which can be wrong (LilyGo).
                MeshPacketCodec.decodeMyNodeNum(event.bytes)?.let { boardNodeNumber = it }
                val decoded = MeshPacketCodec.decodeFromRadio(event.bytes) ?: return
                // Internal handshake signal — fire the awaiting deferred
                // BEFORE emitting to consumers so the handshake driver
                // proceeds without competing with downstream observers.
                if (decoded is MeshEvent.ConfigComplete) {
                    handshakeStages.remove(decoded.configId)?.complete(Unit)
                }
                _events.emit(decoded)
            }
        }
    }

    /**
     * Two-stage Meshtastic phone-protocol handshake. Mirrors the
     * official Meshtastic-Android client (core/data MeshConnectionManagerImpl):
     *
     *   pre-handshake heartbeat
     *   → 100 ms delay (give firmware time to settle)
     *   → want_config_id(CONFIG_NONCE)     — pulls device + module config + channels
     *   → wait for ConfigComplete(CONFIG_NONCE)        — stall timeout 30 s
     *   → want_config_id(NODE_INFO_NONCE)  — pulls full nodeDB
     *   → wait for ConfigComplete(NODE_INFO_NONCE)     — stall timeout 60 s
     *   → LinkState.UP
     *
     * Without this, the firmware's data plane stays gated — FromRadio
     * drains return empty and FromNum notifications never fire even when
     * LoRa traffic arrives. The injected-peer test seam bypasses BLE
     * entirely so we missed this until the first real dual-board smoke.
     */
    private suspend fun runHandshake(epoch: Int) {
        val tag = "BleConnection.handshake"
        val instance = System.identityHashCode(this)

        // Step 1: heartbeat (wake firmware's phone-protocol state machine)
        val heartbeatOk = sendHeartbeat()
        android.util.Log.i(tag, "[@$instance] heartbeat sent=$heartbeatOk")
        delay(100)

        // Step 2: Stage 1 — device config
        val stage1Done = CompletableDeferred<Unit>()
        handshakeStages[MeshPacketCodec.HANDSHAKE_CONFIG_NONCE] = stage1Done
        val stage1Sent = runCatching {
            transport.writeToRadio(
                MeshPacketCodec.encodeWantConfigId(MeshPacketCodec.HANDSHAKE_CONFIG_NONCE)
            )
        }.getOrDefault(false)
        android.util.Log.i(tag, "[@$instance] stage1 want_config_id=${MeshPacketCodec.HANDSHAKE_CONFIG_NONCE} sent=$stage1Sent")
        val stage1Ok = runCatching {
            withTimeout(HANDSHAKE_STAGE1_TIMEOUT_MS) { stage1Done.await() }
            true
        }.getOrElse {
            handshakeStages.remove(MeshPacketCodec.HANDSHAKE_CONFIG_NONCE)
            android.util.Log.w(tag, "[@$instance] stage1 timeout (${HANDSHAKE_STAGE1_TIMEOUT_MS}ms): $it")
            false
        }
        android.util.Log.i(tag, "[@$instance] stage1 complete (stage1Ok=$stage1Ok)")

        // Emit UP as soon as Stage 1 is done (or has timed out). The
        // pair flow above us has its own LINK_UP timeout that's
        // tighter than Stage 1 + Stage 2 combined, and Stage 2 is
        // only a nodeDB pre-population — ongoing events come through
        // regardless. Marking UP here is the right "we're ready to
        // be useful" signal.
        //
        // ...but only if this handshake still belongs to the live
        // connection. If a Disconnected (or stop()) landed while we were
        // awaiting Stage 1, the epoch has moved on and driving UP now would
        // resurrect a dead link and start an orphaned heartbeat loop.
        if (connectionEpoch.get() != epoch) {
            android.util.Log.w(tag, "[@$instance] epoch $epoch superseded (now ${connectionEpoch.get()}) — aborting before UP")
            return
        }
        updateLinkState(LinkState.UP)

        // Kick off the periodic heartbeat once we're UP. Cancelled on
        // Disconnected / stop().
        startPeriodicHeartbeat()

        // Step 3: Stage 2 — full nodeDB. Fired in the background; we
        // don't block UP on it. If it times out or the write fails
        // (Android's GATT queue can be busy during the burst of
        // FromRadio reads from Stage 1) the link stays UP and we
        // just don't get the historical nodeDB until a fresh
        // broadcast lands.
        val stage2Done = CompletableDeferred<Unit>()
        handshakeStages[MeshPacketCodec.HANDSHAKE_NODE_INFO_NONCE] = stage2Done
        val stage2Sent = runCatching {
            transport.writeToRadio(
                MeshPacketCodec.encodeWantConfigId(MeshPacketCodec.HANDSHAKE_NODE_INFO_NONCE)
            )
        }.getOrDefault(false)
        android.util.Log.i(tag, "[@$instance] stage2 want_config_id=${MeshPacketCodec.HANDSHAKE_NODE_INFO_NONCE} sent=$stage2Sent")
        runCatching {
            withTimeout(HANDSHAKE_STAGE2_TIMEOUT_MS) { stage2Done.await() }
        }.onFailure {
            handshakeStages.remove(MeshPacketCodec.HANDSHAKE_NODE_INFO_NONCE)
            android.util.Log.w(tag, "[@$instance] stage2 timeout (${HANDSHAKE_STAGE2_TIMEOUT_MS}ms): $it")
        }
        android.util.Log.i(tag, "[@$instance] handshake fully complete")
    }

    /**
     * Send one heartbeat ToRadio packet. Returns true if writeToRadio
     * succeeded (means the OS queued the write and the peer ack'd).
     * Increments [heartbeatCounter] on success so T6 can observe.
     */
    private suspend fun sendHeartbeat(): Boolean {
        val ok = runCatching {
            transport.writeToRadio(MeshPacketCodec.encodeHeartbeat())
        }.getOrDefault(false)
        if (ok) heartbeatCounter.incrementAndGet()
        return ok
    }

    /**
     * Start the periodic-heartbeat loop. Sends one heartbeat every
     * [HEARTBEAT_INTERVAL_MS] while the link is UP. Cancel via
     * [stopPeriodicHeartbeat] (called on Disconnected). The first
     * heartbeat is the handshake's own pre-config wake — this loop
     * picks up from there at the regular interval.
     *
     * Why: the firmware can keep the BLE link nominally "connected"
     * even when the data plane has stalled. A regular phone-initiated
     * round-trip is the cheapest observability + liveness check we
     * have, and per the [[feedback-definition-of-done]] safety bar
     * we want to KNOW the link is dead within seconds, not minutes.
     */
    private fun startPeriodicHeartbeat() {
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
                if (linkState != LinkState.UP) break
                val ok = sendHeartbeat()
                android.util.Log.i(
                    "BleConnection.heartbeat",
                    "[@${System.identityHashCode(this@BleConnection)}] periodic heartbeat sent=$ok total=${heartbeatCounter.get()}"
                )
            }
        }
    }

    private fun stopPeriodicHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // Pending CompletableDeferred keyed by configId — completed when the
    // firmware replies with the matching ConfigComplete on FromRadio.
    private val handshakeStages = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

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
    internal suspend fun injectRawToRadio(toRadioBytes: ByteArray): Boolean {
        val result = runCatching { transport.writeToRadio(toRadioBytes) }.getOrDefault(false)
        android.util.Log.i("BleConnection", "[@${System.identityHashCode(this)}] injectRawToRadio: ${toRadioBytes.size}B -> $result (linkState=$linkState)")
        return result
    }

    /**
     * Push a synthetic [MeshEvent] directly into the connection's events
     * flow, bypassing the BLE wire and the firmware. For replay tests that
     * need virtual peer positions to land in Redux without round-tripping
     * through Meshtastic — the firmware overwrites `from=0` on phone-sent
     * packets and doesn't echo them back via FromRadio, so the "loopback
     * peer trick" doesn't work on real hardware.
     *
     * The real BleConnection wire (DUT GPS, ownership packets, future
     * SOS) still goes through the transport unmodified.
     */
    internal suspend fun injectMeshEventForTest(event: MeshEvent) {
        android.util.Log.i("BleConnection", "[@${System.identityHashCode(this)}] injectMeshEventForTest: ${event.javaClass.simpleName}")
        _events.emit(event)
    }

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

    companion object {
        // Stall-guard timeouts for the two-stage handshake. Match the
        // official Meshtastic-Android client (30 s / 60 s). If a stage
        // doesn't complete in time we proceed to LinkState.UP anyway —
        // the connection might still work for ongoing-only events even
        // if the bulk config dump stalled. Worst case the user sees
        // empty nodeDB until traffic comes in.
        private const val HANDSHAKE_STAGE1_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_STAGE2_TIMEOUT_MS = 60_000L

        // Periodic heartbeat (T6). 30s matches official Meshtastic
        // client cadence. Short enough to detect dead links quickly,
        // long enough to keep airtime + power negligible.
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
