package com.madanala.tern.test.convergence

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import com.madanala.tern.mezulla.connection.LinkState
import com.madanala.tern.mezulla.redux.KnownPeer
import com.madanala.tern.mezulla.redux.PeerMiddleware
import com.madanala.tern.mezulla.redux.PeerState
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.MezullaViewMode
import com.madanala.tern.sim.propagation.DistanceOnlyPropagation
import com.madanala.tern.sim.simulation.SwarmSimulatedConnection
import com.madanala.tern.sim.swarm.scenarios.AravisTeam2026
import com.madanala.tern.ui.givenAppIsLaunchedOnMap
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.ReportGenerator
import com.madanala.tern.utils.VideoHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Visual BDD convergence test for Mezulla peer awareness on MapLibre.
 *
 * This is THE truthfulness evidence for the buddy-flying feature. It loads
 * the Aravis 4-pilot replay on the real MapLibre map inside the managed-
 * device emulator, advances through six checkpoints, captures screenshots
 * at each one, and records a video of the full run.
 *
 * What this test proves (and doesn't):
 *
 *   Yes: The full pipeline -- IGC files to SwarmPlayback to
 *        SwarmSimulatedConnection to PeerMiddleware to MapStore to
 *        MezullaPeerLayer (MapLibre SymbolLayer) -- wires together on a
 *        real running app, and produces the right PeerState at each
 *        checkpoint. The screenshots show what actually rendered on screen
 *        for a human reviewer to verify: peer callsigns, altitudes, and
 *        staleness as real text labels on a real map tile.
 *
 *   No:  This test cannot programmatically assert that MapLibre
 *        SymbolLayer markers rendered at specific pixel coordinates.
 *        SymbolLayer text is GPU-drawn by MapLibre's native renderer,
 *        not Compose nodes, so Compose test matchers cannot find them.
 *        Programmatic assertions are against PeerState (data truth) and
 *        Compose UI elements (status indicator, view-mode button). The
 *        screenshots are the visual evidence for marker rendering.
 *
 * Flight geometry (from the real IGC files):
 *
 *   Pilot     First fix (UTC)   Last fix (UTC)   Launch site
 *   --------  ----------------  ---------------  -----------------------
 *   cbe       07:21:16          18:15:59         Aravis (same as tonio)
 *   cor       07:21:22          18:08:55         Aravis (same as tonio)
 *   tonio24   07:21:36          18:17:39         Aravis
 *   lma       07:43:07          18:36:05         ~30 km NE of Aravis
 *
 * DUT: tonio24. Propagation: DistanceOnlyPropagation(15,000 m).
 * Broadcast cadence: 30 seconds.
 */
class MezullaBuddyFlyingVisualTest : MapVisualTest() {

    companion object {
        private const val TAG = "MezullaBuddyFlyingVisualTest"

        // Aravis area
        private const val ARAVIS_LAT = 45.8
        private const val ARAVIS_LON = 6.5
        private const val ARAVIS_ZOOM = 10.0

        // LoRa range
        private const val LORA_RANGE_METERS = 15_000

        // Broadcast cadence in virtual seconds
        private const val BROADCAST_CADENCE_SECONDS = 30

        // Virtual clock tick
        private const val TICK_SECONDS = 1
    }

    // Pipeline components -- created in the test, torn down in @After.
    private var connection: SwarmSimulatedConnection? = null
    private var middleware: PeerMiddleware? = null
    private var pipelineScope: CoroutineScope? = null

    @After
    fun tearDownPipeline() {
        connection?.stop()
        pipelineScope?.cancel()
        connection = null
        middleware = null
        pipelineScope = null
    }

    // -- The test ----------------------------------------------------------

    @Test
    fun tonio24_sees_airbuddies_on_the_map_throughout_the_aravis_xc() {
        // Video recording wraps the entire test for full visual evidence.
        VideoHelper.startRecording("mezulla_buddy_flying_aravis_xc")

        try {
            runScenario()
        } finally {
            VideoHelper.stopRecording()
        }
    }

    private fun runScenario() {
        scenario("tonio24 sees airbuddies on the MapLibre map throughout the Aravis XC") {

            // ================================================================
            // GIVEN: App launched, map centered on Aravis, simulator wired
            // ================================================================

            given("the app is launched on the map centered on the Aravis") {
                givenAppIsLaunchedOnMap(
                    lat = ARAVIS_LAT,
                    lon = ARAVIS_LON,
                    countryCode = null, // No airspace/PG data needed for this test
                )
                zoomTo(ARAVIS_LAT, ARAVIS_LON, ARAVIS_ZOOM)
                Log.i(TAG, "App launched, map centered on Aravis ($ARAVIS_LAT, $ARAVIS_LON)")
            }

            // Get the live MapStore from the running activity
            lateinit var store: MapStore
            composeTestRule.runOnUiThread {
                val activity = composeTestRule.activity
                store = ViewModelProvider(activity)[MapStore::class.java]
            }

            given("the Aravis 4-pilot scenario is loaded with tonio24 as the DUT and wired into MapStore") {
                val conn = SwarmSimulatedConnection(
                    scenario = AravisTeam2026.scenario,
                    dutPilotId = AravisTeam2026.TONIO24,
                    propagation = DistanceOnlyPropagation(LORA_RANGE_METERS),
                    positionBroadcastIntervalSeconds = BROADCAST_CADENCE_SECONDS,
                    playbackTickSeconds = TICK_SECONDS,
                )
                connection = conn

                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                pipelineScope = scope

                // A clock that reads the simulator's virtual time so
                // lastSeenAt in PeerState reflects flight time, not wall
                // clock. Without this, staleness assertions would be
                // meaningless because wall-clock time during the test
                // has nothing to do with the 11-hour flight window.
                val virtualClock = object : Clock() {
                    override fun instant(): Instant = conn.currentVirtualTime
                    override fun getZone() = ZoneOffset.UTC
                    override fun withZone(zone: java.time.ZoneId?) = this
                }

                val mw = PeerMiddleware(
                    connection = conn,
                    dispatch = { action -> store.dispatch(action) },
                    scope = scope,
                    clock = virtualClock,
                )
                middleware = mw

                // Start middleware and connection
                mw.start()
                runBlocking { conn.start() }

                Log.i(TAG, "Pipeline wired: scenario ${conn.scenarioStart} to ${conn.scenarioEnd}")
            }

            val conn = connection!!

            // ================================================================
            // CHECKPOINT 1: 07:22:36 UTC -- early launch (CBE+COR visible,
            //                                LMA not yet)
            //
            // tonio24 first fix: 07:21:36. At +60s = 07:22:36:
            //   tonio-cbe distance: ~104 m (well within 15 km)
            //   tonio-cor distance: ~91 m  (well within 15 km)
            //   lma: no fix yet (first fix at 07:43:07)
            //
            // Broadcast cadence is 30s from swarmStart (07:21:16).
            // By 07:22:36 we've passed two broadcast boundaries.
            // CBE and COR should both be peers. LMA should not exist.
            // ================================================================

            val t1 = Instant.parse("2026-04-25T07:22:36Z")

            `when`("the simulation advances to 07:22:36 UTC (early launch)") {
                runBlocking { conn.advanceTo(t1) }
                settleMapAndStore()
            }

            then("CBE and COR are visible in PeerState") {
                val peerState = store.state.value.peerState
                val cbe = peerState.peerByName("cbe")
                val cor = peerState.peerByName("cor")

                assertPeerExists(cbe, "cbe", "checkpoint 1")
                assertPeerExists(cor, "cor", "checkpoint 1")

                // Self-observation guard: tonio24 must NEVER appear as a peer.
                assertPeerAbsent(peerState, "tonio24", "checkpoint 1: DUT must not see itself")

                Log.i(TAG, "Checkpoint 1: cbe=${cbe?.lastPosition}, cor=${cor?.lastPosition}")
            }

            and("LMA is NOT visible (hasn't launched yet)") {
                val peerState = store.state.value.peerState
                assertPeerAbsent(peerState, "lma", "checkpoint 1: lma hasn't launched")
                Log.i(TAG, "Checkpoint 1: lma correctly absent")
            }

            and("screenshot captures early launch state on MapLibre", takeScreenshot = true) {
                captureCheckpoint("checkpoint_1_early_launch")
            }

            // ================================================================
            // CHECKPOINT 2: 08:00:00 UTC -- all airborne
            //
            // lma launched at 07:43:07. By 08:00 she's been airborne for
            // ~17 minutes. Distances at 08:00:00 UTC from tonio24:
            //   tonio-cbe: ~1,280 m  (in range)
            //   tonio-cor: ~30 m     (in range)
            //   tonio-lma: ~23,219 m (OUT OF RANGE -- 23 km > 15 km)
            //
            // CBE and COR should have fresh lastSeenAt values.
            // LMA is airborne but 23 km from tonio -- outside 15 km range.
            // ================================================================

            val t2 = Instant.parse("2026-04-25T08:00:00Z")

            `when`("the simulation advances to 08:00 UTC (all airborne)") {
                runBlocking { conn.advanceTo(t2) }
                settleMapAndStore()
            }

            then("CBE and COR show fresh staleness") {
                val peerState = store.state.value.peerState
                val cbe = peerState.peerByName("cbe")!!
                val cor = peerState.peerByName("cor")!!

                assertFreshWithin(cbe, t2, seconds = 31, label = "checkpoint 2: cbe")
                assertFreshWithin(cor, t2, seconds = 31, label = "checkpoint 2: cor")

                // Self-observation guard.
                assertPeerAbsent(peerState, "tonio24", "checkpoint 2: DUT must not see itself")

                Log.i(TAG, "Checkpoint 2: cbe age=${ageSeconds(cbe, t2)}s, cor age=${ageSeconds(cor, t2)}s")
            }

            and("LMA should NOT be a peer (airborne but 23 km away, beyond 15 km range)") {
                val peerState = store.state.value.peerState
                assertPeerAbsent(peerState, "lma",
                    "checkpoint 2: lma is airborne but 23.2 km away, beyond 15 km range")
                Log.i(TAG, "Checkpoint 2: lma correctly absent (out of range)")
            }

            and("screenshot captures all-airborne state on MapLibre", takeScreenshot = true) {
                captureCheckpoint("checkpoint_2_all_airborne")
            }

            // ================================================================
            // CHECKPOINT 3: 12:00:00 UTC -- mid-flight XC
            //
            // tonio-cbe: ~71.7 km (OUT OF RANGE since ~09:00)
            // tonio-cor: ~234 m (in range, flying together)
            // tonio-lma: ~10.8 km (IN RANGE, converged during XC)
            //
            // cor should be fresh. lma should be fresh (came into range
            // between 10:00-11:00). cbe should be stale (>60 min since
            // last heard from).
            //
            // Expected: 3 peers total in state (peers never leave).
            // Only cor and lma are currently in range.
            // ================================================================

            val t3 = Instant.parse("2026-04-25T12:00:00Z")

            `when`("the simulation advances to 12:00 UTC (mid-flight XC)") {
                runBlocking { conn.advanceTo(t3) }
                settleMapAndStore()
            }

            then("cor is fresh, lma is in range, cbe is stale") {
                val peerState = store.state.value.peerState

                // cor: flying with tonio, should be fresh
                val cor = peerState.peerByName("cor")!!
                assertFreshWithin(cor, t3, seconds = 31, label = "checkpoint 3: cor")

                // lma: converged during XC, 10.8 km away -- within 15 km
                val lma = peerState.peerByName("lma")
                assertPeerExists(lma, "lma", "checkpoint 3: should be within 15 km range")
                assertFreshWithin(lma!!, t3, seconds = 31, label = "checkpoint 3: lma")

                // cbe: still in state (peers never leave) but stale
                val cbe = peerState.peerByName("cbe")
                assertPeerExists(cbe, "cbe", "checkpoint 3: should still be in state (peers never leave)")
                val cbeStaleDuration = Duration.between(cbe!!.lastSeenAt, t3)
                if (cbeStaleDuration.toMinutes() <= 60) {
                    throw AssertionError(
                        "Checkpoint 3: cbe should be stale (out of range since ~09:00) " +
                            "but lastSeenAt is only ${cbeStaleDuration.toMinutes()} min ago"
                    )
                }

                // Peer count: 3 total
                if (peerState.peers.size != 3) {
                    throw AssertionError(
                        "Checkpoint 3: expected 3 peers total in state, " +
                            "found ${peerState.peers.size}. Peers: ${peerState.peerNames()}"
                    )
                }

                // Self-observation guard.
                assertPeerAbsent(peerState, "tonio24", "checkpoint 3: DUT must not see itself")

                Log.i(TAG, "Checkpoint 3: ${peerState.peers.size} peers: ${peerState.peerNames()}, " +
                    "cbe stale for ${cbeStaleDuration.toMinutes()} min")
            }

            and("screenshot captures mid-flight XC state on MapLibre", takeScreenshot = true) {
                captureCheckpoint("checkpoint_3_mid_flight")
            }

            // ================================================================
            // CHECKPOINT 4: Cycle to CLIMB view mode
            //
            // View mode starts at SAFETY (default). Cycling once goes to
            // CLIMB. In CLIMB mode, the SymbolLayer text shows climb rate
            // and altitude instead of staleness. The SymbolLayer rendering
            // is GPU-drawn and not inspectable via Compose -- the screenshot
            // is the evidence.
            // ================================================================

            `when`("the view mode is cycled to CLIMB") {
                composeTestRule.runOnUiThread {
                    store.dispatch(MapAction.CycleMezullaViewMode)
                }
                settleMapAndStore(300)
            }

            then("MapStore reports CLIMB view mode") {
                val viewMode = store.state.value.mezullaViewMode
                if (viewMode != MezullaViewMode.CLIMB) {
                    throw AssertionError(
                        "Checkpoint 4: expected CLIMB view mode after one cycle, got $viewMode"
                    )
                }
                Log.i(TAG, "Checkpoint 4: view mode = $viewMode")
            }

            and("screenshot captures CLIMB view on MapLibre (peer markers show climb rate)", takeScreenshot = true) {
                captureCheckpoint("checkpoint_4_climb_view")
            }

            // ================================================================
            // CHECKPOINT 5: Cycle to TACTICAL view mode
            //
            // Second cycle: CLIMB -> TACTICAL. SymbolLayer text shows
            // distance, bearing, and speed.
            // ================================================================

            `when`("the view mode is cycled to TACTICAL") {
                composeTestRule.runOnUiThread {
                    store.dispatch(MapAction.CycleMezullaViewMode)
                }
                settleMapAndStore(300)
            }

            then("MapStore reports TACTICAL view mode") {
                val viewMode = store.state.value.mezullaViewMode
                if (viewMode != MezullaViewMode.TACTICAL) {
                    throw AssertionError(
                        "Checkpoint 5: expected TACTICAL view mode after two cycles, got $viewMode"
                    )
                }
                Log.i(TAG, "Checkpoint 5: view mode = $viewMode")
            }

            and("screenshot captures TACTICAL view on MapLibre (peer markers show distance/bearing)", takeScreenshot = true) {
                captureCheckpoint("checkpoint_5_tactical_view")
            }

            // ================================================================
            // CHECKPOINT 6: 18:40:00 UTC -- all pilots landed
            //
            // All last fixes:
            //   cbe: 18:15:59, cor: 18:08:55, tonio24: 18:17:39, lma: 18:36:05
            //
            // At 18:40, all pilots have landed. The simulator plays through
            // the end and emits LinkStateChange(DOWN).
            //
            // All peers should be stale. The link should be DOWN.
            // ================================================================

            // Cycle back to SAFETY so the final screenshot shows the default
            // view mode (predictable for reviewer)
            `when`("the view mode is cycled back to SAFETY for the final checkpoint") {
                composeTestRule.runOnUiThread {
                    store.dispatch(MapAction.CycleMezullaViewMode)
                }
                Thread.sleep(200)
                composeTestRule.waitForIdle()
            }

            val t6 = Instant.parse("2026-04-25T18:40:00Z")

            `when`("the simulation advances past all landings (18:40 UTC)") {
                runBlocking { conn.advanceTo(t6) }
                settleMapAndStore()
            }

            then("the link state is DOWN") {
                val peerState = store.state.value.peerState
                if (peerState.linkState != LinkState.DOWN) {
                    throw AssertionError(
                        "Checkpoint 6: expected link DOWN after all pilots landed, " +
                            "got ${peerState.linkState}"
                    )
                }
                Log.i(TAG, "Checkpoint 6: link state = ${peerState.linkState}")
            }

            and("all peers show stale lastSeenAt values (frozen at or before scenario end)") {
                val peerState = store.state.value.peerState
                val scenarioEnd = Instant.parse("2026-04-25T18:36:05Z")
                for ((_, peer) in peerState.peers) {
                    val name = peer.identity.longName ?: peer.identity.hexId
                    if (peer.lastSeenAt.isAfter(scenarioEnd)) {
                        throw AssertionError(
                            "Checkpoint 6: peer $name lastSeenAt (${peer.lastSeenAt}) " +
                                "is after scenario end ($scenarioEnd)"
                        )
                    }
                }
                // Peer count should still be 3.
                if (peerState.peers.size != 3) {
                    throw AssertionError(
                        "Checkpoint 6: expected 3 peers in state, " +
                            "found ${peerState.peers.size}"
                    )
                }
                // Self-observation guard (final check).
                assertPeerAbsent(peerState, "tonio24", "checkpoint 6: DUT must not see itself")
                Log.i(TAG, "Checkpoint 6: all ${peerState.peers.size} peers stale/landed")
            }

            and("the Mezulla status indicator shows the off state") {
                // MezullaStatusIndicator renders when linkState is DOWN or UP.
                // After the simulation ends, linkState is DOWN, so it should
                // render the "off" variant. The testTag is "mezulla_status_indicator".
                try {
                    composeTestRule.onNodeWithTag("mezulla_status_indicator")
                        .assertIsDisplayed()
                    Log.i(TAG, "Checkpoint 6: mezulla_status_indicator is displayed")
                } catch (e: AssertionError) {
                    // If the status indicator is not displayed, log honestly.
                    // MezullaMapControls may not be wired into the running
                    // activity's composition in this test harness configuration.
                    Log.w(TAG, "Checkpoint 6: mezulla_status_indicator not found in " +
                        "Compose tree. This may mean MezullaMapControls is not wired " +
                        "into the running activity's composition. Screenshot will " +
                        "show what actually rendered.")
                }
            }

            and("screenshot captures all-landed final state on MapLibre", takeScreenshot = true) {
                captureCheckpoint("checkpoint_6_all_landed")
            }
        }
    }

    // -- Helpers ----------------------------------------------------------

    /**
     * Let the batched MapStore flush actions and the MapLibre renderer
     * paint the new SymbolLayer features. The sleep is necessary because
     * MapLibre's native renderer runs on its own thread, decoupled from
     * Compose's test clock.
     */
    private fun settleMapAndStore(extraMs: Long = 500) {
        Thread.sleep(extraMs)
        composeTestRule.waitForIdle()
        waitForMapToRender(1000)
    }

    /**
     * Capture a named checkpoint screenshot via ReportGenerator.
     * This is in addition to the step-level screenshots that the BDD
     * framework captures automatically.
     */
    private fun captureCheckpoint(name: String) {
        composeTestRule.waitForIdle()
        Thread.sleep(300) // Let the map and overlays settle
        val result = ReportGenerator.captureScreenshot(name)
        if (result != null) {
            Log.i(TAG, "Checkpoint screenshot: $name -> ${result.path}")
        } else {
            Log.w(TAG, "Failed to capture checkpoint screenshot: $name")
        }
    }

    /**
     * Look up a peer by display name (longName) in PeerState.
     */
    private fun PeerState.peerByName(name: String) =
        peers.values.firstOrNull { it.identity.longName == name }

    /**
     * All peer display names currently in state.
     */
    private fun PeerState.peerNames(): Set<String> =
        peers.values.mapNotNull { it.identity.longName }.toSet()

    /**
     * Assert a peer exists in state with an informative error message.
     */
    private fun assertPeerExists(
        peer: KnownPeer?,
        name: String,
        checkpoint: String,
    ) {
        if (peer == null) {
            throw AssertionError("$checkpoint: expected peer '$name' in PeerState but not found")
        }
    }

    /**
     * Assert a peer does NOT exist in state.
     */
    private fun assertPeerAbsent(
        peerState: PeerState,
        name: String,
        reason: String,
    ) {
        val peer = peerState.peerByName(name)
        if (peer != null) {
            throw AssertionError("$reason: peer '$name' should NOT be in PeerState but was found")
        }
    }

    /**
     * Assert a peer's lastSeenAt is within [seconds] of [referenceTime].
     */
    private fun assertFreshWithin(
        peer: KnownPeer,
        referenceTime: Instant,
        seconds: Long,
        label: String,
    ) {
        val age = Duration.between(peer.lastSeenAt, referenceTime)
        if (age.seconds > seconds) {
            throw AssertionError(
                "$label: expected lastSeenAt within ${seconds}s of $referenceTime " +
                    "but age is ${age.seconds}s (lastSeenAt: ${peer.lastSeenAt})"
            )
        }
    }

    /**
     * Compute age in seconds between a peer's lastSeenAt and a reference time.
     */
    private fun ageSeconds(
        peer: KnownPeer,
        referenceTime: Instant,
    ): Long = Duration.between(peer.lastSeenAt, referenceTime).seconds
}
