package com.madanala.tern.test.convergence

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.madanala.tern.mezulla.connection.LinkState
import com.madanala.tern.mezulla.redux.KnownPeer
import com.madanala.tern.mezulla.redux.PeerAction
import com.madanala.tern.mezulla.redux.PeerMiddleware
import com.madanala.tern.mezulla.redux.PeerState
import com.madanala.tern.mezulla.redux.peerReducer
import com.madanala.tern.sim.propagation.DistanceOnlyPropagation
import com.madanala.tern.sim.simulation.SwarmSimulatedConnection
import com.madanala.tern.sim.swarm.scenarios.AravisTeam2026
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Buddy-flying convergence smoke test.
 *
 * This is the first end-to-end integration test that wires the FULL
 * pipeline against real flight data:
 *
 *   IGC files (Aravis 2026-04-25, 4 pilots)
 *     -> SwarmPlayback (time-aligned multi-pilot replay)
 *       -> SwarmSimulatedConnection (virtual LoRa with 15 km range)
 *         -> PeerMiddleware (mesh events to redux actions)
 *           -> PeerReducer (actions to PeerState)
 *
 * The test advances virtual time to specific checkpoints and asserts
 * against PeerState at each one. No UI, no screenshots -- just the
 * data path. If this passes, the pipeline is wired correctly against
 * real flight geometry.
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
 *   swarmStart = min(all first fixes) = 07:21:16 UTC (cbe)
 *   swarmEnd   = max(all last fixes)  = 18:36:05 UTC (lma)
 *
 * The DUT is tonio24. Propagation: DistanceOnlyPropagation(15,000 m).
 * Broadcast cadence: 30 seconds (the default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuddyFlyingSmokeTest {

    // -- Pipeline wiring helpers ------------------------------------------

    /**
     * The full pipeline, wired and ready for [advanceAndSnapshot].
     *
     * PeerMiddleware feeds dispatched actions into [peerReducer],
     * accumulating state in [peerState]. The middleware's clock is fixed
     * at the connection's current virtual time at each checkpoint so
     * that lastSeenAt values are predictable and testable.
     *
     * We use a ticking clock that returns the simulator's virtualClock
     * at the moment each event is handled, so lastSeenAt reflects
     * virtual time, not wall-clock time. This is important: without it,
     * lastSeenAt would be the test-machine's wall clock, which is
     * meaningless for staleness assertions.
     */
    private class Pipeline(testScope: TestScope) {
        val connection = SwarmSimulatedConnection(
            scenario = AravisTeam2026.scenario,
            dutPilotId = AravisTeam2026.TONIO24,
            propagation = DistanceOnlyPropagation(15_000),
            positionBroadcastIntervalSeconds = 30,
            playbackTickSeconds = 1,
        )

        var peerState: PeerState = PeerState.empty()
            private set

        // Clock that reads the simulator's virtual time directly.
        // When the middleware calls Instant.now(clock) to timestamp
        // an event, it gets the simulator's current virtual instant,
        // which is the tick the event was emitted at. This makes
        // lastSeenAt reflect virtual time, not wall-clock time.
        private val virtualClock: Clock = object : Clock() {
            override fun instant(): Instant = connection.currentVirtualTime
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        }

        private val collectorScope: CoroutineScope = CoroutineScope(
            testScope.backgroundScope.coroutineContext +
                UnconfinedTestDispatcher(testScope.testScheduler),
        )

        private val middleware = PeerMiddleware(
            connection = connection,
            dispatch = { action ->
                peerState = peerReducer(peerState, action)
            },
            scope = collectorScope,
            clock = virtualClock,
        )

        suspend fun startAndAdvanceTo(target: Instant): PeerState {
            middleware.start()
            connection.start()
            connection.advanceTo(target)
            return peerState
        }

        suspend fun advanceTo(target: Instant): PeerState {
            connection.advanceTo(target)
            return peerState
        }
    }

    // -- Helpers -----------------------------------------------------------

    private val swarmStart = Instant.parse("2026-04-25T07:21:16Z")

    /**
     * Look up a peer by display name (longName) in the current state.
     * Returns null if the peer is not in the map.
     */
    private fun PeerState.peerByName(name: String): KnownPeer? =
        peers.values.firstOrNull { it.identity.longName == name }

    /**
     * All peer display names currently in state.
     */
    private fun PeerState.peerNames(): Set<String> =
        peers.values.mapNotNull { it.identity.longName }.toSet()

    // -- The test ---------------------------------------------------------

    @Test
    fun `tonio24 sees airbuddies throughout the Aravis XC and peers go stale after landing`() = runTest {
        val pipeline = Pipeline(this)

        // ============================================================
        // CHECKPOINT 1: T = tonio24 launch + 60s (07:22:36 UTC)
        //
        // tonio24 first fix: 07:21:36. At +60s = 07:22:36:
        //   tonio-cbe distance: ~104 m (well within 15 km)
        //   tonio-cor distance: ~91 m  (well within 15 km)
        //   lma: no fix yet (first fix at 07:43:07) -- pre-launch
        //
        // Broadcast cadence is 30s from swarmStart (07:21:16).
        // First boundary: swarmStart + 30s = 07:21:46
        // Second boundary: swarmStart + 60s = 07:22:16
        // By 07:22:36 we've passed two broadcast boundaries.
        // CBE and COR should both be peers. LMA should not exist.
        // ============================================================
        val t1 = Instant.parse("2026-04-25T07:22:36Z")
        val state1 = pipeline.startAndAdvanceTo(t1)

        assertWithMessage("checkpoint 1: link should be UP")
            .that(state1.linkState).isEqualTo(LinkState.UP)

        assertWithMessage("checkpoint 1: cbe should be a peer (104 m away)")
            .that(state1.peerByName("cbe")).isNotNull()

        assertWithMessage("checkpoint 1: cor should be a peer (91 m away)")
            .that(state1.peerByName("cor")).isNotNull()

        assertWithMessage("checkpoint 1: lma should NOT be a peer (hasn't launched)")
            .that(state1.peerByName("lma")).isNull()

        // Self-observation guard: tonio24 must NEVER appear as a peer.
        assertWithMessage("checkpoint 1: DUT (tonio24) must not see itself")
            .that(state1.peerByName("tonio24")).isNull()

        // ============================================================
        // CHECKPOINT 2: T = 08:00:00 UTC (all 4 pilots airborne)
        //
        // lma launched at 07:43:07, so she's airborne by 08:00.
        // Distances at 08:00:00 UTC from tonio24:
        //   tonio-cbe: ~1,280 m  (in range)
        //   tonio-cor: ~30 m     (in range)
        //   tonio-lma: ~23,219 m (OUT OF RANGE -- 23 km > 15 km)
        //
        // LMA is airborne but 23 km away. With a 15 km range limit
        // she should NOT appear as a peer yet.
        // ============================================================
        val t2 = Instant.parse("2026-04-25T08:00:00Z")
        val state2 = pipeline.advanceTo(t2)

        assertWithMessage("checkpoint 2: cbe should be a peer (1.3 km away)")
            .that(state2.peerByName("cbe")).isNotNull()

        assertWithMessage("checkpoint 2: cor should be a peer (30 m away)")
            .that(state2.peerByName("cor")).isNotNull()

        // lma is airborne but 23 km from tonio -- outside 15 km range.
        // She should not have appeared in PeerState at all yet, because
        // no broadcast from her has ever been within range.
        assertWithMessage(
            "checkpoint 2: lma should NOT be a peer " +
                "(airborne but 23.2 km away, beyond 15 km range)"
        ).that(state2.peerByName("lma")).isNull()

        // Freshness check: cbe and cor should have been heard from
        // recently. The last broadcast boundary before 08:00:00 is
        // at most 30 seconds earlier.
        val cbe2 = state2.peerByName("cbe")!!
        val cor2 = state2.peerByName("cor")!!
        assertFreshWithin(cbe2, t2, seconds = 30, label = "checkpoint 2: cbe")
        assertFreshWithin(cor2, t2, seconds = 30, label = "checkpoint 2: cor")

        // Self-observation guard.
        assertWithMessage("checkpoint 2: DUT (tonio24) must not see itself")
            .that(state2.peerByName("tonio24")).isNull()

        // ============================================================
        // CHECKPOINT 3: T = 12:00:00 UTC (mid-flight XC)
        //
        // Distances at 12:00:00 UTC from tonio24:
        //   tonio-cbe: ~71,738 m (OUT OF RANGE -- they split ~09:00)
        //   tonio-cor: ~234 m    (in range -- flying together)
        //   tonio-lma: ~10,826 m (IN RANGE -- converged during XC)
        //
        // By this point tonio and cor have been flying together the
        // whole time. cbe split away after ~08:40 (went > 15 km) and
        // won't return until ~17:00. lma came into range sometime
        // between 10:00 and 11:00.
        //
        // Expected peers visible: cor and lma.
        // cbe is still in state (peers never leave) but her lastSeenAt
        // is frozen near the time she went out of range (~08:40ish).
        // ============================================================
        val t3 = Instant.parse("2026-04-25T12:00:00Z")
        val state3 = pipeline.advanceTo(t3)

        assertWithMessage("checkpoint 3: cor should be a peer (234 m away)")
            .that(state3.peerByName("cor")).isNotNull()
        assertWithMessage("checkpoint 3: cor should be fresh")
            .that(state3.peerByName("cor")!!.lastSeenAt)
            .isGreaterThan(t3.minusSeconds(31))

        // lma is 10.8 km from tonio at 12:00 -- well within 15 km.
        assertWithMessage("checkpoint 3: lma should be a peer (10.8 km away, within 15 km)")
            .that(state3.peerByName("lma")).isNotNull()
        assertWithMessage("checkpoint 3: lma should be fresh")
            .that(state3.peerByName("lma")!!.lastSeenAt)
            .isGreaterThan(t3.minusSeconds(31))

        // cbe is still in state (peers never get removed), but stale.
        // She went out of range around 09:00. Her lastSeenAt should
        // be frozen well before 12:00.
        assertWithMessage("checkpoint 3: cbe should still be in state (peers never leave)")
            .that(state3.peerByName("cbe")).isNotNull()
        val cbe3 = state3.peerByName("cbe")!!
        val cbeStaleDuration = Duration.between(cbe3.lastSeenAt, t3)
        assertWithMessage(
            "checkpoint 3: cbe should be stale (out of range since ~09:00, " +
                "lastSeenAt frozen ${cbeStaleDuration.toMinutes()} min ago)"
        ).that(cbeStaleDuration.toMinutes()).isGreaterThan(60)

        // Peer count: 3 peers total (cbe, cor, lma) -- even though
        // only cor and lma are currently in range.
        assertWithMessage("checkpoint 3: should have 3 peers total in state")
            .that(state3.peers).hasSize(3)

        // Self-observation guard.
        assertWithMessage("checkpoint 3: DUT (tonio24) must not see itself")
            .that(state3.peerByName("tonio24")).isNull()

        // ============================================================
        // CHECKPOINT 4: T = 18:10:00 UTC (after COR's landing)
        //
        // COR's last fix: 18:08:55 UTC. By 18:10:00 COR has landed
        // and stopped producing IGC fixes. The simulator returns null
        // for COR's position after 18:08:55, so no more broadcasts
        // from COR reach the DUT.
        //
        // COR should still be in PeerState (peers never get removed)
        // but her lastSeenAt should be frozen near her landing time.
        // She should NOT have received any new position updates after
        // her last fix.
        //
        // At 18:00:00 (before COR landed):
        //   tonio-cbe: ~162 m (IN RANGE -- they reconverged late)
        //   tonio-cor: ~4,810 m (IN RANGE)
        //   tonio-lma: ~25,814 m (OUT OF RANGE)
        // ============================================================
        val t4 = Instant.parse("2026-04-25T18:10:00Z")
        val state4 = pipeline.advanceTo(t4)

        // COR is still tracked but stale. Her lastSeenAt should be
        // near her landing time, not near 18:10.
        val cor4 = state4.peerByName("cor")!!
        assertWithMessage("checkpoint 4: cor should still be in state after landing")
            .that(cor4).isNotNull()

        val corStaleness = Duration.between(cor4.lastSeenAt, t4)
        assertWithMessage(
            "checkpoint 4: cor's lastSeenAt should be frozen near landing " +
                "(18:08:55), not near 18:10. Staleness = ${corStaleness.seconds}s"
        ).that(corStaleness.seconds).isGreaterThan(30)

        // cbe should be fresh (reconverged with tonio around 17:00,
        // distance ~162 m at 18:00).
        val cbe4 = state4.peerByName("cbe")!!
        assertWithMessage("checkpoint 4: cbe should be fresh (back in range)")
            .that(cbe4.lastSeenAt).isGreaterThan(t4.minusSeconds(31))

        // Self-observation guard.
        assertWithMessage("checkpoint 4: DUT (tonio24) must not see itself")
            .that(state4.peerByName("tonio24")).isNull()

        // ============================================================
        // CHECKPOINT 5: T = past end of scenario (after 18:36:05)
        //
        // lma's last fix is 18:36:05 -- the latest of all four pilots.
        // After that, the simulator has played through its entire
        // window and emits LinkStateChange(DOWN).
        //
        // All peers should be stale (no fresh broadcasts). The link
        // should be DOWN.
        //
        // Advance past the end to trigger the closing event.
        // ============================================================
        val t5 = Instant.parse("2026-04-25T19:00:00Z")
        val state5 = pipeline.advanceTo(t5)

        assertWithMessage("checkpoint 5: link should be DOWN after scenario ends")
            .that(state5.linkState).isEqualTo(LinkState.DOWN)

        // All peers should be stale. "Stale" means lastSeenAt is more
        // than 30 seconds before the scenario end (18:36:05).
        val scenarioEnd = Instant.parse("2026-04-25T18:36:05Z")
        for ((_, peer) in state5.peers) {
            val name = peer.identity.longName ?: peer.identity.hexId
            // tonio24's last fix is 18:17:39. cbe is 18:15:59.
            // cor is 18:08:55. lma is 18:36:05.
            // After 18:36:05, no one is broadcasting anymore.
            assertWithMessage(
                "checkpoint 5: peer $name lastSeenAt should be at or before scenario end"
            ).that(peer.lastSeenAt).isAtMost(scenarioEnd)
        }

        // Peer count should still be 3 (no peer is ever removed).
        assertWithMessage("checkpoint 5: should still have 3 peers")
            .that(state5.peers).hasSize(3)

        // Self-observation guard (final check).
        assertWithMessage("checkpoint 5: DUT (tonio24) must not see itself")
            .that(state5.peerByName("tonio24")).isNull()
    }

    // -- Assertion helpers ------------------------------------------------

    /**
     * Assert that [peer]'s lastSeenAt is within [seconds] of [referenceTime].
     * This checks freshness: a peer who was heard from recently has a
     * lastSeenAt close to the current virtual time.
     */
    private fun assertFreshWithin(
        peer: KnownPeer,
        referenceTime: Instant,
        seconds: Long,
        label: String,
    ) {
        val age = Duration.between(peer.lastSeenAt, referenceTime)
        assertWithMessage(
            "$label: lastSeenAt should be within ${seconds}s of $referenceTime " +
                "(actual age: ${age.seconds}s, lastSeenAt: ${peer.lastSeenAt})"
        ).that(age.seconds).isAtMost(seconds)
    }
}
