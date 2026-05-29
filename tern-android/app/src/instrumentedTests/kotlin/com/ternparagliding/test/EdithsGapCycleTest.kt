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
import com.ternparagliding.sim.swarm.scenarios.EdithsGap2026
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
 * Fast-iteration twin of [FullCycleTest] using the Edith's Gap two-pilot
 * scenario. Same end-to-end shape (reflash → pair → replay → visual sample
 * for peer green markers) but the replay is ~2 h of real flight instead
 * of 11 h 15 m. At 256x speed that's ~30 s of replay, so the whole cycle
 * lands in 1–2 min wall-clock instead of ~9 min — used for debugging
 * map / peer-render issues without burning huge iteration time.
 *
 * DUT: Josh. Peer: Stephen. One peer is enough to validate that the
 * rendering pipeline puts FRESH_PEER_GREEN pixels on the screen.
 */
@RunWith(AndroidJUnit4::class)
class EdithsGapCycleTest : MapVisualTest() {

    @get:Rule
    val blePermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    companion object {
        private const val TAG = "EdithsGapCycleTest"

        private const val DEFAULT_SPEED_MULTIPLIER = 256
        private const val BUDGET_HEADROOM_MS = 5_000L
        // Stephen's full flight is ~2 h 04 min ≈ 7 440 s. We sample for
        // that span; at 256x that's ~29 s of wall-clock replay. Headroom
        // is kept tight because anything past Stephen's landing fades the
        // peer past FRESH and the green-pixel check stops finding it.
        private const val FLIGHT_DURATION_SECONDS = 7_440L
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val FRESH_PEER_GREEN = 0xFF4CAF50.toInt()
        private const val GOLDEN_RANGE_METERS = 50_000

        private const val PAIRING_TIMEOUT_MS = 90_000L
        private const val LINK_UP_TIMEOUT_MS = 120_000L

        private val PEER_NODE_NUMBERS: Map<PilotId, Long> = mapOf(
            EdithsGap2026.STEPHEN to 0x10000001L,
        )

        // Edith's Gap, VA — centroid covering both pilots' flight
        // envelope (launches at 38.725 N, -78.510 W and the two flew
        // east-ish toward 38.45 N, -78.32 W).
        private const val LAUNCH_LAT = 38.65
        private const val LAUNCH_LON = -78.45

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
                ?: error("pairUri instrumentation arg required — run via Gradle task edithsGapCycleTest")

        private fun extractNodeFromUri(uri: String): String =
            Uri.parse(uri).getQueryParameter("n")
                ?: error("pairUri missing 'n' parameter: $uri")
    }

    private var runner: AravisReplayRunner? = null
    private var runnerScope: CoroutineScope? = null

    @Before
    fun requireRealHardware() {
        Assume.assumeFalse(
            "Skipping EdithsGapCycleTest: requires real phone + Mezulla board, not emulator",
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
    fun pilot_pairs_then_flies_with_one_buddy_visible() {
        val pairUri = pairUriArg()
        val expectedNode = extractNodeFromUri(pairUri)
        val speedMultiplier = speedMultiplierArg()
        Log.i(TAG, "Edith's Gap cycle: pairUri=$pairUri, expectedNode=$expectedNode, speed=${speedMultiplier}x")

        val pairTapper = startPairDialogAutoTapper()

        scenario("Pilot pairs with Mezulla, then flies and sees one buddy on the map") {

            val activity = composeTestRule.activity
            lateinit var store: MapStore
            lateinit var connection: BleConnection

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

            and("the persistent BLE link is UP (ready to inject the virtual peer)") {
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

            pairTapper.invoke()

            and("the Edith's Gap IGC bundle is loaded into the replay runner") {
                val playback = SwarmPlayback(EdithsGap2026.scenario)
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                runnerScope = scope
                runner = AravisReplayRunner(
                    context = activity.applicationContext,
                    bleConnection = connection,
                    playback = playback,
                    dutPilotId = EdithsGap2026.JOSH,
                    peerNodeNumbers = PEER_NODE_NUMBERS,
                    propagation = DistanceOnlyPropagation(GOLDEN_RANGE_METERS),
                    speedMultiplier = speedMultiplier,
                )
            }

            `when`("the AravisReplayRunner starts at ${speedMultiplier}x speed") {
                val scope = runnerScope ?: error("runnerScope not initialised")
                composeTestRule.runOnUiThread {
                    store.dispatch(com.ternparagliding.redux.MapAction.UpdateCenter(
                        org.osmdroid.util.GeoPoint(LAUNCH_LAT, LAUNCH_LON)
                    ))
                    store.dispatch(com.ternparagliding.redux.MapAction.UpdateZoom(11.0))
                }
                val cameraDeadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < cameraDeadline) {
                    val c = store.state.value.center
                    if (c != null &&
                        kotlin.math.abs(c.latitude - LAUNCH_LAT) < 0.1 &&
                        kotlin.math.abs(c.longitude - LAUNCH_LON) < 0.1) break
                    Thread.sleep(200)
                }
                runner?.start(scope) ?: error("runner not initialised")
                assert(runner?.state?.value is ReplayState.Running) {
                    "Expected Running after start, got ${runner?.state?.value}"
                }
            }

            then("the rendered map keeps showing a fresh-peer green marker across the short flight") {
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
                // At least 2 samples must show a peer marker. We don't
                // require >50% because the IGC playback ends inside the
                // budget window and Stephen's marker then ages past
                // FRESH — that's expected, not a bug. Anything ≥ 2 is
                // proof that the rendering pipeline puts peer-green
                // pixels on the screen.
                assert(peerVisibleSamples >= 2) {
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
