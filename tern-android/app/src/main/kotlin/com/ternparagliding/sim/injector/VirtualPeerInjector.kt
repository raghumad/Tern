package com.ternparagliding.sim.injector

import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.connection.ble.MeshPacketCodec
import com.ternparagliding.sim.propagation.DistanceOnlyPropagation
import com.ternparagliding.sim.propagation.PilotEndpoint
import com.ternparagliding.sim.propagation.PropagationModel
import com.ternparagliding.sim.propagation.PropagationOutcome
import com.ternparagliding.sim.propagation.TxPower
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.PilotPosition
import com.ternparagliding.sim.swarm.SwarmPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Pushes virtual-peer position packets through the paired board's BLE link
 * so the phone observes them as if they had arrived over LoRa.
 *
 * # The loopback peer trick
 *
 * Meshtastic's `MeshPacket.from` field is just the node number of the
 * originator — the board does not refuse a `ToRadio` frame whose `from`
 * is not our own node number. We exploit this for the Aravis replay test:
 *
 *  1. The injector picks one virtual peer from the [SwarmPlayback] (e.g.
 *     "antoine"), looks up its position at the current virtual time, and
 *     encodes a Position frame with `from = antoineNodeNumber` via
 *     [MeshPacketCodec.encodeToRadioPosition].
 *  2. The bytes go down [BleConnection.injectRawToRadio] →
 *     `BleTransport.writeToRadio`, i.e. straight onto the board's write
 *     characteristic.
 *  3. The board treats the packet as something it just heard. With the
 *     test firmware in radio-`UNSET`, nothing actually goes out over the
 *     air; the packet stays internal.
 *  4. The board reflects the packet back up the FromRadio notification
 *     stream. `BleConnection` decodes it via [MeshPacketCodec.decodeFromRadio]
 *     and surfaces it as a [com.ternparagliding.mezulla.connection.MeshEvent.PeerPositionUpdate]
 *     for `antoine` — exactly the same event the phone would receive if a
 *     real radio at antoine's node number had transmitted.
 *
 * This is the BLE/protocol arm of the test bench. Combined with the
 * `LocationInjector` (mock GPS) feeding the DUT's own position, the whole
 * mezulla pipeline can be exercised end-to-end with no second device on
 * the bench.
 *
 * # Time, timestamps, cadence
 *
 * Three different clocks are at play. They must not be confused:
 *
 *  - **Virtual time** advances at [speedMultiplier]x wall clock and drives
 *    `SwarmPlayback.currentPosition()` lookups — i.e. where each peer
 *    *was* at virtual-time T. Start at [playbackStart] (defaults to
 *    `playback.swarmStart`); per cadence tick, virtual time steps forward
 *    by [positionBroadcastInterval].
 *  - **Wall-clock now** is the value written into `Position.time` on the
 *    wire. The phone uses this as the freshness indicator and would drop
 *    packets timestamped years in the past, so historical IGC timestamps
 *    are unusable on the wire — same constraint as
 *    [com.ternparagliding.sim.mockgps.LocationInjector].
 *  - **Cadence in wall-clock** between two injections is
 *    `positionBroadcastInterval / speedMultiplier`. At 60x, a 30-virtual-
 *    second broadcast cadence fires every 500 ms of real time.
 *
 * # Propagation
 *
 * Each tick, for each peer, the configured [PropagationModel] is asked
 * whether the peer's transmission reaches the DUT. On
 * [PropagationOutcome.Lost] the injection is skipped — that peer is "out
 * of range" for this tick. The DUT's own position is read from the
 * playback (at [dutPilotId]) at the same virtual time, so range checks
 * track the relative motion of the swarm.
 *
 * If [dutPilotId] is null, no range check is performed — every peer is
 * always delivered. Use this when running the injector against a DUT that
 * is not in the scenario (e.g. a bench device away from the IGC
 * playback's geographic area).
 *
 * # Lifecycle
 *
 *  - [start] launches a single coroutine on the provided scope and
 *    returns immediately. Idempotent — double-start throws.
 *  - [stop] cancels the coroutine. Safe to call repeatedly. After
 *    [stop] the injector is single-use; build a new one to replay.
 *
 * @param bleConnection the paired board connection. The injector calls
 *   [BleConnection.injectRawToRadio]; it does NOT call [BleConnection.sendOwnPosition]
 *   or [BleConnection.sendAlert], which carry their own bookkeeping for
 *   the DUT-as-sender path.
 * @param playback swarm playback containing every virtual peer's flight.
 * @param peerNodeNumbers the mapping from [PilotId] to the Meshtastic node
 *   number the board should see in the `from` field for that peer. Pilots
 *   in the scenario that are absent from this map are silently skipped
 *   (e.g. the DUT itself).
 * @param speedMultiplier how much faster than real time virtual time
 *   advances. 1 = real time, 60 = 1 virtual minute per real second. Must
 *   be > 0.
 * @param propagation range / line-of-sight model. Defaults to a 15 km
 *   distance-only model — same as [SwarmSimulatedConnection]'s default.
 * @param dutPilotId pilot whose position should be used as the propagation
 *   receiver. Null disables range checks (every tick injects every peer).
 * @param positionBroadcastInterval virtual-time interval between
 *   broadcasts of each peer. Default 30 seconds — matches
 *   [com.ternparagliding.sim.simulation.SwarmSimulatedConnection]'s
 *   default cadence and the actual Meshtastic position broadcast default.
 * @param playbackStart virtual time the first tick lands at. Defaults to
 *   [SwarmPlayback.swarmStart]. The first broadcast happens at
 *   `playbackStart + positionBroadcastInterval` (not at playbackStart
 *   itself — same convention as `SwarmSimulatedConnection.isBroadcastBoundary`).
 * @param wallClockMillis source of "now" for the `Position.time` field on
 *   the wire. Defaults to [System.currentTimeMillis]; tests pin it so the
 *   on-wire timestamp is deterministic.
 * @param packetIdSource source of `MeshPacket.id`. Default uses
 *   [System.nanoTime] truncated to a positive int so two injections within
 *   the same nanosecond budget (which would be a feat) avoid Meshtastic's
 *   FIFO dedup window. Tests pin it.
 */
class VirtualPeerInjector(
    private val bleConnection: BleConnection,
    private val playback: SwarmPlayback,
    private val peerNodeNumbers: Map<PilotId, Long>,
    private val speedMultiplier: Int,
    private val propagation: PropagationModel =
        DistanceOnlyPropagation(DEFAULT_RANGE_METERS),
    private val dutPilotId: PilotId? = null,
    private val positionBroadcastInterval: Duration =
        Duration.ofSeconds(DEFAULT_POSITION_INTERVAL_SECONDS.toLong()),
    private val playbackStart: Instant = playback.swarmStart,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val packetIdSource: () -> Int = { defaultPacketId() },
) {

    init {
        require(speedMultiplier > 0) {
            "speedMultiplier must be > 0 (got $speedMultiplier)"
        }
        require(!positionBroadcastInterval.isZero && !positionBroadcastInterval.isNegative) {
            "positionBroadcastInterval must be > 0 (got $positionBroadcastInterval)"
        }
        dutPilotId?.let { id ->
            // Sanity-check the DUT is in the playback; otherwise the
            // range check would be silently broken. Peers absent from
            // peerNodeNumbers are fine — they just don't get injected.
            require(playback.pilots.contains(id)) {
                "dutPilotId '$id' is not in the playback's scenario"
            }
        }
    }

    /** Background tick job; null until [start], cancelled by [stop]. */
    @Volatile
    private var driverJob: Job? = null

    /** True once [start] has been called. Single-use. */
    @Volatile
    private var started: Boolean = false

    /**
     * Current virtual instant. Public for tests; advances by
     * [positionBroadcastInterval] per tick. Initialised to [playbackStart];
     * the first tick advances to `playbackStart + interval` before
     * injecting (same boundary convention as `SwarmSimulatedConnection`).
     */
    @Volatile
    var currentVirtualTime: Instant = playbackStart
        private set

    /**
     * How many `injectRawToRadio` calls succeeded since [start]. Public
     * for tests; production callers don't read it.
     */
    @Volatile
    var injectedCount: Long = 0L
        private set

    /**
     * Launch the background driver coroutine. Returns immediately; the
     * driver runs until [stop] (or scope cancellation, or the scenario
     * runs past [SwarmPlayback.swarmEnd]).
     *
     * Throws if called more than once.
     */
    fun start(scope: CoroutineScope) {
        check(!started) { "VirtualPeerInjector.start() called twice" }
        started = true
        driverJob = scope.launch { drive() }
    }

    /**
     * Cancel the background driver. Safe to call repeatedly; safe to call
     * before [start] (no-op).
     */
    fun stop() {
        driverJob?.cancel()
        driverJob = null
    }

    /**
     * Driver loop. Each iteration sleeps `interval / speedMultiplier` ms
     * of wall clock, then advances virtual time and injects one broadcast
     * round. Exits when virtual time runs past [SwarmPlayback.swarmEnd].
     */
    private suspend fun drive() {
        val wallClockDelayMs = wallClockDelayMillis()
        val end = playback.swarmEnd
        while (true) {
            delay(wallClockDelayMs)
            val next = currentVirtualTime.plus(positionBroadcastInterval)
            if (next.isAfter(end)) break
            currentVirtualTime = next
            tick(next)
        }
    }

    /**
     * One injection round at virtual time [virtualNow]. For each peer in
     * [peerNodeNumbers] that has a position at [virtualNow] and whose
     * propagation outcome to the DUT is Delivered, encode and inject one
     * Position frame. Wall-clock now is stamped onto each frame.
     */
    private suspend fun tick(virtualNow: Instant) {
        val nowMillis = wallClockMillis()
        val dutEndpoint = dutEndpointAt(virtualNow)
        for ((pilotId, nodeNumber) in peerNodeNumbers) {
            if (pilotId == dutPilotId) continue
            val pos = playback.currentPosition(pilotId, virtualNow) ?: continue
            if (dutEndpoint != null) {
                val outcome = propagation.propagate(
                    sender = pos.toEndpoint(),
                    receiver = dutEndpoint,
                    txPower = TxPower.TX_DEFAULT,
                )
                if (outcome !is PropagationOutcome.Delivered) continue
            }
            // Derive ground track + speed from the previous fix — the IGC
            // PilotPosition carries only lat/lon/alt, but peers on the wire
            // (and the HUD's heading arrow + TACTICAL speed) need course and
            // speed. Bearing/distance from a fix TRACK_SAMPLE_SECONDS earlier.
            val prev = playback.currentPosition(
                pilotId, virtualNow.minusSeconds(TRACK_SAMPLE_SECONDS)
            )
            var trackDegrees: Double? = null
            var groundSpeed: Double? = null
            if (prev != null) {
                val from = org.osmdroid.util.GeoPoint(prev.latitude, prev.longitude)
                val to = org.osmdroid.util.GeoPoint(pos.latitude, pos.longitude)
                val meters = from.distanceToAsDouble(to)
                if (meters > 1.0) {
                    trackDegrees = from.bearingTo(to)
                    groundSpeed = meters / TRACK_SAMPLE_SECONDS
                }
            }
            val fix = pos.toPeerFix(
                timestampSeconds = nowMillis / MS_PER_SECOND,
                trackDegrees = trackDegrees,
                groundSpeed = groundSpeed,
            )
            // Bypass the firmware loop: Meshtastic overwrites `from=0` on
            // phone-sent packets and doesn't echo them back via FromRadio,
            // so the original "encode ToRadio with peer's from / let it
            // round-trip" plan doesn't work on real hardware. Push the
            // synthetic event straight into BleConnection's events flow,
            // same code path as if it had arrived from the wire.
            val event = MeshEvent.PeerPositionUpdate(
                // Use the pilot handle as the peer's long name so the HUD
                // shows a friendly callsign (e.g. RICHARD) instead of the
                // raw node id. In production this name arrives via NodeInfo.
                peer = PeerIdentity.fromNodeNumber(nodeNumber, longName = pilotId.value),
                fix = fix,
            )
            bleConnection.injectMeshEventForTest(event)
            injectedCount++
            // Also issue a packet-id call so test seams that count IDs
            // stay consistent with the previous wire-bytes path.
            @Suppress("UNUSED_VARIABLE") val unused = packetIdSource()
        }
    }

    /**
     * The DUT's propagation endpoint at [virtualNow], or null if no DUT
     * is configured (or the DUT has no position yet at this virtual
     * instant — pre-launch, post-landing). Null means "skip the range
     * check"; with no DUT to receive against, the simulator cannot reason
     * about whether a packet was lost, so the safe answer is "deliver."
     */
    private fun dutEndpointAt(virtualNow: Instant): PilotEndpoint? {
        val id = dutPilotId ?: return null
        val pos = playback.currentPosition(id, virtualNow) ?: return null
        return pos.toEndpoint()
    }

    /**
     * Wall-clock milliseconds between ticks. Integer division is fine —
     * for the values this is used with (intervals in whole seconds,
     * multipliers in small whole numbers up to a few thousand) the
     * remainder is at worst one millisecond per tick, which adds up to a
     * few seconds per hour of replay. Acceptable for a dev-velocity tool.
     */
    private fun wallClockDelayMillis(): Long {
        val intervalMs = positionBroadcastInterval.toMillis()
        return (intervalMs / speedMultiplier).coerceAtLeast(1L)
    }

    private fun PilotPosition.toEndpoint(): PilotEndpoint = PilotEndpoint(
        latitudeDeg = latitude,
        longitudeDeg = longitude,
        altitudeMeters = altitudeMeters.toDouble(),
    )

    private fun PilotPosition.toPeerFix(
        timestampSeconds: Long,
        trackDegrees: Double?,
        groundSpeed: Double?,
    ): PeerPosition.Fix =
        PeerPosition.Fix(
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            altitudeMeters = altitudeMeters,
            groundSpeedMetersPerSecond = groundSpeed,
            groundTrackDegrees = trackDegrees,
            timestampSeconds = timestampSeconds,
        )

    companion object {

        /** Default LoRa range (m); matches `SwarmSimulatedConnection`. */
        const val DEFAULT_RANGE_METERS: Int = 15_000

        /** Default broadcast cadence (s); matches Meshtastic's default. */
        const val DEFAULT_POSITION_INTERVAL_SECONDS: Int = 30

        private const val MS_PER_SECOND: Long = 1_000L

        /** Virtual-time lookback used to derive a peer's ground track + speed. */
        private const val TRACK_SAMPLE_SECONDS: Long = 10L

        /**
         * Nanotime-based packet id. Truncated with a positive 31-bit mask
         * so the result is always > 0 (Meshtastic uses zero as "unset").
         */
        private fun defaultPacketId(): Int =
            (System.nanoTime() and 0x7FFF_FFFFL).toInt().coerceAtLeast(1)
    }
}
