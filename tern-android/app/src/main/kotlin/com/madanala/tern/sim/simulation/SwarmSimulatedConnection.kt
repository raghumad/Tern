package com.madanala.tern.sim.simulation

import com.madanala.tern.mezulla.connection.LinkState
import com.madanala.tern.mezulla.connection.MeshEvent
import com.madanala.tern.mezulla.connection.MeshtasticConnection
import com.madanala.tern.mezulla.connection.PeerIdentity
import com.madanala.tern.mezulla.connection.PeerPosition
import com.madanala.tern.sim.propagation.DistanceOnlyPropagation
import com.madanala.tern.sim.propagation.PilotEndpoint
import com.madanala.tern.sim.propagation.PropagationModel
import com.madanala.tern.sim.propagation.PropagationOutcome
import com.madanala.tern.sim.propagation.TxPower
import com.madanala.tern.sim.swarm.PilotId
import com.madanala.tern.sim.swarm.PilotPosition
import com.madanala.tern.sim.swarm.Scenario
import com.madanala.tern.sim.swarm.ScenarioPilot
import com.madanala.tern.sim.swarm.SwarmPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A [MeshtasticConnection] that turns recorded IGC flights into a
 * Tern-shaped event stream (WS2.2).
 *
 * The bridge wires the WS1.2 [SwarmPlayback] (multi-pilot IGC playback)
 * and the WS1.3 [PropagationModel] (range check) into the WS2.1
 * [MeshtasticConnection] interface so the WS2.4 [com.madanala.tern.mezulla.redux.PeerMiddleware]
 * can consume IGC-derived events with no special knowledge of the
 * simulator.
 *
 * What it does, in one paragraph:
 *  - It plays a [Scenario] forward in virtual time at [playbackTickSeconds]
 *    granularity, starting from the earliest first-fix in the scenario.
 *  - At every tick whose offset from start is a multiple of
 *    [positionBroadcastIntervalSeconds], each non-DUT pilot who has a
 *    valid position at that instant broadcasts it.
 *  - For each such broadcast, the configured [propagation] model is asked
 *    whether the DUT receives it. On [PropagationOutcome.Delivered], a
 *    [MeshEvent.PeerPositionUpdate] is emitted (preceded the first time by
 *    a [MeshEvent.PeerIdentityKnown] for that peer).
 *  - On [PropagationOutcome.Lost], nothing is emitted: the simulator is
 *    silent about packets that did not reach us, just as the radio is.
 *
 * Happy path only, per
 * [project-tern-simulator-purpose](../../../../../../../../docs/backlog/current-focus.md).
 * No mesh repeats, no collisions, no smart broadcast, no duty cycle, no
 * GPS jitter, no NodeInfo / telemetry / alert emission, no BLE transport
 * quirks. Those are not in scope for the WS2.2 cut. Hooks exist (a richer
 * [PropagationModel] can be plugged in, position cadence is a parameter)
 * but no chaos is built.
 *
 * # Drive modes
 *
 *  - **Scripted (tests):** call [advanceTo] with the virtual instant the
 *    test wants to land on. The function returns once every event up to
 *    and including that instant has been emitted on the [events] flow.
 *    Tests subscribe before calling [advanceTo].
 *  - **Real time (manual smoke):** call [start] on a coroutine scope; a
 *    background coroutine drives ticks at wall-clock pace
 *    ([playbackTickSeconds] seconds of real time per tick). The coroutine
 *    can be cancelled via [stop] or by cancelling its parent scope.
 *
 * # DUT silence
 *
 * The DUT does not see its own broadcasts. Real Meshtastic broadcasts
 * leave the board over the air and do not come back as receive events.
 * [sendOwnPosition] therefore appends to [sentPositions] (so tests can
 * assert what the DUT broadcast) but does **not** feed the local event
 * flow.
 *
 * # Threading
 *
 * Events flow through a [MutableSharedFlow] with `extraBufferCapacity = 64`
 * — same shape as [com.madanala.tern.mezulla.connection.StubMeshtasticConnection].
 * A test that needs to observe events must subscribe to [events] before
 * calling [advanceTo]; the flow does not replay to late subscribers. With
 * an `UnconfinedTestDispatcher`-rooted collector scope the subscription
 * is in place by the time [advanceTo] runs (see the matching pattern in
 * `PeerMiddlewareTest`).
 *
 * @param scenario the multi-pilot scenario to play back.
 * @param dutPilotId which pilot is the device-under-test ("us"). That
 *   pilot's broadcasts are not surfaced on the local event flow.
 * @param propagation range check applied to every non-DUT broadcast.
 *   Defaults to [DistanceOnlyPropagation] at 15 km — the value the
 *   convergence test's BDD vocabulary uses.
 * @param positionBroadcastIntervalSeconds how often (in virtual seconds)
 *   each non-DUT pilot broadcasts. Fixed periodic cadence; smart-broadcast
 *   is deliberately out of scope. Default 30 s.
 * @param playbackTickSeconds virtual seconds advanced per simulator tick.
 *   The broadcast cadence must be evenly divisible by the tick interval
 *   so cadence boundaries can be hit precisely. Default 1 s.
 * @param clock injected to keep real-time mode testable. Defaults to
 *   [Clock.systemUTC]; tests do not need this — they use [advanceTo].
 * @param playbackFlightsOverride optional pre-parsed flights for tests
 *   that want to skip the classpath IGC load.
 */
class SwarmSimulatedConnection(
    val scenario: Scenario,
    val dutPilotId: PilotId,
    val propagation: PropagationModel = DistanceOnlyPropagation(DEFAULT_RANGE_METERS),
    val positionBroadcastIntervalSeconds: Int = DEFAULT_POSITION_INTERVAL_SECONDS,
    val playbackTickSeconds: Int = DEFAULT_TICK_SECONDS,
    private val clock: Clock = Clock.systemUTC(),
    playbackFlightsOverride: Map<PilotId, com.madanala.tern.sim.igc.IgcFlight>? = null,
) : MeshtasticConnection {

    init {
        require(scenario.pilot(dutPilotId) != null) {
            "DUT pilot '$dutPilotId' is not in scenario '${scenario.name}'"
        }
        require(positionBroadcastIntervalSeconds > 0) {
            "positionBroadcastIntervalSeconds must be > 0 (got $positionBroadcastIntervalSeconds)"
        }
        require(playbackTickSeconds > 0) {
            "playbackTickSeconds must be > 0 (got $playbackTickSeconds)"
        }
        require(positionBroadcastIntervalSeconds % playbackTickSeconds == 0) {
            "positionBroadcastIntervalSeconds ($positionBroadcastIntervalSeconds) must be a " +
                "whole multiple of playbackTickSeconds ($playbackTickSeconds) so the cadence " +
                "boundary lands on a tick"
        }
    }

    private val playback: SwarmPlayback = SwarmPlayback(
        scenario = scenario,
        tickInterval = Duration.ofSeconds(playbackTickSeconds.toLong()),
        flightsOverride = playbackFlightsOverride,
    )

    private val tickStep: Duration = Duration.ofSeconds(playbackTickSeconds.toLong())

    /**
     * Stable identity for every non-DUT pilot. Built once at construction
     * so the same [PeerIdentity] surfaces on every event for the same
     * pilot — that keeps the redux key stable across the run.
     *
     * `nodeNumber` is a deterministic 32-bit hash of the pilot id string.
     * Two pilots with different ids will overwhelmingly produce different
     * numbers; collisions would only show up if a scenario file contained
     * pathologically-chosen ids and are accepted as a development-tool
     * limitation. The check below would catch one in practice.
     */
    private val peerIdentities: Map<PilotId, PeerIdentity> = buildPeerIdentities()

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = EVENT_BUFFER)
    private val _sentPositions = mutableListOf<PeerPosition.Fix>()
    private val _sentAlerts = mutableListOf<PeerPosition.Fix?>()

    /**
     * Peers we have already announced via [MeshEvent.PeerIdentityKnown].
     * The set is consulted once per delivered broadcast to make
     * "PeerIdentityKnown is emitted at most once per peer per session"
     * a hard guarantee.
     */
    private val announcedPeers: MutableSet<PilotId> = mutableSetOf()

    /**
     * Virtual clock. Holds the highest instant we have already ticked at;
     * initialised to one tick before [SwarmPlayback.swarmStart] so the
     * very first [tick] call lands exactly on `swarmStart`. Only advanced
     * inside [advanceTo] / [driveRealtime].
     */
    @Volatile
    private var virtualClock: Instant = playback.swarmStart.minus(tickStep)

    /** True once [start] has been called and the connection is live. */
    @Volatile
    private var started: Boolean = false

    /** True once the scenario has been played to completion. */
    @Volatile
    private var endingEmitted: Boolean = false

    /** Background coroutine driving real-time mode; null in scripted mode. */
    @Volatile
    private var realtimeJob: Job? = null

    /** [MeshtasticConnection.pairedBoardId] — fabricated stable id for the simulator. */
    override val pairedBoardId: String = "!sim-" + scenario.name.take(SIM_ID_PREFIX_LEN)

    @Volatile
    override var linkState: LinkState = LinkState.NEVER_PAIRED
        private set

    /** What [sendOwnPosition] was called with, in call order. Tests assert on this. */
    val sentPositions: List<PeerPosition.Fix> get() = _sentPositions.toList()

    /** What [sendAlert] was called with, in call order. Tests assert on this. */
    val sentAlerts: List<PeerPosition.Fix?> get() = _sentAlerts.toList()

    /** Earliest virtual instant the scenario starts at (inclusive). */
    val scenarioStart: Instant get() = playback.swarmStart

    /** Latest virtual instant the scenario ends at (inclusive). */
    val scenarioEnd: Instant get() = playback.swarmEnd

    /**
     * Highest virtual instant that has been ticked. Public for tests;
     * not for production use. Before the first tick this returns
     * `scenarioStart - playbackTickSeconds` (i.e. "no ticks have happened
     * yet").
     */
    val currentVirtualTime: Instant get() = virtualClock

    override fun events(): Flow<MeshEvent> = _events.asSharedFlow()

    /**
     * Start the simulator.
     *
     * Emits the initial [MeshEvent.LinkStateChange] to [LinkState.UP]. In
     * scripted (test) mode this is all `start` does; the test then calls
     * [advanceTo] to drive virtual time forward.
     *
     * In real-time mode, pass a non-null [scope]; a background coroutine
     * is launched on that scope which advances virtual time by
     * [playbackTickSeconds] every `playbackTickSeconds` seconds of wall
     * clock until the scenario ends or [stop] is called.
     *
     * Idempotent in the sense that double-start throws — a started
     * simulator is single-use; build a new instance for a new run.
     */
    suspend fun start(scope: CoroutineScope? = null) {
        check(!started) { "SwarmSimulatedConnection.start() called twice" }
        started = true
        linkState = LinkState.UP
        _events.emit(MeshEvent.LinkStateChange(LinkState.UP))

        if (scope != null) {
            realtimeJob = scope.launch { driveRealtime() }
        }
    }

    /**
     * Advance virtual time to [targetTime], emitting every event that
     * falls in `(currentVirtualTime, targetTime]`. Caller is responsible
     * for having called [start] first.
     *
     * Idempotent in the trivial sense: if [targetTime] is at or before
     * [currentVirtualTime], this is a no-op. Going backwards is not
     * supported and not allowed.
     *
     * If [targetTime] is at or past [scenarioEnd], the scenario plays
     * through to its end, emits the closing [MeshEvent.LinkStateChange]
     * to [LinkState.DOWN], and any further [advanceTo] call is a no-op.
     */
    suspend fun advanceTo(targetTime: Instant) {
        check(started) { "advanceTo called before start()" }
        if (endingEmitted) return
        require(!targetTime.isBefore(virtualClock)) {
            "advanceTo($targetTime) goes backwards; current virtual time is $virtualClock"
        }

        val end = playback.swarmEnd
        val clamped = if (targetTime.isBefore(end)) targetTime else end

        // Walk one tick at a time. virtualClock holds "highest instant
        // ticked so far"; the next tick is at virtualClock + tickStep.
        // Stop when the next tick would overshoot clamped.
        while (true) {
            val next = virtualClock.plus(tickStep)
            if (next.isAfter(clamped)) break
            virtualClock = next
            tick()
        }

        if (!targetTime.isBefore(end)) {
            emitEnd()
        }
    }

    /**
     * Cancel the real-time driver coroutine, if one is running. Does not
     * affect the event flow or the virtual clock; if the simulator was
     * driven by [advanceTo] only, this is a no-op.
     */
    fun stop() {
        realtimeJob?.cancel()
        realtimeJob = null
    }

    override suspend fun sendOwnPosition(position: PeerPosition.Fix) {
        if (linkState != LinkState.UP) return
        _sentPositions.add(position)
        // Deliberately not looped back to the local event flow. Real
        // Meshtastic broadcasts leave the board over the air and the
        // sender does not observe them as receive events.
    }

    override suspend fun sendAlert(
        lastKnownPosition: PeerPosition.Fix?,
        maxRetries: Int,
    ): MeshtasticConnection.SendResult {
        if (linkState != LinkState.UP) return MeshtasticConnection.SendResult.NoLink
        _sentAlerts.add(lastKnownPosition)
        // Happy path: assume the SOS made it. Ack-loss / NoAck simulation
        // is chaos modelling and is out of scope for WS2.2.
        return MeshtasticConnection.SendResult.Acked
    }

    // -- internals -------------------------------------------------------

    /**
     * Real-time driver loop. Advances virtual time by one tick every
     * `playbackTickSeconds` seconds of wall clock until the scenario ends
     * or the coroutine is cancelled. Cooperative cancellation: [delay]
     * propagates `CancellationException` so cancelling the parent job
     * exits the loop without further work.
     */
    private suspend fun driveRealtime() {
        val realtimePerTickMs = playbackTickSeconds.toLong() * MS_PER_SECOND
        while (!endingEmitted) {
            val next = virtualClock.plus(tickStep)
            if (next.isAfter(playback.swarmEnd)) {
                emitEnd()
                break
            }
            virtualClock = next
            tick()
            delay(realtimePerTickMs)
        }
    }

    /**
     * Emit every event due at [virtualClock]:
     *  - Each non-DUT pilot whose cadence boundary lines up with this
     *    instant, who has a position now, and whose propagation outcome
     *    to the DUT is [PropagationOutcome.Delivered], gets a
     *    [MeshEvent.PeerPositionUpdate]. First-time deliveries are
     *    preceded by [MeshEvent.PeerIdentityKnown].
     */
    private suspend fun tick() {
        if (endingEmitted) return

        val now = virtualClock
        if (!isBroadcastBoundary(now)) return

        val dutPosition = playback.currentPosition(dutPilotId, now) ?: return
        val dutEndpoint = dutPosition.toEndpoint()

        for (pilotId in playback.pilots) {
            if (pilotId == dutPilotId) continue
            val senderPos = playback.currentPosition(pilotId, now) ?: continue
            val outcome = propagation.propagate(
                sender = senderPos.toEndpoint(),
                receiver = dutEndpoint,
                txPower = TxPower.TX_DEFAULT,
            )
            if (outcome !is PropagationOutcome.Delivered) continue

            val identity = peerIdentities.getValue(pilotId)
            if (announcedPeers.add(pilotId)) {
                _events.emit(MeshEvent.PeerIdentityKnown(identity))
            }
            _events.emit(MeshEvent.PeerPositionUpdate(identity, senderPos.toPeerFix()))
        }
    }

    /**
     * True when [instant] is a broadcast boundary — i.e. the seconds
     * elapsed since [SwarmPlayback.swarmStart] is a positive whole
     * multiple of [positionBroadcastIntervalSeconds]. Sub-second drift
     * is impossible because every advance step is a whole multiple of
     * [playbackTickSeconds] and [playbackTickSeconds] divides
     * [positionBroadcastIntervalSeconds].
     *
     * The first broadcast happens at `swarmStart + interval`, not at
     * `swarmStart` itself. The exact instant the link came up is not a
     * broadcast moment in any physical sense; making it one would
     * conflate "the simulator started" with "every peer transmitted."
     * Skipping the t = 0 boundary keeps the cadence assertion exact:
     * a 90-second window at a 30-second cadence yields 3 broadcasts
     * per in-range peer, not 4.
     */
    private fun isBroadcastBoundary(instant: Instant): Boolean {
        val elapsed = Duration.between(playback.swarmStart, instant).seconds
        return elapsed > 0L && elapsed % positionBroadcastIntervalSeconds == 0L
    }

    private suspend fun emitEnd() {
        if (endingEmitted) return
        endingEmitted = true
        linkState = LinkState.DOWN
        _events.emit(MeshEvent.LinkStateChange(LinkState.DOWN))
    }

    private fun buildPeerIdentities(): Map<PilotId, PeerIdentity> {
        val out = LinkedHashMap<PilotId, PeerIdentity>(scenario.pilots.size)
        val claimedNumbers = HashSet<Long>(scenario.pilots.size)
        for (pilot in scenario.pilots) {
            if (pilot.id == dutPilotId) continue
            val number = stableNodeNumber(pilot)
            check(claimedNumbers.add(number)) {
                "node-number collision in scenario '${scenario.name}': pilot " +
                    "'${pilot.id}' hashed to $number which is already claimed. " +
                    "Pick a different pilot id."
            }
            val displayName = pilot.displayName.takeIf { it.isNotBlank() } ?: pilot.id.value
            out[pilot.id] = PeerIdentity.fromNodeNumber(
                nodeNumber = number,
                longName = displayName,
                shortName = displayName.take(SHORT_NAME_LEN),
            )
        }
        return out
    }

    /**
     * 32-bit hash of the pilot id string. The Kotlin String.hashCode is
     * a 32-bit signed int; masking with 0xFFFFFFFF gives the matching
     * unsigned 32-bit node number Meshtastic uses.
     */
    private fun stableNodeNumber(pilot: ScenarioPilot): Long =
        pilot.id.value.hashCode().toLong() and 0xFFFFFFFFL

    private fun PilotPosition.toEndpoint(): PilotEndpoint = PilotEndpoint(
        latitudeDeg = latitude,
        longitudeDeg = longitude,
        altitudeMeters = altitudeMeters.toDouble(),
    )

    private fun PilotPosition.toPeerFix(): PeerPosition.Fix = PeerPosition.Fix(
        latitudeDeg = latitude,
        longitudeDeg = longitude,
        altitudeMeters = altitudeMeters,
        // The simulator does not derive speed / track from IGC fixes in
        // this cut; both are first-class nullable in PeerPosition.Fix.
        // Adding them is a follow-up if the convergence test needs them.
        groundSpeedMetersPerSecond = null,
        groundTrackDegrees = null,
        timestampSeconds = sourceFixTimestamp.epochSecond,
    )

    companion object {
        /** Default LoRa range used by the convergence test vocabulary. */
        const val DEFAULT_RANGE_METERS: Int = 15_000

        /** Default position-broadcast cadence in virtual seconds. */
        const val DEFAULT_POSITION_INTERVAL_SECONDS: Int = 30

        /** Default virtual-clock step per tick. */
        const val DEFAULT_TICK_SECONDS: Int = 1

        /**
         * Buffer capacity on the event SharedFlow. Mirrors
         * [com.madanala.tern.mezulla.connection.StubMeshtasticConnection]
         * so an `advanceTo` burst does not suspend on backpressure under
         * normal test workloads.
         */
        private const val EVENT_BUFFER: Int = 64

        /**
         * Max characters of the scenario name folded into [pairedBoardId].
         * 8 keeps the fabricated id short enough to be readable in logs
         * while still being unique across the small number of scenarios
         * we have.
         */
        private const val SIM_ID_PREFIX_LEN: Int = 8

        /** Max short-name length for the synthesised [PeerIdentity]. */
        private const val SHORT_NAME_LEN: Int = 4

        private const val MS_PER_SECOND: Long = 1_000L
    }
}
