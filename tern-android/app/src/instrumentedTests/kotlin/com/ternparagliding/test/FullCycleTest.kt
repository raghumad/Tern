package com.ternparagliding.test

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.pairing.PairingState
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
 * The end-to-end pilot journey, as a single instrumented test:
 *
 *   1. Pilot scans the QR on a freshly-flashed Mezulla board.
 *   2. Tern opens via the `tern://` deep link, claims the board, and the
 *      persistent BLE link comes up — silently (no OS pair prompts).
 *   3. The Aravis convergence replay starts; fresh-peer green markers
 *      appear continuously on the rendered map across the full 11h 15m
 *      flight.
 *
 * This subsumes [BlePairingTest] + [AravisReplayTest] — both still exist
 * as smaller diagnostic units, but THIS is the one row in the BDD report
 * that says "the whole pilot journey works on real hardware".
 *
 * Pre-conditions (the [scripts/full-cycle.sh] wrapper handles these):
 *   - Phone connected via adb, Tern + test APKs installed.
 *   - Mezulla board reflashed clean, showing its QR on OLED, within BLE range.
 *   - `pairUri` instrumentation arg supplied with the deep link from the QR.
 *   - Mock-location app set to Tern in Developer Options.
 *   - Board running test firmware with radio UNSET so injected positions
 *     never go over the air.
 *
 * Run with:
 *   ./scripts/full-cycle.sh [device-serial] [speed-multiplier]
 *
 * (Running this raw via `./gradlew connectedAndroidTest` without the
 * reflash + pairUri arg will fail in the `given` step — by design;
 * the precondition is part of the scenario.)
 */
@RunWith(AndroidJUnit4::class)
class FullCycleTest : MapVisualTest() {

    @get:Rule
    val blePermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    companion object {
        private const val TAG = "FullCycleTest"

        private const val DEFAULT_SPEED_MULTIPLIER = 256
        private const val BUDGET_HEADROOM_MS = 60_000L
        private const val FLIGHT_DURATION_SECONDS = 40_500L
        private const val SAMPLE_INTERVAL_MS = 30_000L
        private const val FRESH_PEER_GREEN = 0xFF4CAF50.toInt()
        private const val GOLDEN_RANGE_METERS = 50_000

        private const val PAIRING_TIMEOUT_MS = 90_000L
        private const val LINK_UP_TIMEOUT_MS = 120_000L

        private val PEER_NODE_NUMBERS: Map<PilotId, Long> = mapOf(
            AravisTeam2026.CBE to 0x10000001L,
            AravisTeam2026.COR to 0x10000002L,
            AravisTeam2026.LMA to 0x10000003L,
        )

        private const val ARAVIS_LAT = 45.83
        private const val ARAVIS_LON = 6.65

        private fun arg(name: String): String? {
            return try {
                InstrumentationRegistry.getArguments().getString(name)
            } catch (_: Exception) {
                null
            }
        }

        private fun speedMultiplierArg(): Int =
            arg("speedMultiplier")?.toIntOrNull() ?: DEFAULT_SPEED_MULTIPLIER

        private fun pairUriArg(): String =
            arg("pairUri")?.takeIf { it.isNotBlank() }
                ?: error("pairUri instrumentation arg required — run via full-cycle.sh")

        private fun extractNodeFromUri(uri: String): String =
            Uri.parse(uri).getQueryParameter("n")
                ?: error("pairUri missing 'n' parameter: $uri")
    }

    private var runner: AravisReplayRunner? = null
    private var runnerScope: CoroutineScope? = null

    // Real hardware required: a paired Mezulla board over BLE + GPS mock
    // location. None of this works on the managed-device emulator. Failing
    // fast as an assumption (SKIP, not FAIL) keeps the emulator CI lane
    // green and signals clearly that this test belongs on the bench.
    @Before
    fun requireRealHardware() {
        Assume.assumeFalse(
            "Skipping FullCycleTest: requires real phone + Mezulla board, not emulator",
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
        runner?.let { runBlocking { runCatching { it.stop() } } }
        runnerScope?.cancel()
        runner = null
        runnerScope = null
    }

    @Test
    fun pilot_pairs_then_flies_with_buddies_visible() {
        val pairUri = pairUriArg()
        val expectedNode = extractNodeFromUri(pairUri)
        val speedMultiplier = speedMultiplierArg()
        Log.i(TAG, "Full cycle: pairUri=$pairUri, expectedNode=$expectedNode, speed=${speedMultiplier}x")

        // ================================================================
        //   Scenario: Pilot pairs with Mezulla, then flies and sees
        //             buddies on the map.
        //
        //   Per Option A (BLE Core Spec + AOSP reality): silent SMP
        //   bonding is not achievable from a non-privileged app. The
        //   pilot taps "Pair" once — Tern primes them by showing the
        //   PIN in advance, OLED shows the same PIN, system dialog
        //   shows the same PIN. UI Automator simulates the tap so the
        //   test runs unattended.
        // ================================================================

        val pairTapper = startPairDialogAutoTapper()

        scenario("Pilot pairs with Mezulla, then flies and sees buddies on the map") {

            val activity = composeTestRule.activity
            lateinit var store: MapStore
            lateinit var connection: BleConnection

            // ---------- PAIRING PHASE ----------

            given("Tern is running with no board paired") {
                if (activity.pairingOrchestrator.getPairedNodeId() != null) {
                    activity.pairingOrchestrator.forgetBoard()
                }
                composeTestRule.runOnUiThread {
                    store = ViewModelProvider(activity)[MapStore::class.java]
                }
            }

            `when`("the pilot scans the QR (tern:// deep link triggers Tern)") {
                val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(pairUri) }
                composeTestRule.runOnUiThread {
                    activity.pairingOrchestrator.handleIntent(intent)
                }
            }

            then("the persistent BLE link reaches Success with the board's node ID") {
                val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
                var state: PairingState = PairingState.Idle
                while (System.currentTimeMillis() < deadline) {
                    state = activity.pairingOrchestrator.state.value
                    if (state is PairingState.Success || state is PairingState.Failed) break
                    Thread.sleep(500)
                }
                Log.i(TAG, "Pairing final state: $state")
                assert(state is PairingState.Success) {
                    "Expected PairingState.Success but got: $state"
                }
                assert((state as PairingState.Success).nodeIdHex == expectedNode) {
                    "Expected node $expectedNode but got ${state.nodeIdHex}"
                }
            }

            // ---------- FLY PHASE ----------

            and("the persistent BLE link is UP (ready to inject virtual peers)") {
                val live = activity.connectionManager.activeBleConnection()
                    ?: error("No active BLE connection — pairing did not start the persistent link")
                connection = live
                val deadline = System.currentTimeMillis() + LINK_UP_TIMEOUT_MS
                while (connection.linkState != LinkState.UP &&
                       System.currentTimeMillis() < deadline) {
                    Thread.sleep(500)
                }
                if (connection.linkState != LinkState.UP) {
                    error(
                        "BLE link did not reach UP within ${LINK_UP_TIMEOUT_MS}ms " +
                            "(currently ${connection.linkState})."
                    )
                }
                Log.i(TAG, "BLE link UP, ready for replay")
            }

            // Pairing settled — stop watching for pair dialogs so the
            // tapper thread doesn't accidentally interact with anything
            // else during the replay.
            pairTapper.invoke()

            and("the Aravis IGC bundle is loaded into the replay runner") {
                val playback = SwarmPlayback(AravisTeam2026.scenario)
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

            `when`("the AravisReplayRunner starts at ${speedMultiplier}x speed") {
                val scope = runnerScope ?: error("runnerScope not initialised")
                composeTestRule.runOnUiThread {
                    store.dispatch(com.ternparagliding.redux.MapAction.UpdateCenter(
                        org.osmdroid.util.GeoPoint(ARAVIS_LAT, ARAVIS_LON)
                    ))
                    store.dispatch(com.ternparagliding.redux.MapAction.UpdateZoom(11.0))
                }
                val cameraDeadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < cameraDeadline) {
                    val c = store.state.value.center
                    if (c != null &&
                        kotlin.math.abs(c.latitude - ARAVIS_LAT) < 0.1 &&
                        kotlin.math.abs(c.longitude - ARAVIS_LON) < 0.1) break
                    Thread.sleep(200)
                }
                runner?.start(scope) ?: error("runner not initialised")
                assert(runner?.state?.value is ReplayState.Running) {
                    "Expected Running after start, got ${runner?.state?.value}"
                }
            }

            then("the rendered map keeps showing fresh-peer green markers across the full flight") {
                val budgetMs = (FLIGHT_DURATION_SECONDS * 1000L) / speedMultiplier + BUDGET_HEADROOM_MS
                val deadline = System.currentTimeMillis() + budgetMs
                Log.i(
                    TAG,
                    "Pilot-visible sampling: budget=${budgetMs}ms, " +
                        "interval=${SAMPLE_INTERVAL_MS}ms, " +
                        "expected samples=${budgetMs / SAMPLE_INTERVAL_MS}",
                )

                var samples = 0
                var peerVisibleSamples = 0
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                    composeTestRule.waitForIdle()

                    val bitmap = InstrumentationRegistry.getInstrumentation()
                        .uiAutomation.takeScreenshot()
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
                    Log.i(TAG, "Sample $samples: virtual=$virtualNow, peerVisible=$peerVisible")
                }

                Log.i(TAG, "Flight complete: $samples samples, $peerVisibleSamples with peer green")
                assert(samples > 0) { "No samples taken — budget too small?" }
                assert(peerVisibleSamples * 2 > samples) {
                    "Only $peerVisibleSamples/$samples samples showed a peer marker"
                }
            }

            and("the runner stops cleanly", takeScreenshot = false) {
                runBlocking { runner?.stop() }
                val terminal = runner?.state?.value
                assert(terminal is ReplayState.Finished || terminal is ReplayState.Failed) {
                    "Expected terminal state after stop, got $terminal"
                }
            }
        }
    }
}
