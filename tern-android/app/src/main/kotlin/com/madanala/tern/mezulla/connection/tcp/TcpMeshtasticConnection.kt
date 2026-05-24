package com.madanala.tern.mezulla.connection.tcp

import com.madanala.tern.mezulla.connection.LinkState
import com.madanala.tern.mezulla.connection.MeshEvent
import com.madanala.tern.mezulla.connection.MeshtasticConnection
import com.madanala.tern.mezulla.connection.PeerIdentity
import com.madanala.tern.mezulla.connection.PeerPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

/**
 * A [MeshtasticConnection] that talks to a Meshtastic node over TCP.
 *
 * Used in two places:
 *
 *  1. Dev-time workflow. The Android emulator cannot pass through the
 *     host's Bluetooth radio. Instead, we run mezulla on the host's WiFi
 *     and point Tern at its TCP API (the board exposes it on port 4403
 *     when `network.wifi_enabled` is set). The emulator dials
 *     `10.0.2.2:4403` (host loopback) for the socat-bridge path; for the
 *     board-on-WiFi path, it dials the board's LAN address directly.
 *
 *  2. Real product. A pilot who keeps their LilyGo on home WiFi for
 *     charging can use the TCP path instead of pairing over BLE.
 *
 * What this class does:
 *
 *  - On [start], opens a TCP socket to `host:port` and emits
 *    `LinkStateChange(UP)`. Sends a `want_config_id` so the board replays
 *    its NodeDB to us (gives names for already-heard peers without
 *    waiting for fresh over-the-air announcements).
 *  - Spawns a coroutine that loops on socket reads, runs each byte chunk
 *    through [FrameDecoder], decodes each frame as a `FromRadio`, and
 *    emits typed [MeshEvent]s.
 *  - [sendOwnPosition] / [sendAlert] encode a `ToRadio` containing a
 *    `MeshPacket` with the right PortNum and write the framed bytes.
 *
 * Failure mode: any IO error during read or write becomes a
 * `LinkStateChange(DOWN)` event. We never throw out of [events] or out
 * of `send*`. This is the graceful-degradation rule applied to the
 * transport layer — see `project_tern_graceful_degradation`.
 *
 * Reconnect policy: none. After a DOWN, the calling code decides whether
 * to call [start] again. This is deliberate. The pairing-and-lifecycle
 * layer (WS5) owns reconnect semantics; the transport layer's job is to
 * report what happened.
 *
 * Threading: the read loop runs on [Dispatchers.IO] (a thread that can
 * block on socket reads). Writes are serialised through a mutex so two
 * outbound packets cannot interleave on the wire. The [events] flow is a
 * `MutableSharedFlow` so a single subscriber (the redux middleware) sees
 * ordering across peer events and link-state transitions.
 *
 * @param host hostname or IP of the Meshtastic TCP endpoint.
 * @param port TCP port; defaults to Meshtastic's standard 4403.
 * @param scope the parent [CoroutineScope]. The read loop is a child of
 *   this scope, so cancelling [scope] tears the connection down.
 * @param socketFactory injection seam for tests; production uses
 *   [RealTcpSocketFactory].
 * @param ownNodeId the node number Tern advertises in outbound MeshPackets
 *   as `from`. Defaults to 0 (broadcast / unknown) which is what the board
 *   substitutes its own node number for, anyway. Override only if you
 *   want the board to see a specific source id.
 */
class TcpMeshtasticConnection(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val scope: CoroutineScope,
    private val socketFactory: TcpSocketFactory = RealTcpSocketFactory(),
    private val ownNodeId: Long = 0L,
) : MeshtasticConnection {

    override val pairedBoardId: String? = null

    @Volatile
    override var linkState: LinkState = LinkState.DOWN
        private set

    private val _events = MutableSharedFlow<MeshEvent>(
        // Replay buffer of zero (matches the WS2.1 contract — late subscribers
        // see nothing) but a small extra buffer so the decode loop can emit
        // without suspending on a slow subscriber.
        replay = 0,
        extraBufferCapacity = 64,
    )
    private val writeLock = Mutex()

    @Volatile private var socket: TcpSocketHandle? = null
    private var readJob: Job? = null

    override fun events(): Flow<MeshEvent> = _events.asSharedFlow()

    /**
     * Open the TCP socket and start reading. Returns once the socket is
     * connected (or the connect attempt has failed and the DOWN event
     * has been emitted).
     *
     * Subsequent calls while already connected are a no-op. The intended
     * usage is one connection per instance: cancel [scope] to tear down
     * and construct a new one to retry.
     */
    suspend fun start() {
        if (socket != null) return

        val handle = try {
            withContext(Dispatchers.IO) { socketFactory.open(host, port) }
        } catch (e: IOException) {
            // Connect failed — surface as DOWN, don't throw.
            updateLinkState(LinkState.DOWN)
            return
        } catch (e: SecurityException) {
            // Android emulator INTERNET permission missing, for example.
            updateLinkState(LinkState.DOWN)
            return
        }

        socket = handle
        updateLinkState(LinkState.UP)

        // Ask the board to replay its NodeDB. Best-effort: if this fails
        // we still report UP and let the caller decide what to do next.
        runCatching { sendFramed(ProtoToRadio.encodeWantConfigId(WANT_CONFIG_ID)) }

        readJob = scope.launch(Dispatchers.IO) {
            runReadLoop(handle)
        }
        // When this coroutine is cancelled (scope cancellation, explicit
        // readJob.cancel()), close the handle to unblock any in-flight
        // blocking Socket.read(). Without this, the blocking read()
        // keeps the thread alive indefinitely and the coroutine never
        // actually finishes cancelling.
        readJob!!.invokeOnCompletion { handle.close() }
    }

    /**
     * Close the socket and stop the read loop. Idempotent. Emits a
     * `LinkStateChange(DOWN)` if the link was UP. Safe to call from any
     * dispatcher.
     */
    fun stop() {
        val handle = socket
        socket = null
        // Close the handle first to unblock a blocking read() on the IO
        // thread, then cancel the read job. The reverse order would leave
        // the read() blocked forever because coroutine cancellation does
        // not interrupt JVM-level blocking calls.
        handle?.close()
        readJob?.cancel()
        readJob = null
        if (linkState != LinkState.DOWN) {
            // Synchronous state update + best-effort event emission. We
            // can't suspend here because stop() is non-suspending; the
            // shared flow has buffer capacity to absorb the emit.
            linkState = LinkState.DOWN
            _events.tryEmit(MeshEvent.LinkStateChange(LinkState.DOWN))
        }
    }

    override suspend fun sendOwnPosition(position: PeerPosition.Fix) {
        if (linkState != LinkState.UP) return
        val packet = buildPositionPacket(position)
        val toRadio = ProtoToRadio.encodePacket(packet)
        try {
            sendFramed(toRadio)
        } catch (e: IOException) {
            // Write failed — link is gone. Surface DOWN and drop the call.
            handleIoFailure()
        }
    }

    override suspend fun sendAlert(
        lastKnownPosition: PeerPosition.Fix?,
        maxRetries: Int,
    ): MeshtasticConnection.SendResult {
        if (linkState != LinkState.UP) return MeshtasticConnection.SendResult.NoLink

        val packet = buildAlertPacket(lastKnownPosition)
        val toRadio = ProtoToRadio.encodePacket(packet)
        try {
            sendFramed(toRadio)
        } catch (e: IOException) {
            handleIoFailure()
            return MeshtasticConnection.SendResult.NoLink
        }

        // Note: a real ACK path correlates the outgoing packet's `id`
        // with a returning `routing` packet from the board. That belongs
        // in WS4.6 alongside the airtime measurement question flagged in
        // the design doc. For now: report Acked on successful write —
        // the local TCP socket accepting the bytes is a meaningful (if
        // partial) success signal, and is consistent with the stub's
        // happy-path behaviour. The pilot-facing distinction between
        // "wrote to socket" and "ACKed over the air" is owned by WS4.6.
        return MeshtasticConnection.SendResult.Acked
    }

    // --- internals --------------------------------------------------------

    private suspend fun runReadLoop(handle: TcpSocketHandle) {
        val decoder = FrameDecoder()
        val buf = ByteArray(READ_BUFFER_SIZE)
        try {
            while (true) {
                // runInterruptible bridges coroutine cancellation into a
                // thread interrupt, which causes a blocking read() to throw
                // InterruptedIOException / IOException. Without this, a
                // cancelled coroutine would leave the thread stuck in
                // read() indefinitely.
                val n = runInterruptible { handle.read(buf) }
                if (n < 0) break // clean EOF
                if (n == 0) continue
                decoder.feed(buf, n)
                for (frame in decoder.drainFrames()) {
                    dispatchFrame(frame)
                }
            }
        } catch (_: IOException) {
            // Socket closed or read failed — fall through to DOWN.
        } catch (_: InterruptedException) {
            // Thread interrupt during a blocking read; treat as a close.
        }
        // EOF or error → DOWN. Don't touch the socket field if stop()
        // has already nulled it.
        if (socket === handle) {
            socket = null
            updateLinkState(LinkState.DOWN)
            handle.close()
        }
    }

    private suspend fun dispatchFrame(frame: ByteArray) {
        val fromRadio = try {
            ProtoFromRadio.decode(frame)
        } catch (_: Exception) {
            // Malformed protobuf — skip the frame, keep reading. Don't
            // crash the read loop because the firmware sent us something
            // we couldn't parse.
            return
        }

        fromRadio.nodeInfo?.let { ni ->
            ni.user?.let { user ->
                val peer = buildPeerIdentity(ni.num.toLong() and 0xFFFFFFFFL, user)
                _events.emit(MeshEvent.PeerIdentityKnown(peer))
            }
            ni.position?.let { pos ->
                val peer = buildPeerIdentity(ni.num.toLong() and 0xFFFFFFFFL, ni.user)
                val fix = decodePositionFix(pos) ?: return@let
                _events.emit(MeshEvent.PeerPositionUpdate(peer, fix))
            }
        }

        fromRadio.packet?.let { packet ->
            val data = packet.decoded ?: return@let
            val peer = buildPeerIdentity(
                packet.from.toLong() and 0xFFFFFFFFL,
                user = null,
            )
            when (data.portnum) {
                MeshtasticProtos.PORT_POSITION -> {
                    val pos = runCatching { ProtoPosition.decode(data.payload) }.getOrNull()
                        ?: return@let
                    val fix = decodePositionFix(pos) ?: return@let
                    _events.emit(MeshEvent.PeerPositionUpdate(peer, fix))
                }
                MeshtasticProtos.PORT_NODEINFO -> {
                    val user = runCatching { ProtoUser.decode(data.payload) }.getOrNull()
                        ?: return@let
                    val withName = buildPeerIdentity(
                        packet.from.toLong() and 0xFFFFFFFFL,
                        user,
                    )
                    _events.emit(MeshEvent.PeerIdentityKnown(withName))
                }
                MeshtasticProtos.PORT_TELEMETRY -> {
                    val tel = runCatching { ProtoTelemetry.decode(data.payload) }.getOrNull()
                        ?: return@let
                    _events.emit(
                        MeshEvent.PeerTelemetry(
                            peer = peer,
                            batteryPercent = tel.deviceMetrics?.batteryPercent,
                            timestampSeconds = tel.timeSeconds.toLong() and 0xFFFFFFFFL,
                        ),
                    )
                }
                MeshtasticProtos.PORT_ALERT -> {
                    // Payload format is open (see design doc, open
                    // question 1). For now: surface the alert with the
                    // sender's identity; the last-known-position rider
                    // is null until WS2.2 freezes the payload schema.
                    _events.emit(
                        MeshEvent.PeerAlert(
                            peer = peer,
                            lastKnownPosition = null,
                            timestampSeconds = packet.rxTime.toLong() and 0xFFFFFFFFL,
                        ),
                    )
                }
                // Unrecognised port → ignore. Forward-compat for any
                // Meshtastic port Tern doesn't surface yet.
                else -> Unit
            }
        }
    }

    private suspend fun sendFramed(toRadioBytes: ByteArray) {
        val handle = socket ?: throw IOException("socket not open")
        val framed = MeshtasticFraming.encodeFrame(toRadioBytes)
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                handle.write(framed)
            }
        }
    }

    private suspend fun handleIoFailure() {
        val handle = socket
        socket = null
        readJob?.cancel()
        readJob = null
        handle?.close()
        updateLinkState(LinkState.DOWN)
    }

    private suspend fun updateLinkState(newState: LinkState) {
        if (linkState == newState) return
        linkState = newState
        _events.emit(MeshEvent.LinkStateChange(newState))
    }

    private fun buildPositionPacket(position: PeerPosition.Fix): ProtoMeshPacket {
        val proto = ProtoPosition(
            latitudeI = (position.latitudeDeg * MeshtasticProtos.POSITION_SCALE).roundToInt(),
            longitudeI = (position.longitudeDeg * MeshtasticProtos.POSITION_SCALE).roundToInt(),
            altitudeMeters = position.altitudeMeters,
            timeSeconds = position.timestampSeconds.toInt(),
            groundSpeedMps = position.groundSpeedMetersPerSecond?.roundToInt(),
            groundTrackDeg = position.groundTrackDegrees?.roundToInt(),
        )
        return ProtoMeshPacket(
            from = (ownNodeId and 0xFFFFFFFFL).toInt(),
            to = BROADCAST_ADDR,
            decoded = ProtoData(
                portnum = MeshtasticProtos.PORT_POSITION,
                payload = proto.encode(),
            ),
        )
    }

    private fun buildAlertPacket(lastKnown: PeerPosition.Fix?): ProtoMeshPacket {
        // Payload is open per the design doc; we send an empty payload
        // on ALERT_APP for now and rely on the sender's node number
        // (set by `from`) plus rxTime to identify who is in trouble.
        // When WS2.2 freezes the SOS payload schema, encode it here.
        val payload = lastKnown?.let {
            ProtoPosition(
                latitudeI = (it.latitudeDeg * MeshtasticProtos.POSITION_SCALE).roundToInt(),
                longitudeI = (it.longitudeDeg * MeshtasticProtos.POSITION_SCALE).roundToInt(),
                altitudeMeters = it.altitudeMeters,
                timeSeconds = it.timestampSeconds.toInt(),
            ).encode()
        } ?: ByteArray(0)
        return ProtoMeshPacket(
            from = (ownNodeId and 0xFFFFFFFFL).toInt(),
            to = BROADCAST_ADDR,
            decoded = ProtoData(
                portnum = MeshtasticProtos.PORT_ALERT,
                payload = payload,
            ),
            wantAck = true,
            priority = MeshtasticProtos.PRIORITY_ALERT,
        )
    }

    private fun buildPeerIdentity(
        nodeNumber: Long,
        user: ProtoUser?,
    ): PeerIdentity {
        // Clamp into unsigned-32 range; PeerIdentity validates that.
        val n = nodeNumber and 0xFFFFFFFFL
        return PeerIdentity.fromNodeNumber(
            nodeNumber = n,
            longName = user?.longName?.takeIf { it.isNotEmpty() },
            shortName = user?.shortName?.takeIf { it.isNotEmpty() },
        )
    }

    private fun decodePositionFix(pos: ProtoPosition): PeerPosition.Fix? {
        val lat = pos.latitudeI / MeshtasticProtos.POSITION_SCALE
        val lon = pos.longitudeI / MeshtasticProtos.POSITION_SCALE
        if (lat !in -90.0..90.0) return null
        if (lon !in -180.0..180.0) return null
        return PeerPosition.Fix(
            latitudeDeg = lat,
            longitudeDeg = lon,
            altitudeMeters = pos.altitudeMeters,
            groundSpeedMetersPerSecond = pos.groundSpeedMps?.toDouble(),
            groundTrackDegrees = pos.groundTrackDeg?.toDouble(),
            timestampSeconds = pos.timeSeconds.toLong() and 0xFFFFFFFFL,
        )
    }

    companion object {
        /** Meshtastic's standard TCP port. */
        const val DEFAULT_PORT = 4403

        /** Broadcast destination address in Meshtastic's addressing scheme. */
        const val BROADCAST_ADDR = -1 // 0xFFFFFFFF as signed int

        /**
         * Arbitrary positive integer; the board echoes a `config_complete_id`
         * with this value once the NodeDB replay finishes. We don't currently
         * read it back — surfacing replay-complete is a follow-up.
         */
        const val WANT_CONFIG_ID = 1

        private const val READ_BUFFER_SIZE = 1024
    }
}
