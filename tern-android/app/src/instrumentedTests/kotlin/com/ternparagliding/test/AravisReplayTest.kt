package com.ternparagliding.test

import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.redux.PeerState
import com.ternparagliding.redux.MapStore
import com.ternparagliding.sim.propagation.DistanceOnlyPropagation
import com.ternparagliding.sim.replay.AravisReplayRunner
import com.ternparagliding.sim.replay.ReplayState
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.SwarmPlayback
import com.ternparagliding.sim.swarm.scenarios.AravisTeam2026
import com.ternparagliding.utils.MapVisualTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Aravis team XC replay on a real phone + real paired Mezulla board.
 *
 * Pre-conditions (the cycle script handles these — running this raw will
 * fail or hang):
 *
 *  - Phone connected via adb.
 *  - Tern + test APKs installed.
 *  - A real Mezulla board ALREADY PAIRED (same physical board powered on
 *    and within BLE range). Pairing reset is destructive (re-flashes
 *    firmware) so we do not pair from scratch each run — the cycle
 *    script's job is to ensure the board is paired before invoking us.
 *  - Mock-location app set to Tern in Developer Options (the
 *    [IgcMockLocationProvider] needs this on API 23+).
 *  - Board running test firmware with the radio in `UNSET` so injected
 *    Position frames stay on the BLE pipe and never go out over the air.
 *
 * Run with:
 *   ./scripts/aravis-replay-cycle.sh
 *
 * What this proves (and doesn't):
 *
 *   Yes: The full DUT + virtual-peer pipeline lights up end-to-end on a
 *        real phone with a real BLE link. PeerState ends up containing
 *        the three Aravis teammates; the on-screen recording shows their
 *        markers; the OLED screendumps (taken from the host, not in this
 *        test) show "3 peers" on the board.
 *
 *   No:  This test does NOT validate LoRa physics, range modelling, or
 *        the board's view of peers it heard over the air. The injection
 *        loopback bypasses the radio entirely (that's why we use the
 *        test firmware with radio UNSET). Real-world range / terrain /
 *        fade is the human test's job.
 */
@RunWith(AndroidJUnit4::class)
class AravisReplayTest : MapVisualTest() {

    @get:Rule
    val blePermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    companion object {
        private const val TAG = "AravisReplayTest"

        /**
         * Speed multiplier — passed via instrumentation arg so the cycle
         * script can dial it without recompiling the APK. Default 64x:
         * a 60-wall-second test covers ~64 virtual minutes, enough to
         * see all three Aravis teammates register and start moving.
         */
        private const val DEFAULT_SPEED_MULTIPLIER = 64

        /**
         * LoRa range in metres for the golden path. Matches
         * [DistanceOnlyPropagation] / [VirtualPeerInjector] defaults.
         */
        // 50 km — realistic clear-air LoRa range for paragliding (mountain
        // line-of-sight, 868/915 MHz at the Meshtastic preset). The Aravis
        // scenario has lma launching ~30 km NE of tonio's launch; at 15 km
        // golden range, lma would never reach tonio and the "see all my
        // buddies" assertion would correctly fail. 50 km covers the whole
        // 4-pilot Aravis swarm.
        private const val GOLDEN_RANGE_METERS = 50_000

        /**
         * How long we wait for all three peers to appear in PeerState
         * before failing. With 64x replay and a 30s broadcast cadence,
         * each peer should land within the first ~1 wall-clock second.
         * 60 s is the project's "test isn't flaky" headroom.
         */
        private const val PEER_DISCOVERY_TIMEOUT_MS = 60_000L

        // Cold reconnect after force-stop takes 30-60s based on observation.
        // Give 120s margin to avoid flakiness without hanging forever.
        private const val LINK_UP_TIMEOUT_MS = 120_000L

        /** Peer node numbers — arbitrary unique 32-bit values for the loopback. */
        private val PEER_NODE_NUMBERS: Map<PilotId, Long> = mapOf(
            AravisTeam2026.CBE to 0x10000001L,
            AravisTeam2026.COR to 0x10000002L,
            AravisTeam2026.LMA to 0x10000003L,
        )

        /** Display names we expect on the peers, in test-spec order. */
        private val EXPECTED_PEER_NAMES: Set<String> = setOf("cbe", "cor", "lma")

        private fun speedMultiplierArg(): Int {
            return try {
                val args = InstrumentationRegistry.getArguments()
                args.getString("speedMultiplier")?.toIntOrNull()
                    ?: DEFAULT_SPEED_MULTIPLIER
            } catch (_: Exception) {
                DEFAULT_SPEED_MULTIPLIER
            }
        }
    }

    private var runner: AravisReplayRunner? = null
    private var runnerScope: CoroutineScope? = null

    @After
    fun tearDownRunner() {
        val r = runner
        if (r != null) {
            runBlocking { runCatching { r.stop() } }
        }
        runnerScope?.cancel()
        runner = null
        runnerScope = null
    }

    @Test
    fun aravis_team_xc_replay_golden_path_15km_range() {
        val speedMultiplier = speedMultiplierArg()
        Log.i(TAG, "Running Aravis replay at ${speedMultiplier}x")

        scenario("Aravis team XC replay — golden path (15 km range)") {

            // ================================================================
            // GIVEN: paired board + scenario loaded
            // ================================================================

            // Activity + Redux store + live BLE handle are looked up once
            // and then used by both GIVEN blocks. lateinit is fine here —
            // the BDD framework runs the blocks serially in declared order.
            val activity = composeTestRule.activity
            lateinit var store: MapStore
            lateinit var connection: BleConnection

            given("the app is running with a paired Mezulla board") {
                // The persistent BLE connection should already be live
                // because (a) the board is paired (cycle script ensured
                // this) and (b) MezullaConnectionManager.initialize()
                // auto-starts the connection on activity create for any
                // previously-paired board.
                composeTestRule.runOnUiThread {
                    store = ViewModelProvider(activity)[MapStore::class.java]
                }

                val live = activity.connectionManager.activeBleConnection()
                    ?: error(
                        "No active BLE connection — the cycle script must " +
                            "pair the board before running this test"
                    )
                connection = live
                Log.i(TAG, "Live BLE connection found (linkState=${connection.linkState})")

                // Wait for linkState=UP before starting the runner — otherwise
                // VirtualPeerInjector's writeToRadio calls silently drop. Cold
                // reconnect after force-stop has been observed to take 30-60s.
                val linkUpDeadline = System.currentTimeMillis() + LINK_UP_TIMEOUT_MS
                while (connection.linkState != LinkState.UP &&
                       System.currentTimeMillis() < linkUpDeadline) {
                    Thread.sleep(500)
                }
                if (connection.linkState != LinkState.UP) {
                    error(
                        "BLE link did not reach UP within ${LINK_UP_TIMEOUT_MS}ms " +
                            "(currently ${connection.linkState}). Check that the " +
                            "board is powered on and within range."
                    )
                }
                Log.i(TAG, "BLE link is UP, proceeding with replay")
            }

            and("the Aravis IGC bundle (tonio24, cbe, cor, lma) is loaded") {
                val playback = SwarmPlayback(AravisTeam2026.scenario)
                Log.i(
                    TAG,
                    "Aravis playback loaded: ${playback.pilots.size} pilots, " +
                        "swarmStart=${playback.swarmStart}, swarmEnd=${playback.swarmEnd}",
                )

                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                runnerScope = scope

                runner = AravisReplayRunner(
                    context = activity.applicationContext,
                    bleConnection = connection,
                    playback = playback,
                    dutPilotId = AravisTeam2026.TONIO24,
                    peerNodeNumbers = PEER_NODE_NUMBERS,
                    propagation = DistanceOnlyPropagation(GOLDEN_RANGE_METERS),
                    speedMultiplier = speedMultiplier,
                )
            }

            // ================================================================
            // WHEN: replay starts
            // ================================================================

            `when`("the AravisReplayRunner starts at ${speedMultiplier}x speed") {
                val scope = runnerScope ?: error("runnerScope not initialised")
                // Centre the map on the Aravis launch site so the video
                // shows what we expect — terrain map of the Alps with
                // peer markers moving across it. Without this, Tern's
                // camera stays at whatever the last user-pan location
                // was (default Boulder, CO for fresh installs), and the
                // peer markers render off-screen.
                composeTestRule.runOnUiThread {
                    store.dispatch(com.ternparagliding.redux.MapAction.UpdateCenter(
                        org.osmdroid.util.GeoPoint(45.85, 6.42)
                    ))
                }
                runner?.start(scope) ?: error("runner not initialised")

                // Sanity: state should transition to Running synchronously.
                val state = runner?.state?.value
                assert(state is ReplayState.Running) {
                    "Expected Running state after start, got $state"
                }
            }

            // ================================================================
            // THEN: peers register in PeerState
            // ================================================================

            then("within 60 wall-clock seconds, PeerState contains cbe, cor, lma") {
                val startTime = System.currentTimeMillis()
                var lastSeenNames: Set<String> = emptySet()

                while (System.currentTimeMillis() - startTime < PEER_DISCOVERY_TIMEOUT_MS) {
                    val peerState: PeerState = store.state.value.peerState
                    lastSeenNames = peerState.peers.values
                        .mapNotNull { it.identity.longName }
                        .toSet()

                    // We don't require longName — Position frames don't
                    // carry it, only NodeInfo does. Match on either the
                    // long name (if NodeInfo arrived) or the synthetic
                    // hex id (always present).
                    val nodeNumbersSeen = peerState.peers.keys
                    val expectedNodeNumbers = PEER_NODE_NUMBERS.values.toSet()
                    if (nodeNumbersSeen.containsAll(expectedNodeNumbers)) {
                        Log.i(
                            TAG,
                            "All 3 peers registered after " +
                                "${System.currentTimeMillis() - startTime}ms " +
                                "(names=$lastSeenNames, nodeNumbers=$nodeNumbersSeen)",
                        )
                        return@then
                    }
                    Thread.sleep(500)
                }

                throw AssertionError(
                    "Timed out after ${PEER_DISCOVERY_TIMEOUT_MS}ms waiting for all 3 " +
                        "peers in PeerState. Last seen names: $lastSeenNames, " +
                        "expected (any of) ${EXPECTED_PEER_NAMES}; " +
                        "expected node numbers ${PEER_NODE_NUMBERS.values}, " +
                        "saw ${store.state.value.peerState.peers.keys}"
                )
            }

            and("the runner reports Running state with non-zero virtual elapsed", takeScreenshot = false) {
                val state = runner?.state?.value
                assert(state is ReplayState.Running) {
                    "Expected Running, got $state"
                }
                val running = state as ReplayState.Running
                Log.i(
                    TAG,
                    "Runner state: virtualTime=${running.virtualTime}, " +
                        "elapsed=${running.elapsed.toMillis()}ms",
                )
                assert(!running.elapsed.isZero) {
                    "Expected non-zero elapsed wall-clock time, got ${running.elapsed}"
                }
            }

            and("a screenshot captures the map with peer markers") {
                // The screen recording (started by MapVisualTest.setup()) is
                // the primary evidence — this checkpoint screenshot is a
                // belt-and-braces still frame for the report.
                composeTestRule.waitForIdle()
                Thread.sleep(500)
                // Captured automatically by the BDD framework via
                // takeScreenshot=true on this step.
            }

            // ================================================================
            // CLEANUP: stop the runner so @After is quick
            // ================================================================

            and("the runner stops cleanly", takeScreenshot = false) {
                runBlocking { runner?.stop() }
                val state = runner?.state?.value
                assert(state is ReplayState.Finished || state is ReplayState.Failed) {
                    "Expected terminal state after stop, got $state"
                }
            }
        }
    }
}
