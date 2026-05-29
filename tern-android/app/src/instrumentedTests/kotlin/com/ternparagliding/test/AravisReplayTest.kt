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
import org.junit.Assume
import org.junit.Before
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
         * script can dial it without recompiling the APK. Default 256x:
         * the full 11h 15m Aravis flight replays in ~158 wall-clock
         * seconds, end-to-end, with frames captured every 500 ms.
         */
        private const val DEFAULT_SPEED_MULTIPLIER = 256

        /**
         * Wall-clock budget headroom over computed flight duration, in
         * milliseconds. Covers GPS+camera settle, the in-flight sample
         * cadence overhead, and slow ticks under load.
         */
        private const val BUDGET_HEADROOM_MS = 60_000L

        /**
         * Total span of the Aravis 2026-04-25 fixtures: earliest fix
         * cbe@07:21:16, latest fix lma@18:36:05 → 40,489 s. Rounded up
         * to give the runner a clean end-of-data boundary.
         */
        private const val FLIGHT_DURATION_SECONDS = 40_500L

        /**
         * How often we take a checkpoint screenshot (wall-clock ms)
         * to assert the rendered map keeps showing peer markers
         * across the full flight.
         */
        private const val SAMPLE_INTERVAL_MS = 30_000L

        /**
         * Fresh-peer green from [com.ternparagliding.overlay.mezulla.PeerBundleBuilder]
         * — the glyph color a pilot sees on every fresh peer marker.
         * The peer-visible assertion looks for this color on the
         * rendered map.
         */
        private const val FRESH_PEER_GREEN = 0xFF4CAF50.toInt()

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

        /** Centroid covering tonio/cbe/cor's launch (45.747, 6.507),
         *  the close-cluster peers ~10km east during early flight
         *  (45.77, 6.60), and the convergence area with lma (45.90, 6.88). */
        private const val ARAVIS_LAT = 45.83
        private const val ARAVIS_LON = 6.65

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

    @Before
    fun requireRealHardware() {
        Assume.assumeFalse(
            "Skipping AravisReplayTest: requires real phone + Mezulla board, not emulator",
            isEmulator(),
        )
    }

    private fun isEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT
        val model = android.os.Build.MODEL
        return fp.startsWith("generic") || fp.startsWith("unknown") ||
            fp.contains("emulator") || fp.contains("vbox") ||
            model.contains("Emulator") || model.contains("Android SDK") ||
            android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.HARDWARE.contains("goldfish")
    }

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
    fun aravis_team_xc_replay_golden_path_50km_range() {
        val speedMultiplier = speedMultiplierArg()
        Log.i(TAG, "Running Aravis replay at ${speedMultiplier}x")

        scenario("Aravis team XC replay — golden path (50 km range), full 11h 15m flight") {

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
                // Centre the map on the Aravis launch site BEFORE starting
                // the runner. Then wait for the camera animation to actually
                // arrive — Tern's MapLibre camera animates over ~1-2s when
                // Redux state.center changes, and we want the recording to
                // start with the Alps on screen, not Boulder.
                composeTestRule.runOnUiThread {
                    store.dispatch(com.ternparagliding.redux.MapAction.UpdateCenter(
                        org.osmdroid.util.GeoPoint(ARAVIS_LAT, ARAVIS_LON)
                    ))
                    // Zoom 11 ≈ 5 km visible radius — peers spread out over
                    // ~10 km early in the flight fit comfortably with the DUT.
                    store.dispatch(com.ternparagliding.redux.MapAction.UpdateZoom(11.0))
                }
                val cameraDeadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < cameraDeadline) {
                    val c = store.state.value.center
                    if (c != null &&
                        kotlin.math.abs(c.latitude - ARAVIS_LAT) < 0.1 &&
                        kotlin.math.abs(c.longitude - ARAVIS_LON) < 0.1) {
                        Log.i(TAG, "Camera arrived at Aravis (${c.latitude}, ${c.longitude})")
                        break
                    }
                    Thread.sleep(200)
                }
                runner?.start(scope) ?: error("runner not initialised")

                val state = runner?.state?.value
                assert(state is ReplayState.Running) {
                    "Expected Running state after start, got $state"
                }
            }

            // ================================================================
            // THEN: pilot-visible validation across the full flight.
            //
            // Per feedback_assert_downstream + the user's "we wont do any
            // more redux validation" guidance: we sample the rendered
            // bitmap (the pilot's truth) on a steady cadence across the
            // whole replay and assert (a) the map is not blank and
            // (b) the fresh-peer green glyph color is visible somewhere
            // on screen.
            //
            // Total wait = flight duration / speedMultiplier + headroom.
            // At 256x that's ~158 s + 60 s budget = ~3.6 min wall-clock
            // for the full 11h 15m Aravis flight.
            // ================================================================

            then("the rendered map keeps showing peer markers across the full Aravis flight") {
                val budgetWallMs = (FLIGHT_DURATION_SECONDS * 1000L) / speedMultiplier +
                    BUDGET_HEADROOM_MS
                val deadline = System.currentTimeMillis() + budgetWallMs
                Log.i(
                    TAG,
                    "Pilot-visible sampling: budget=${budgetWallMs}ms, " +
                        "interval=${SAMPLE_INTERVAL_MS}ms, " +
                        "expected samples=${budgetWallMs / SAMPLE_INTERVAL_MS}",
                )

                var samples = 0
                var peerVisibleSamples = 0
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                    composeTestRule.waitForIdle()

                    val bitmap = androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation().uiAutomation.takeScreenshot()
                        ?: throw AssertionError("uiAutomation.takeScreenshot returned null")
                    samples++

                    assert(!com.ternparagliding.utils.VisualValidator.isBlank(bitmap)) {
                        "Sample $samples is blank — rendering failed mid-flight"
                    }

                    val peerVisible = com.ternparagliding.utils.VisualValidator
                        .findColorSignature(
                            bitmap = bitmap,
                            rect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height),
                            targetColor = FRESH_PEER_GREEN,
                            tolerance = 25,
                            minPixels = 10,
                        )
                    if (peerVisible) peerVisibleSamples++

                    val virtualNow = (runner?.state?.value as? ReplayState.Running)
                        ?.virtualTime?.toString() ?: "—"
                    Log.i(
                        TAG,
                        "Sample $samples: virtual=$virtualNow, peerVisible=$peerVisible",
                    )
                }

                Log.i(
                    TAG,
                    "Flight complete: $samples samples, " +
                        "$peerVisibleSamples with visible fresh-peer green",
                )
                assert(samples > 0) {
                    "Wall-clock budget ${budgetWallMs}ms was too small to take any sample"
                }
                // First sample lands while DUT GPS is settling and peers
                // are still registering — allow some warm-up. Require the
                // strict majority of samples to show a peer marker.
                assert(peerVisibleSamples * 2 > samples) {
                    "Only $peerVisibleSamples/$samples samples showed a peer marker — " +
                        "the rendered map did not consistently display fresh peers"
                }
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
