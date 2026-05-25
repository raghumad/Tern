package com.madanala.tern.mezulla.demo

import android.util.Log
import com.madanala.tern.mezulla.connection.MeshtasticConnection
import com.madanala.tern.mezulla.redux.PeerAction
import com.madanala.tern.mezulla.redux.PeerMiddleware
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.sim.propagation.DistanceOnlyPropagation
import com.madanala.tern.sim.simulation.SwarmSimulatedConnection
import com.madanala.tern.sim.swarm.scenarios.AravisTeam2026
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.time.Duration

/**
 * Drives the Aravis team demo replay on the live map.
 *
 * Loads the AravisTeam2026 scenario with tonio24 as the device under
 * test, creates a SwarmSimulatedConnection, wires it through
 * PeerMiddleware into the MapStore, and advances virtual time at
 * accelerated speed (1 minute of flight = ~1 second of wall clock).
 *
 * The replay is lifecycle-scoped: call [start] to begin, [stop] to
 * cancel. The driver creates its own CoroutineScope and cancels it
 * on stop.
 */
class AravisDemoReplay(
    private val store: MapStore,
    private val onReplayFinished: () -> Unit = {},
) {
    companion object {
        private const val TAG = "AravisDemoReplay"

        // Aravis area center (approximately 45.8N, 6.5E)
        private const val ARAVIS_LAT = 45.8
        private const val ARAVIS_LON = 6.5
        private const val ARAVIS_ZOOM = 10.0

        // Accelerated time: 60 virtual seconds per 1 wall-clock second.
        // This means 1 minute of flight = 1 second of display.
        // The full 11-hour flight replays in ~11 minutes;
        // the interesting first 2 hours replay in ~2 minutes.
        private const val ACCELERATION_FACTOR = 60

        // Tick every 30 virtual seconds (= 0.5 real seconds at 60x)
        private const val VIRTUAL_TICK_SECONDS = 30

        // Wall-clock delay between ticks in milliseconds
        private const val TICK_DELAY_MS = (VIRTUAL_TICK_SECONDS * 1000L) / ACCELERATION_FACTOR

        // LoRa range for the demo
        private const val LORA_RANGE_METERS = 15_000
    }

    private var scope: CoroutineScope? = null
    private var connection: SwarmSimulatedConnection? = null
    private var middleware: PeerMiddleware? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Start the demo replay. Centers the map on the Aravis area and
     * begins advancing virtual time.
     *
     * No-op if already running.
     */
    fun start() {
        if (isRunning) return

        Log.i(TAG, "Starting Aravis demo replay")

        val replayScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = replayScope

        replayScope.launch {
            try {
                runReplay(replayScope)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "Demo replay cancelled")
                } else {
                    Log.e(TAG, "Demo replay failed", e)
                }
            } finally {
                isRunning = false
                onReplayFinished()
            }
        }

        isRunning = true
    }

    private suspend fun runReplay(replayScope: CoroutineScope) {
        val scenario = AravisTeam2026.scenario
        val dutPilotId = AravisTeam2026.TONIO24

        val conn = SwarmSimulatedConnection(
            scenario = scenario,
            dutPilotId = dutPilotId,
            propagation = DistanceOnlyPropagation(LORA_RANGE_METERS),
            positionBroadcastIntervalSeconds = VIRTUAL_TICK_SECONDS,
            playbackTickSeconds = VIRTUAL_TICK_SECONDS,
        )
        connection = conn

        // Wire PeerMiddleware to dispatch into MapStore
        val mw = PeerMiddleware(
            connection = conn,
            dispatch = { action -> store.dispatch(action) },
            scope = replayScope,
        )
        middleware = mw

        // Start the middleware collector
        mw.start()

        // Start the simulated connection (scripted mode -- we drive
        // advanceTo manually for accelerated playback)
        conn.start()

        // Center map on the Aravis area
        store.dispatch(MapAction.UpdateCenter(GeoPoint(ARAVIS_LAT, ARAVIS_LON)))
        store.dispatch(MapAction.UpdateZoom(ARAVIS_ZOOM))

        Log.i(TAG, "Replay started: ${conn.scenarioStart} to ${conn.scenarioEnd}")

        // Drive the replay at accelerated speed
        val totalDuration = Duration.between(conn.scenarioStart, conn.scenarioEnd)
        var elapsed = Duration.ZERO
        val step = Duration.ofSeconds(VIRTUAL_TICK_SECONDS.toLong())

        while (elapsed < totalDuration && replayScope.isActive) {
            elapsed = elapsed.plus(step)
            val targetTime = conn.scenarioStart.plus(elapsed)
            conn.advanceTo(targetTime)
            delay(TICK_DELAY_MS)
        }

        // Advance to the very end to trigger the closing LinkStateChange
        if (replayScope.isActive) {
            conn.advanceTo(conn.scenarioEnd)
            Log.i(TAG, "Replay finished: all pilots have landed")
        }
    }

    /**
     * Stop the replay. Cancels the background coroutine and cleans up.
     */
    fun stop() {
        Log.i(TAG, "Stopping demo replay")
        connection?.stop()
        scope?.cancel()
        scope = null
        connection = null
        middleware = null
        isRunning = false
    }
}
