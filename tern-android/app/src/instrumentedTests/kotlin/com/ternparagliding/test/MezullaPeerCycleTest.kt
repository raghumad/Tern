package com.ternparagliding.test

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.ble.BleConnection
import com.ternparagliding.mezulla.pairing.PairingState
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.sim.propagation.DistanceOnlyPropagation
import com.ternparagliding.sim.replay.AravisReplayRunner
import com.ternparagliding.sim.replay.ReplayState
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.Scenario
import com.ternparagliding.sim.swarm.SwarmPlayback
import com.ternparagliding.utils.CacheManager
import com.ternparagliding.utils.CountryUtils
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.PGSpotCache
import com.ternparagliding.utils.PerformanceDebugger
import com.ternparagliding.utils.ReportGenerator
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
import org.osmdroid.util.GeoPoint
import java.time.Duration
import java.time.Instant
import kotlin.math.cos
import kotlin.math.log2

/**
 * One end-to-end "pilot flies with buddies" cycle, shared by every swarm
 * scenario. Subclasses supply only the data — the [scenario], which pilot
 * is the [dutPilotId] (the phone/DUT, whose track the map follows), and the
 * [peerNodeNumbers] for the LoRa buddies. Everything else — pairing, the
 * heading-up look-ahead follow-cam, peer injection, the HUD + off-screen
 * indicator checks, the budget derived from the flight's own duration — is
 * identical across Aravis, Edith's Gap, and Bir Billing. The only thing
 * that differs between them is the IGC files.
 *
 * Run via the per-scenario Gradle tasks (aravisCycleTest, edithsGapCycleTest,
 * birBillingCycleTest), which pass `-PpairUri` and optional
 * `-PspeedMultiplier`.
 */
abstract class MezullaPeerCycleTest : MapVisualTest() {

    /** The swarm to replay. */
    protected abstract val scenario: Scenario
    /** Which pilot is the device under test (the map follows them). */
    protected abstract val dutPilotId: PilotId
    /** LoRa node number to advertise for each buddy (must exclude the DUT). */
    protected abstract val peerNodeNumbers: Map<PilotId, Long>

    @get:Rule
    val blePermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val tag: String get() = this::class.java.simpleName

    private var runner: AravisReplayRunner? = null
    private var runnerScope: CoroutineScope? = null
    private val hudFrameSaved = java.util.concurrent.atomic.AtomicBoolean(false)
    private val offscreenFrameSaved = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── Exploratory overlay+memory probe (opt-in via -e probeOverlays true) ──
    // When on, the cycle additionally loads REAL airspace + PG spots for the
    // scenario's region and samples heap + overlay inventory along the flight,
    // emitting a memory/overlay scorecard at the end. It also downgrades the
    // peer-visibility asserts to logged observations: this is a data-gathering
    // run to understand in-flight memory pressure, not a pass/fail gate.
    private var probe = false
    private var baselineHeapMb = 0.0
    private var peakHeapMb = 0.0
    private val heapSamples = mutableListOf<Double>()
    private val airspaceSamples = mutableListOf<Int>()
    private val pgSamples = mutableListOf<Int>()
    private val probeMiles = 200.0 / 1.60934 // overlay query radius (~200 km)

    private fun usedHeapMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
    }

    private fun sampleHeap() {
        val u = usedHeapMb()
        if (u > peakHeapMb) peakHeapMb = u
        heapSamples += u
    }

    companion object {
        private const val DEFAULT_SPEED_MULTIPLIER = 256
        private const val BUDGET_HEADROOM_MS = 10_000L
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val CAMERA_SETTLE_MS = 600L
        private const val PRIME_TIMEOUT_MS = 12_000L
        private const val FRESH_PEER_GREEN = 0xFF4CAF50.toInt()
        private const val GOLDEN_RANGE_METERS = 50_000

        private const val PAIRING_TIMEOUT_MS = 90_000L
        private const val LINK_UP_TIMEOUT_MS = 120_000L

        // Heading-up look-ahead framing.
        private const val LOOK_AHEAD_KM = 10.0
        private const val PILOT_FRACTION_FROM_BOTTOM = 0.30
        private const val HEADING_LOOKAHEAD_SECONDS = 15L
        // MapLibre GL renders with 512-px tiles → ground resolution at zoom 0
        // is 78271.5 m/px (NOT the 256-tile 156543, which zooms 2x too far).
        private const val EQUATOR_MPP_AT_Z0 = 78_271.51696

        // Pass bar (robust, scenario-agnostic): every buddy must show up in
        // state, and the peer HUD must render on the map across the flight.
        private const val MIN_GREEN_SAMPLES = 3

        private fun zoomForVerticalExtent(extentMeters: Double, viewportHeightPx: Int, latDeg: Double): Double {
            val metersPerPixel = extentMeters / viewportHeightPx
            val mppAtZoom0 = EQUATOR_MPP_AT_Z0 * cos(Math.toRadians(latDeg))
            return log2(mppAtZoom0 / metersPerPixel)
        }

        private fun arg(name: String): String? = try {
            InstrumentationRegistry.getArguments().getString(name)
        } catch (_: Exception) {
            null
        }

        private fun speedMultiplierArg(): Int =
            arg("speedMultiplier")?.toIntOrNull() ?: DEFAULT_SPEED_MULTIPLIER

        private fun probeOverlaysArg(): Boolean =
            arg("probeOverlays")?.toBoolean() ?: false

        private fun pairUriArg(): String =
            arg("pairUri")?.takeIf { it.isNotBlank() }
                ?: error("pairUri instrumentation arg required — run via the scenario's Gradle task")

        private fun extractNodeFromUri(uri: String): String =
            Uri.parse(uri).getQueryParameter("n") ?: error("pairUri missing 'n' parameter: $uri")
    }

    @Before
    fun requireRealHardware() {
        Assume.assumeFalse(
            "Skipping $tag: requires real phone + Mezulla board, not emulator",
            isEmulator(),
        )
        require(dutPilotId !in peerNodeNumbers) { "peerNodeNumbers must not include the DUT" }
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
        probe = probeOverlaysArg()
        val expectedPeers = peerNodeNumbers.size
        Log.i(tag, "${scenario.name}: pairUri=$pairUri node=$expectedNode speed=${speedMultiplier}x peers=$expectedPeers")

        val pairTapper = startPairDialogAutoTapper()

        scenario("Pilot pairs with Mezulla, then flies ${scenario.location} and sees $expectedPeers buddies on the map") {

            val activity = composeTestRule.activity
            lateinit var store: MapStore
            lateinit var connection: BleConnection
            lateinit var playback: SwarmPlayback

            val viewportHeightPx = activity.resources.displayMetrics.heightPixels
            val fullExtentMeters = LOOK_AHEAD_KM * 1000.0 / (1.0 - PILOT_FRACTION_FROM_BOTTOM)
            var lastHeadingDeg = 0.0

            fun dutAt(vt: Instant): GeoPoint? {
                val p = playback.currentPosition(dutPilotId, vt) ?: return null
                return GeoPoint(p.latitude, p.longitude, p.altitudeMeters.toDouble())
            }

            fun grabPeerGreen(): Pair<android.graphics.Bitmap?, Boolean> {
                val bmp = InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.takeScreenshot() ?: return null to false
                val green = com.ternparagliding.utils.VisualValidator.findColorSignature(
                    bitmap = bmp,
                    rect = android.graphics.Rect(0, 0, bmp.width, bmp.height),
                    targetColor = FRESH_PEER_GREEN,
                    tolerance = 25,
                    minPixels = 10,
                )
                return bmp to green
            }

            // Frame the camera heading-up on the DUT: rotate to their track,
            // sit them in the lower third, zoom to a true ~10 km look-ahead.
            fun followDut(): Boolean {
                val r = runner ?: return false
                val vt = r.currentVirtualTime
                val here = dutAt(vt) ?: return false
                val ahead = dutAt(vt.plusSeconds(HEADING_LOOKAHEAD_SECONDS))
                val behind = dutAt(vt.minusSeconds(HEADING_LOOKAHEAD_SECONDS))
                lastHeadingDeg = when {
                    ahead != null -> here.bearingTo(ahead)
                    behind != null -> behind.bearingTo(here)
                    else -> lastHeadingDeg
                }
                val centreOffsetMeters = (0.5 - PILOT_FRACTION_FROM_BOTTOM) * fullExtentMeters
                val centre = here.destinationPoint(centreOffsetMeters, lastHeadingDeg)
                val zoom = zoomForVerticalExtent(fullExtentMeters, viewportHeightPx, here.latitude)
                composeTestRule.runOnUiThread {
                    store.dispatch(MapAction.UpdateUserLocation(here))
                    store.dispatch(MapAction.UpdateMapMovement(rotation = lastHeadingDeg.toFloat(), center = centre, zoom = zoom))
                    // GPS-style centre on the pilot's true position. In a real
                    // flight ReduxLocationService dispatches UpdateCenter from
                    // the GPS fix, which is what drives country-preload; mirror
                    // that here so overlays load + churn along the track.
                    if (probe) store.dispatch(MapAction.UpdateCenter(here))
                }
                return true
            }

            given("Tern is running with no board paired") {
                if (activity.pairingOrchestrator.getPairedNodeId() != null) {
                    activity.pairingOrchestrator.forgetBoard()
                }
                composeTestRule.runOnUiThread {
                    store = ViewModelProvider(activity)[MapStore::class.java]
                }
                if (probe) {
                    // Opt this run into REAL data: clear the harness's "TEST"
                    // country pin and the mock-server base URLs so the airspace +
                    // PG-spot CDNs are hit for the scenario's region as we fly.
                    CountryUtils.setTestCountryCode(null)
                    CacheManager.airspaceCache.resetBaseUrlForTesting()
                    PGSpotCache.resetBaseUrlForTesting()
                    Log.i(tag, "[PROBE] real overlays enabled for region=${scenario.region}")
                }
            }

            `when`("the pilot scans the QR (tern:// deep link triggers Tern)") {
                val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(pairUri) }
                composeTestRule.runOnUiThread { activity.pairingOrchestrator.handleIntent(intent) }
            }

            then("the persistent BLE link reaches Success with the board's node ID") {
                val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
                var state: PairingState = PairingState.Idle
                while (System.currentTimeMillis() < deadline) {
                    state = activity.pairingOrchestrator.state.value
                    if (state is PairingState.Success || state is PairingState.Failed) break
                    Thread.sleep(500)
                }
                Log.i(tag, "Pairing final state: $state")
                assert(state is PairingState.Success) { "Expected PairingState.Success but got: $state" }
                assert((state as PairingState.Success).nodeIdHex == expectedNode) {
                    "Expected node $expectedNode but got ${state.nodeIdHex}"
                }
            }

            and("the persistent BLE link is UP (ready to inject the virtual peers)") {
                val live = activity.connectionManager.activeBleConnection()
                    ?: error("No active BLE connection — pairing did not start the persistent link")
                connection = live
                val deadline = System.currentTimeMillis() + LINK_UP_TIMEOUT_MS
                while (connection.linkState != LinkState.UP && System.currentTimeMillis() < deadline) {
                    Thread.sleep(500)
                }
                if (connection.linkState != LinkState.UP) {
                    error("BLE link did not reach UP within ${LINK_UP_TIMEOUT_MS}ms (currently ${connection.linkState}).")
                }
                Log.i(tag, "BLE link UP, ready for replay")
            }

            pairTapper.invoke()

            and("the ${scenario.name} IGC bundle is loaded into the replay runner") {
                playback = SwarmPlayback(scenario)
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                runnerScope = scope
                runner = AravisReplayRunner(
                    context = activity.applicationContext,
                    bleConnection = connection,
                    playback = playback,
                    dutPilotId = dutPilotId,
                    peerNodeNumbers = peerNodeNumbers,
                    propagation = DistanceOnlyPropagation(GOLDEN_RANGE_METERS),
                    speedMultiplier = speedMultiplier,
                )
            }

            `when`("the replay starts at ${speedMultiplier}x speed and the camera follows the pilot") {
                val scope = runnerScope ?: error("runnerScope not initialised")
                runner?.start(scope) ?: error("runner not initialised")
                assert(runner?.state?.value is ReplayState.Running) {
                    "Expected Running after start, got ${runner?.state?.value}"
                }
                val r = runner ?: error("runner not initialised")
                val framedDeadline = System.currentTimeMillis() + 8000
                while (dutAt(r.currentVirtualTime) == null && System.currentTimeMillis() < framedDeadline) {
                    Thread.sleep(200)
                }
                if (!followDut()) {
                    // DUT hasn't launched yet at swarm start — centre on their
                    // first-ever fix so the map isn't stranded mid-ocean.
                    val first = dutAt(playback.pilot(dutPilotId).firstFixTime)
                        ?: error("DUT '${dutPilotId.value}' has no track in ${scenario.name}")
                    composeTestRule.runOnUiThread {
                        store.dispatch(MapAction.UpdateUserLocation(first))
                        store.dispatch(MapAction.UpdateMapMovement(
                            rotation = 0f, center = first,
                            zoom = zoomForVerticalExtent(fullExtentMeters, viewportHeightPx, first.latitude),
                        ))
                    }
                }
                val cameraDeadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < cameraDeadline) {
                    if (store.state.value.center != null) break
                    Thread.sleep(200)
                }
                Thread.sleep(CAMERA_SETTLE_MS)
            }

            then("the map follows the pilot and the $expectedPeers buddies appear as peer HUDs / edge chips") {
                // Prime the peer-render pipeline until a buddy marker paints once.
                run {
                    val primeDeadline = System.currentTimeMillis() + PRIME_TIMEOUT_MS
                    var primed = false
                    while (System.currentTimeMillis() < primeDeadline) {
                        followDut()
                        composeTestRule.waitForIdle()
                        Thread.sleep(CAMERA_SETTLE_MS)
                        if (grabPeerGreen().second) { primed = true; break }
                    }
                    Log.i(tag, "Peer render primed=$primed")
                }

                if (probe) {
                    // Give the region's airspace + PG spots time to download as
                    // we orbit the launch, then record the baseline heap.
                    val cc = scenario.region.uppercase()
                    val cdl = System.currentTimeMillis() + 180_000
                    while (System.currentTimeMillis() < cdl) {
                        if (store.state.value.airspaceCountries.any { it.equals(cc, true) } &&
                            store.state.value.pgSpotGeoJson != null) break
                        followDut(); Thread.sleep(2000)
                    }
                    baselineHeapMb = usedHeapMb(); peakHeapMb = baselineHeapMb
                    val here0 = dutAt(runner?.currentVirtualTime ?: playback.swarmStart)
                    val air0 = if (here0 != null) runCatching {
                        CacheManager.airspaceCache.queryNearbyFeatures(cc, here0, probeMiles, 5000).size
                    }.getOrDefault(0) else 0
                    Log.i(tag, "[PROBE] baseline heap=${"%.1f".format(baselineHeapMb)}MB, loaded=${store.state.value.airspaceCountries}, airspace≈$air0")
                    ReportGenerator.logStep("AND", "[PROBE] $cc overlays loaded; baseline heap=${"%.1f".format(baselineHeapMb)}MB; airspace cached near pilot=$air0", "INFO")
                }

                val swarmSeconds = Duration.between(playback.swarmStart, playback.swarmEnd).seconds.coerceAtLeast(1)
                val budgetMs = swarmSeconds * 1000L / speedMultiplier + BUDGET_HEADROOM_MS
                val deadline = System.currentTimeMillis() + budgetMs
                Log.i(tag, "Sampling: flight=${swarmSeconds}s budget=${budgetMs}ms interval=${SAMPLE_INTERVAL_MS}ms")

                var samples = 0
                var peerVisibleSamples = 0
                var offscreenChipSamples = 0
                var accountedSamples = 0
                var maxPeersSeen = 0
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                    followDut()
                    composeTestRule.waitForIdle()
                    Thread.sleep(CAMERA_SETTLE_MS)

                    val peerCount = store.state.value.peerState.peers.size
                    maxPeersSeen = maxOf(maxPeersSeen, peerCount)
                    val offscreenChips = composeTestRule
                        .onAllNodesWithContentDescription("offscreen-peer:", substring = true)
                        .fetchSemanticsNodes().size
                    if (offscreenChips > 0) offscreenChipSamples++

                    if (probe) {
                        sampleHeap()
                        val here = dutAt(runner?.currentVirtualTime ?: playback.swarmStart)
                        if (here != null) {
                            val cc = scenario.region.uppercase()
                            airspaceSamples += runCatching {
                                CacheManager.airspaceCache.queryNearbyFeatures(cc, here, probeMiles, 5000).size
                            }.getOrDefault(0)
                            pgSamples += runCatching {
                                CacheManager.pgSpotCache.queryNearbyPGSpots(cc, here, probeMiles, 5000).size
                            }.getOrDefault(0)
                        }
                    }

                    val (bitmap, peerVisible) = grabPeerGreen()
                    bitmap ?: throw AssertionError("uiAutomation.takeScreenshot returned null")
                    samples++
                    if (!probe) assert(!com.ternparagliding.utils.VisualValidator.isBlank(bitmap)) {
                        "Sample $samples is blank — rendering failed mid-flight"
                    }
                    if (peerVisible) peerVisibleSamples++
                    // A buddy is "accounted for" if visible on the map OR shown
                    // as an off-screen edge chip — the safety property that
                    // matters: you never silently lose a peer.
                    if (peerVisible || offscreenChips > 0) accountedSamples++

                    if (peerVisible && hudFrameSaved.compareAndSet(false, true)) {
                        runCatching {
                            val out = java.io.File(activity.getExternalFilesDir("tern-tests-report"), "HUD_${scenario.region}_onscreen.png")
                            java.io.FileOutputStream(out).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            Log.i(tag, "saved on-screen HUD frame: ${out.absolutePath}")
                        }
                    }
                    if (offscreenChips > 0 && offscreenFrameSaved.compareAndSet(false, true)) {
                        runCatching {
                            val out = java.io.File(activity.getExternalFilesDir("tern-tests-report"), "HUD_${scenario.region}_offscreen.png")
                            java.io.FileOutputStream(out).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            Log.i(tag, "saved off-screen-chip frame: ${out.absolutePath}")
                        }
                    }

                    Log.i(tag, "Sample $samples: virtual=${runner?.currentVirtualTime ?: "—"} peers=$peerCount green=$peerVisible offscreen=$offscreenChips")
                }

                Log.i(tag, "Flight complete: $samples samples, maxPeers=$maxPeersSeen, green=$peerVisibleSamples, offscreen=$offscreenChipSamples, accounted=$accountedSamples")
                if (probe) {
                    emitProbeScorecard(samples, maxPeersSeen, expectedPeers, peerVisibleSamples, accountedSamples)
                } else {
                    assert(samples > 0) { "No samples taken — budget too small?" }
                    assert(maxPeersSeen >= expectedPeers) {
                        "Expected $expectedPeers buddies in state at some point, but saw at most $maxPeersSeen"
                    }
                    assert(peerVisibleSamples >= MIN_GREEN_SAMPLES) {
                        "Peer HUD rendered on the map in only $peerVisibleSamples samples (< $MIN_GREEN_SAMPLES) — render gap"
                    }
                    // Once primed, a buddy should be accounted for (on-map or edge
                    // chip) in the large majority of samples — never silently lost.
                    assert(accountedSamples * 2 > samples) {
                        "Buddies were accounted for in only $accountedSamples/$samples samples"
                    }
                }
            }

            and("the runner stops cleanly", takeScreenshot = false) {
                runBlocking { runner?.stop() }
                val terminal = runner?.state?.value
                if (!probe) assert(terminal is ReplayState.Finished || terminal is ReplayState.Failed) {
                    "Expected terminal state after stop, got $terminal"
                }
            }
        }
    }

    /**
     * Emit the exploratory in-flight memory + overlay scorecard. Pure data —
     * no assertions. Captures the final rendered overlay pixel counts, the
     * heap trajectory, and the airspace/PG inventory range seen along the
     * track, plus the peer-visibility figures as advisory context.
     */
    private fun emitProbeScorecard(
        samples: Int,
        maxPeersSeen: Int,
        expectedPeers: Int,
        peerVisibleSamples: Int,
        accountedSamples: Int,
    ) {
        val finalHeap = usedHeapMb()
        if (finalHeap > peakHeapMb) peakHeapMb = finalHeap
        val retained = finalHeap - baselineHeapMb
        val airMin = airspaceSamples.minOrNull() ?: 0
        val airMax = airspaceSamples.maxOrNull() ?: 0
        val airAvg = if (airspaceSamples.isNotEmpty()) airspaceSamples.average() else 0.0
        val pgMin = pgSamples.minOrNull() ?: 0
        val pgMax = pgSamples.maxOrNull() ?: 0
        val pgAvg = if (pgSamples.isNotEmpty()) pgSamples.average() else 0.0

        // Final rendered-overlay pixel snapshot.
        val blue: Int
        val teal: Int
        try {
            val shot = captureScreenBitmap()
            val rect = centralBox(shot)
            blue = blueDominantPixels(shot, rect, minBlue = 50)
            teal = tealDominantPixels(shot, rect)
        } catch (e: Throwable) {
            Log.w(tag, "[PROBE] final render snapshot failed: ${e.message}")
            return
        }

        val b = PerformanceDebugger.DEFAULT_BUDGET
        val line = "[PROBE] ${scenario.location} @64x — " +
            "heap baseline=${"%.1f".format(baselineHeapMb)} peak=${"%.1f".format(peakHeapMb)} " +
            "final=${"%.1f".format(finalHeap)} retained=${"%.1f".format(retained)}MB (peak budget ${b.maxPeakHeapMb}); " +
            "airspace near pilot min/avg/max=$airMin/${"%.0f".format(airAvg)}/$airMax; " +
            "PG spots min/avg/max=$pgMin/${"%.0f".format(pgAvg)}/$pgMax; " +
            "final render blue-px=$blue teal-px=$teal; " +
            "peers max=$maxPeersSeen/$expectedPeers, HUD-visible=$peerVisibleSamples/$samples, accounted=$accountedSamples/$samples; " +
            "heap-samples=${heapSamples.size}"
        Log.i(tag, line)
        ReportGenerator.logStep("THEN", line, "INFO")
        // Full heap trajectory to logcat for offline analysis.
        Log.i(tag, "[PROBE] heap trajectory MB: " + heapSamples.joinToString(",") { "%.1f".format(it) })
        Log.i(tag, "[PROBE] airspace trajectory: " + airspaceSamples.joinToString(","))
        Log.i(tag, "[PROBE] pg trajectory: " + pgSamples.joinToString(","))
    }
}
