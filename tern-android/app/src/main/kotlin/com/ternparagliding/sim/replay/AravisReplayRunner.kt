package com.ternparagliding.sim.replay

import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.sim.injector.VirtualPeerInjector
import com.ternparagliding.sim.mockgps.AndroidLocationInjector
import com.ternparagliding.sim.mockgps.IgcMockLocationProvider
import com.ternparagliding.sim.propagation.PropagationModel
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.SwarmPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Orchestrates a single end-to-end Aravis-style swarm replay against a
 * real paired Mezulla board.
 *
 * # What it composes
 *
 *  - [IgcMockLocationProvider] — replays the DUT pilot's IGC track into
 *    Android's mock-location provider so Tern's location-aware features
 *    react as if the phone were really flying.
 *  - [VirtualPeerInjector] — encodes the other pilots' positions as
 *    Meshtastic Position frames and pumps them through the paired
 *    [BleConnection] so the phone observes them as if they had arrived
 *    over LoRa.
 *
 * Both child engines share the same [SwarmPlayback] and [speedMultiplier],
 * so virtual time stays coherent across the DUT GPS stream and the peer
 * radio stream.
 *
 * # Why this lives in production code
 *
 *  - [VirtualPeerInjector] reaches into [BleConnection.injectRawToRadio],
 *    which is `internal` to the mezulla module. The runner therefore must
 *    live alongside it, not in `androidTest/`.
 *  - The runner is harmless if never started — its constructor does no I/O
 *    and the production app does not instantiate it. It exists for the
 *    Aravis replay test (and any future on-device "play a known swarm"
 *    demo).
 *
 * # Lifecycle
 *
 *  - [start] launches the mock GPS provider and the peer injector on the
 *    supplied scope and transitions [state] from [ReplayState.Idle] to
 *    [ReplayState.Running]. A background ticker republishes the current
 *    virtual time onto the Running state every [STATE_TICK_INTERVAL_MS]
 *    so the test/UI can monitor progress without polling either child.
 *  - [stop] cancels both child engines, removes the mock provider, and
 *    transitions [state] to [ReplayState.Finished]. Safe to call before
 *    [start] (no-op) and safe to call repeatedly.
 *  - A runner is single-use; build a new one for a second run.
 *
 * # What it does NOT do
 *
 *  - Pairing. The caller must hand it a [BleConnection] that is already
 *    live (the activity's [com.ternparagliding.mezulla.MezullaConnectionManager]
 *    owns this in production). The runner does not start, stop, or
 *    rebuild the connection.
 *  - Screen recording or OLED capture. Those are the test harness's
 *    responsibility — see `scripts/aravis-replay-cycle.sh`.
 *  - Map camera control. The map follows the DUT's GPS via the existing
 *    `ReduxLocationService`; nothing in this runner steers the camera.
 *
 * @param context an [android.content.Context] — used only to look up
 *   [LocationManager] for the GPS injector. Application context is fine.
 * @param bleConnection live connection to the paired board. Must already
 *   be started (link UP); the runner will not start it.
 * @param playback swarm playback containing the DUT plus every virtual
 *   peer. Built by the caller from [com.ternparagliding.sim.swarm.scenarios.AravisTeam2026]
 *   (or any other [com.ternparagliding.sim.swarm.Scenario]).
 * @param dutPilotId which pilot in [playback] is the device under test.
 *   Must be a pilot in the scenario.
 * @param peerNodeNumbers map from [PilotId] to the Meshtastic node number
 *   the board will see in `MeshPacket.from` for that peer. Must NOT
 *   include [dutPilotId] (the DUT is the phone, not a peer); other pilots
 *   in the scenario that are absent from this map are silently skipped.
 * @param propagation the LoRa propagation model. Pass
 *   [com.ternparagliding.sim.propagation.DistanceOnlyPropagation] with a
 *   15 km range for the golden Aravis path, or a
 *   [com.ternparagliding.sim.propagation.RandomRangePropagation] for
 *   chaos scenarios.
 * @param speedMultiplier how much faster than real time virtual time
 *   advances. Must be > 0. Both child engines see the same value so they
 *   stay in sync.
 */
class AravisReplayRunner(
    context: Context,
    private val bleConnection: BleConnection,
    private val playback: SwarmPlayback,
    private val dutPilotId: PilotId,
    peerNodeNumbers: Map<PilotId, Long>,
    propagation: PropagationModel,
    speedMultiplier: Int,
) {
    companion object {
        private const val TAG = "AravisReplayRunner"

        /**
         * How often the runner republishes the [ReplayState.Running] snapshot
         * with a fresh virtual-time value. 250 ms is well below human
         * perception of a stale UI but high enough that we don't drown the
         * StateFlow with redundant emissions at 64x replay.
         */
        const val STATE_TICK_INTERVAL_MS: Long = 250L
    }

    init {
        require(speedMultiplier > 0) {
            "speedMultiplier must be > 0 (got $speedMultiplier)"
        }
        require(playback.pilots.contains(dutPilotId)) {
            "dutPilotId '$dutPilotId' is not in the playback scenario"
        }
        require(dutPilotId !in peerNodeNumbers.keys) {
            "peerNodeNumbers must not include the DUT pilot ('$dutPilotId') — " +
                "the DUT is the phone, not a peer"
        }
    }

    /**
     * Mock-GPS engine. Built eagerly so the constructor fails fast if the
     * Android [LocationManager] is unavailable — a state we'd rather see
     * at startup than mid-tick. The system service lookup is cheap.
     */
    private val mockGps: IgcMockLocationProvider = run {
        val locationManager = context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as LocationManager
        IgcMockLocationProvider(
            playback = playback,
            dutPilotId = dutPilotId,
            speedMultiplier = speedMultiplier,
            injector = AndroidLocationInjector(locationManager),
        )
    }

    /**
     * Virtual-peer engine. Wired to the same playback/multiplier/DUT so
     * the DUT's range checks (when [propagation] is distance-aware) track
     * the DUT's actual interpolated position.
     */
    private val peerInjector: VirtualPeerInjector = VirtualPeerInjector(
        bleConnection = bleConnection,
        playback = playback,
        peerNodeNumbers = peerNodeNumbers.filterKeys { it != dutPilotId },
        speedMultiplier = speedMultiplier,
        propagation = propagation,
        dutPilotId = dutPilotId,
    )

    /**
     * Wall-clock instant of [start], used to compute [ReplayState.Running.elapsed].
     * Set to null until [start] has been called.
     */
    @Volatile
    private var startedAtWallClock: Instant? = null

    /** Background coroutine that republishes [ReplayState.Running] snapshots. */
    @Volatile
    private var stateJob: Job? = null

    /** Tripwire so [start] cannot be called twice on the same instance. */
    @Volatile
    private var started: Boolean = false

    private val _state: MutableStateFlow<ReplayState> = MutableStateFlow(ReplayState.Idle)

    /**
     * Live replay state. Transitions: Idle → Running → Finished (or Failed).
     * The Running snapshot is republished every
     * [STATE_TICK_INTERVAL_MS] with a fresh virtualTime/elapsed pair.
     */
    val state: StateFlow<ReplayState> = _state.asStateFlow()

    /** Convenience accessor — current virtual instant from the mock GPS. */
    val currentVirtualTime: Instant get() = mockGps.currentVirtualTime

    /**
     * Launch both child engines on [scope] and start publishing
     * [ReplayState.Running] snapshots. Throws if called twice on the
     * same instance.
     *
     * Note: this does NOT block until either engine finishes. The caller
     * is expected to observe [state] (or just poll Redux peerState) and
     * call [stop] when the test/demo is done.
     */
    fun start(scope: CoroutineScope) {
        check(!started) { "AravisReplayRunner.start() called twice" }
        started = true

        startedAtWallClock = Instant.now()

        Log.i(
            TAG,
            "Starting Aravis replay: dut=$dutPilotId, peers=${peerInjector}, " +
                "swarmStart=${playback.swarmStart}, swarmEnd=${playback.swarmEnd}",
        )

        try {
            mockGps.start(scope)
            peerInjector.start(scope)
        } catch (t: Throwable) {
            // Best-effort teardown of whichever child did manage to start
            // before we propagate the failure as a Failed state.
            runCatching { mockGps.stop() }
            runCatching { peerInjector.stop() }
            _state.value = ReplayState.Failed(
                reason = t.message ?: t.javaClass.simpleName,
            )
            throw t
        }

        _state.value = currentRunningSnapshot()

        stateJob = scope.launch {
            while (true) {
                delay(STATE_TICK_INTERVAL_MS)
                // Only publish while still Running — stop() flips us to
                // Finished and we want that to be the terminal value.
                val current = _state.value
                if (current !is ReplayState.Running) break
                _state.value = currentRunningSnapshot()
            }
        }
    }

    /**
     * Cancel both child engines, remove the mock GPS provider, and
     * transition [state] to [ReplayState.Finished]. Safe to call before
     * [start] (no-op) and safe to call repeatedly — only the first call
     * transitions state.
     */
    suspend fun stop() {
        stateJob?.cancel()
        stateJob = null

        // Stop in reverse order of start so the BLE injector is silenced
        // before the DUT's GPS goes away. The order is not safety-critical
        // (both children are idempotent), but it matches the natural
        // intuition that the radio shuts up first.
        runCatching { peerInjector.stop() }
        runCatching { mockGps.stop() }

        // Don't overwrite a Failed state with Finished — Failed is more
        // informative and should win.
        if (_state.value !is ReplayState.Failed) {
            _state.value = ReplayState.Finished
        }
        Log.i(TAG, "Aravis replay stopped (terminal state: ${_state.value})")
    }

    private fun currentRunningSnapshot(): ReplayState.Running {
        val started = startedAtWallClock
        val elapsed = if (started == null) {
            Duration.ZERO
        } else {
            Duration.between(started, Instant.now())
        }
        return ReplayState.Running(
            virtualTime = mockGps.currentVirtualTime,
            elapsed = elapsed,
        )
    }
}

/**
 * Replay lifecycle as seen by the test/UI.
 *
 * Transitions:
 *
 *  - [Idle] → [Running] on [AravisReplayRunner.start]
 *  - [Running] → [Finished] on [AravisReplayRunner.stop]
 *  - [Running] → [Failed] on uncaught exception in a child engine's start
 *
 * Sealed so a `when` exhausts the variants. New states (Paused, etc.)
 * can be added later without breaking existing observers if they handle
 * the base type.
 */
sealed interface ReplayState {

    /** Constructed but not yet started. */
    data object Idle : ReplayState

    /**
     * Replay is in progress.
     *
     * @property virtualTime the most recent virtual-clock instant emitted
     *   by the mock GPS engine. Republished by the runner every
     *   [AravisReplayRunner.STATE_TICK_INTERVAL_MS].
     * @property elapsed wall-clock time since [AravisReplayRunner.start].
     */
    data class Running(
        val virtualTime: Instant,
        val elapsed: Duration,
    ) : ReplayState

    /** Replay was stopped cleanly. Terminal. */
    data object Finished : ReplayState

    /**
     * Replay never started, or one of the child engines crashed during
     * start. Terminal.
     *
     * @property reason human-readable cause; usually the exception
     *   message. Surface in the test report so the failure is debuggable
     *   without re-running.
     */
    data class Failed(val reason: String) : ReplayState
}
