package com.ternparagliding.sim.simulation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.MeshtasticConnection
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition
import com.ternparagliding.mezulla.redux.PeerAction
import com.ternparagliding.mezulla.redux.PeerMiddleware
import com.ternparagliding.sim.propagation.DistanceOnlyPropagation
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.SwarmPlayback
import com.ternparagliding.sim.swarm.scenarios.AravisTeam2026
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections

/**
 * Tests for [SwarmSimulatedConnection].
 *
 * The simulator is exercised end-to-end against the real Aravis IGC
 * fixtures (WS1.2): we drive virtual time forward with [advanceTo] and
 * assert the events the DUT observes.
 *
 * Coroutine plumbing follows the same pattern as
 * `PeerMiddlewareTest`: the event-collector coroutine lives on a
 * `backgroundScope`-parented [UnconfinedTestDispatcher] so subscriptions
 * are in place before any `emit` happens — the simulator's
 * `MutableSharedFlow` has no replay buffer, late subscribers see nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwarmSimulatedConnectionTest {

    private val scenario = AravisTeam2026.scenario
    private val dut: PilotId = AravisTeam2026.TONIO24

    // Per the actual IGC files (see SwarmPlaybackTest):
    //  - cbe  first fix: 07:21:16 UTC  (swarmStart -- earliest of all four)
    //  - cor  first fix: 07:21:22 UTC
    //  - tonio first fix: 07:21:36 UTC
    //  - lma  first fix: 07:43:07 UTC  (~22 min staggered launch)
    // swarmStart is min(...) = 07:21:16 UTC.
    private val swarmStart: Instant = Instant.parse("2026-04-25T07:21:16Z")

    // -- Helpers ---------------------------------------------------------

    /**
     * Snap an instant forward to the nearest broadcast boundary (in
     * 30-second cadence) at or after [target]. The simulator emits
     * broadcasts at boundaries `swarmStart + n * 30s` for n >= 1, so this
     * lets tests pick "a real cadence tick near 07:30:00" without
     * hard-coding the exact second arithmetic in every test.
     */
    private fun nextBoundaryAtOrAfter(target: Instant, intervalSec: Long = 30L): Instant {
        val sinceStart = java.time.Duration.between(swarmStart, target).seconds
        val units = (sinceStart + intervalSec - 1) / intervalSec
        val effective = if (units < 1) 1 else units
        return swarmStart.plusSeconds(effective * intervalSec)
    }

    private fun TestScope.collectorScope(): CoroutineScope =
        CoroutineScope(
            backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler),
        )

    /**
     * Subscribe a list-collector to [conn.events] and return it. The
     * caller can read the list at any point after `advanceTo` returns.
     */
    private fun TestScope.collectEvents(
        conn: SwarmSimulatedConnection,
    ): MutableList<MeshEvent> {
        val out = Collections.synchronizedList(mutableListOf<MeshEvent>())
        collectorScope().launch {
            conn.events().toList(out)
        }
        return out
    }

    private fun newConnection(
        rangeMeters: Int = SwarmSimulatedConnection.DEFAULT_RANGE_METERS,
        intervalSec: Int = SwarmSimulatedConnection.DEFAULT_POSITION_INTERVAL_SECONDS,
        tickSec: Int = SwarmSimulatedConnection.DEFAULT_TICK_SECONDS,
    ): SwarmSimulatedConnection = SwarmSimulatedConnection(
        scenario = scenario,
        dutPilotId = dut,
        propagation = DistanceOnlyPropagation(rangeMeters),
        positionBroadcastIntervalSeconds = intervalSec,
        playbackTickSeconds = tickSec,
        clock = Clock.fixed(Instant.parse("2026-04-25T00:00:00Z"), ZoneOffset.UTC),
    )

    // -- Sanity ----------------------------------------------------------

    @Test
    fun `scenario start matches the earliest first fix across the swarm`() = runTest {
        val conn = newConnection()
        assertThat(conn.scenarioStart).isEqualTo(swarmStart)

        // SwarmPlayback exposes the same value; the simulator must
        // share that anchor so cadence math agrees with playback math.
        val playback = SwarmPlayback(scenario)
        assertThat(conn.scenarioStart).isEqualTo(playback.swarmStart)
    }

    @Test
    fun `link state UP is the first event emitted by start`() = runTest {
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()

        assertThat(events).hasSize(1)
        assertThat(events.first()).isEqualTo(
            MeshEvent.LinkStateChange(LinkState.UP),
        )
        assertThat(conn.linkState).isEqualTo(LinkState.UP)
    }

    // -- Smoke against the Aravis scenario -------------------------------

    @Test
    fun `smoke - at 11 UTC tonio and lma are 6km apart, in-range lma broadcast is delivered`() = runTest {
        // Reference computed from the raw IGC files: at 11:00:00 UTC
        // tonio24 and lma are about 6.3 km apart (well inside the
        // default 15 km range). lma is airborne. So the simulator at a
        // broadcast boundary near 11:00 must emit a PeerPositionUpdate
        // for lma.
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()

        val target = nextBoundaryAtOrAfter(Instant.parse("2026-04-25T11:00:00Z"))
        conn.advanceTo(target)

        val lmaPositionEvents = events.filterIsInstance<MeshEvent.PeerPositionUpdate>()
            .filter { it.peer.longName == "lma" }
        assertWithMessage("expected at least one delivered lma position event at $target")
            .that(lmaPositionEvents).isNotEmpty()
    }

    @Test
    fun `smoke - at 845 UTC tonio and lma are 35km apart, no lma broadcast is delivered`() = runTest {
        // Reference computed from the raw IGC files: tonio-lma is >= 35
        // km from 08:38:19 onward for thousands of consecutive seconds.
        // At a single boundary inside that window the 15 km propagation
        // model must drop every lma broadcast (silent loss).
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()

        // Pick 08:45:00 -- comfortably inside the 35+ km window
        val target = nextBoundaryAtOrAfter(Instant.parse("2026-04-25T08:45:00Z"))
        conn.advanceTo(target)

        val lmaEvents = events.filterIsInstance<MeshEvent.PeerPositionUpdate>()
            .filter { it.peer.longName == "lma" }
        // At this instant lma is airborne but out of range -- no event.
        assertThat(lmaEvents).isEmpty()
    }

    // -- DUT silence -----------------------------------------------------

    @Test
    fun `the DUT never appears as a peer in its own event flow`() = runTest {
        // Walk a healthy slice of the morning -- 10 minutes from
        // swarmStart at 30s cadence = 20 broadcast boundaries. The DUT
        // (tonio24) must never appear in PeerPositionUpdate or
        // PeerIdentityKnown.
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()
        conn.advanceTo(swarmStart.plusSeconds(600))

        val dutName = scenario.pilot(dut)!!.displayName
        val dutMentioned = events.any { ev ->
            when (ev) {
                is MeshEvent.PeerPositionUpdate -> ev.peer.longName == dutName
                is MeshEvent.PeerIdentityKnown -> ev.peer.longName == dutName
                else -> false
            }
        }
        assertWithMessage("DUT '$dutName' must not appear as a peer to itself")
            .that(dutMentioned).isFalse()
    }

    // -- Pre-launch silence ----------------------------------------------

    @Test
    fun `at 0730 UTC lma is still on the ground and emits no position event`() = runTest {
        // lma's first fix is 07:43:07 UTC. tonio/cbe/cor are all
        // airborne by 07:30. The 15 km filter would deliver lma if she
        // had a fix (she'd be at the launch site, ~28 km away from
        // tonio, but the rule is even simpler: she's pre-launch so
        // SwarmPlayback returns null and the simulator emits nothing).
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()

        // Pick a boundary on either side of 07:30:00. 07:29:46 and
        // 07:30:16 are both broadcast boundaries (relative to a
        // swarmStart of 07:21:16 -- 17 * 30s = 510s -> 07:29:46).
        conn.advanceTo(Instant.parse("2026-04-25T07:30:46Z"))

        val lmaEvents = events.filterIsInstance<MeshEvent.PeerPositionUpdate>()
            .filter { it.peer.longName == "lma" }
        assertThat(lmaEvents).isEmpty()

        // Sanity: at least one of the other pilots emitted a position
        // in the same window, so we know the simulator is actually
        // ticking and broadcasting.
        val otherEvents = events.filterIsInstance<MeshEvent.PeerPositionUpdate>()
            .filter { it.peer.longName != "lma" }
        assertWithMessage("expected at least one non-lma peer broadcast in the morning window")
            .that(otherEvents).isNotEmpty()
    }

    // -- Post-landing silence --------------------------------------------

    @Test
    fun `after cor lands no further cor events are emitted`() = runTest {
        // cor lands at 18:08:55 UTC (last fix). Walk to a boundary
        // ~3 minutes after that and assert no cor events on the trailing
        // window. We intentionally measure on a small post-landing slice
        // -- not the whole rest of the scenario -- to keep the test fast.
        val conn = newConnection(intervalSec = 30, tickSec = 30)
        val events = collectEvents(conn)
        conn.start()

        val corLast = Instant.parse("2026-04-25T18:08:55Z")
        val postCorStart = nextBoundaryAtOrAfter(corLast.plusSeconds(60))
        // Walk to just before postCorStart first, then sample a 3-minute
        // post-landing window for cor events.
        conn.advanceTo(postCorStart)
        val sizeAtCorPostLanding = events.size

        conn.advanceTo(postCorStart.plusSeconds(180))
        val window = events.subList(sizeAtCorPostLanding, events.size)

        val corPostLanding = window.filterIsInstance<MeshEvent.PeerPositionUpdate>()
            .filter { it.peer.longName == "cor" }
        assertThat(corPostLanding).isEmpty()
    }

    // -- PeerIdentityKnown is emitted at most once per peer --------------

    @Test
    fun `PeerIdentityKnown is emitted at most once per peer per session`() = runTest {
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()
        // 30 minutes of morning at 30s cadence: every non-DUT peer
        // who has come in range will be heard from many times. The
        // identity event must still be unique per peer.
        conn.advanceTo(swarmStart.plusSeconds(30 * 60))

        val identityCounts = events
            .filterIsInstance<MeshEvent.PeerIdentityKnown>()
            .groupingBy { it.peer.nodeNumber }
            .eachCount()

        for ((nodeNumber, count) in identityCounts) {
            assertWithMessage("PeerIdentityKnown count for $nodeNumber")
                .that(count).isEqualTo(1)
        }
    }

    // -- Cadence: 90s @ 30s = 3 broadcasts per in-range peer -------------

    @Test
    fun `90 seconds of virtual time at 30s cadence yields exactly 3 broadcasts per in-range peer`() = runTest {
        // At swarmStart cbe and cor are already a couple of km from
        // tonio (well in range). lma is pre-launch. Walking 90s of
        // virtual time covers cadence boundaries 30, 60, 90 -- the
        // boundary at the start (t=0) is deliberately not a broadcast
        // moment (the link just came up; pilots' boards do not all
        // transmit at that instant). So we expect exactly 3 broadcasts
        // each from cbe and cor and 0 from lma.
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()
        conn.advanceTo(swarmStart.plusSeconds(90))

        val updates = events.filterIsInstance<MeshEvent.PeerPositionUpdate>()
        val byPilot = updates.groupingBy { it.peer.longName }.eachCount()

        assertThat(byPilot["cbe"]).isEqualTo(3)
        assertThat(byPilot["cor"]).isEqualTo(3)
        // lma is pre-launch through the entire window.
        assertThat(byPilot["lma"]).isNull()
    }

    // -- Range threshold -------------------------------------------------

    @Test
    fun `at 0841 UTC tonio and cbe are 14km apart - cbe broadcast is delivered`() = runTest {
        // Computed from the raw IGC files: tonio-cbe at 08:41:39 is
        // about 13.5 km apart -- inside the 15 km range. So a cbe
        // broadcast at the nearest boundary must be delivered.
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()

        val target = nextBoundaryAtOrAfter(Instant.parse("2026-04-25T08:41:39Z"))
        conn.advanceTo(target)

        val cbeEvents = events.filterIsInstance<MeshEvent.PeerPositionUpdate>()
            .filter { it.peer.longName == "cbe" }
        assertWithMessage("expected at least one cbe position event around $target")
            .that(cbeEvents).isNotEmpty()
    }

    @Test
    fun `at 0838 UTC tonio and lma are 35km apart - lma broadcast is lost silently`() = runTest {
        // tonio-lma is >= 35 km from 08:38:19 onward. Walk to the
        // first boundary at or after that time and assert lma is
        // silent at this boundary.
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()

        val target = nextBoundaryAtOrAfter(Instant.parse("2026-04-25T08:38:19Z"))
        // Walk only to the chosen boundary (not past it) so a later
        // moment that happens to be in range doesn't muddy the assertion.
        conn.advanceTo(target)

        val lmaEvents = events.filterIsInstance<MeshEvent.PeerPositionUpdate>()
            .filter { it.peer.longName == "lma" && it.fix.timestampSeconds * 1000 <= target.toEpochMilli() }
        // The slice up to target.toEpochMilli is what matters for the
        // "this exact boundary lost the packet" assertion.
        assertThat(lmaEvents).isEmpty()
    }

    // -- DUT send-side ---------------------------------------------------

    @Test
    fun `sendOwnPosition records the fix and does not feed it back as an event`() = runTest {
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()

        val fix = PeerPosition.Fix(
            latitudeDeg = 45.85,
            longitudeDeg = 6.35,
            altitudeMeters = 1500,
            groundSpeedMetersPerSecond = 10.0,
            groundTrackDegrees = 90.0,
            timestampSeconds = swarmStart.epochSecond,
        )
        conn.sendOwnPosition(fix)

        assertThat(conn.sentPositions).containsExactly(fix)
        val positionEventsAfterSend = events.filterIsInstance<MeshEvent.PeerPositionUpdate>()
        assertThat(positionEventsAfterSend).isEmpty()
    }

    @Test
    fun `sendAlert returns Acked when link is UP and records the call`() = runTest {
        val conn = newConnection()
        collectEvents(conn) // subscribe so events flow doesn't block
        conn.start()

        val result = conn.sendAlert(lastKnownPosition = null)

        assertThat(result).isEqualTo(MeshtasticConnection.SendResult.Acked)
        assertThat(conn.sentAlerts).hasSize(1)
        assertThat(conn.sentAlerts.single()).isNull()
    }

    @Test
    fun `sendOwnPosition is silent when link state is not UP`() = runTest {
        val conn = newConnection()
        // No start() -- link is still NEVER_PAIRED.
        val fix = PeerPosition.Fix(
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            altitudeMeters = null,
            groundSpeedMetersPerSecond = null,
            groundTrackDegrees = null,
            timestampSeconds = 0L,
        )
        conn.sendOwnPosition(fix)
        assertThat(conn.sentPositions).isEmpty()
    }

    // -- End of scenario emits LinkState DOWN ----------------------------

    @Test
    fun `LinkStateChange DOWN is emitted once the scenario plays past its end`() = runTest {
        // Use a coarse synthetic-friendly tick so we don't iterate
        // 11 hours of 1-second ticks. 60-second ticks at 60s cadence
        // still hit every cadence boundary.
        val conn = newConnection(intervalSec = 60, tickSec = 60)
        val events = collectEvents(conn)
        conn.start()
        // lma's last fix is 18:36:05; pad past that to force the end.
        conn.advanceTo(Instant.parse("2026-04-25T19:00:00Z"))

        val linkChanges = events.filterIsInstance<MeshEvent.LinkStateChange>()
        // Must include UP first and DOWN last.
        assertThat(linkChanges.first().newState).isEqualTo(LinkState.UP)
        assertThat(linkChanges.last().newState).isEqualTo(LinkState.DOWN)
        assertThat(conn.linkState).isEqualTo(LinkState.DOWN)
    }

    @Test
    fun `advancing past end is idempotent - DOWN emitted only once`() = runTest {
        val conn = newConnection(intervalSec = 60, tickSec = 60)
        val events = collectEvents(conn)
        conn.start()
        conn.advanceTo(Instant.parse("2026-04-25T19:00:00Z"))
        conn.advanceTo(Instant.parse("2026-04-25T20:00:00Z"))

        val downs = events.filterIsInstance<MeshEvent.LinkStateChange>()
            .filter { it.newState == LinkState.DOWN }
        assertThat(downs).hasSize(1)
    }

    // -- End-to-end with PeerMiddleware ----------------------------------

    @Test
    fun `end-to-end - simulator drives PeerMiddleware which captures expected actions`() = runTest {
        // Walk 60 seconds of morning: cadence boundaries at swarmStart+30
        // and swarmStart+60. cbe and cor are in range (~2 km).
        // Expected action sequence at each boundary, per pilot:
        //  - first time:  PeerIdentityKnown -> PeerIdentityUpdate (update-only),
        //                 PeerPositionUpdate -> PeerSeen + PeerPositionReceived
        //  - thereafter:  PeerPositionUpdate -> PeerSeen + PeerPositionReceived
        // So per in-range peer over 60s:
        //  1 PeerIdentityKnown -> 1 PeerIdentityUpdate (does NOT register)
        //  2 PeerPositionUpdates -> 2x (PeerSeen + PeerPositionReceived)
        // = 2 PeerSeen, 2 PeerPositionReceived, 1 PeerIdentityUpdate per peer.
        // Plus 1 LinkStateChanged(UP) from start().

        val conn = newConnection()
        val captured = Collections.synchronizedList(mutableListOf<PeerAction>())
        val fixedClock = Clock.fixed(
            Instant.parse("2026-04-25T07:21:16Z"),
            ZoneOffset.UTC,
        )
        val middleware = PeerMiddleware(
            connection = conn,
            dispatch = { captured.add(it) },
            scope = collectorScope(),
            clock = fixedClock,
        )
        middleware.start()
        conn.start()
        conn.advanceTo(swarmStart.plusSeconds(60))

        val seenCounts = captured.filterIsInstance<PeerAction.PeerSeen>()
            .groupingBy { it.identity.longName }
            .eachCount()
        val posCounts = captured.filterIsInstance<PeerAction.PeerPositionReceived>()
            .groupingBy { it.identity.longName }
            .eachCount()

        assertThat(seenCounts["cbe"]).isEqualTo(2)
        assertThat(seenCounts["cor"]).isEqualTo(2)
        assertThat(posCounts["cbe"]).isEqualTo(2)
        assertThat(posCounts["cor"]).isEqualTo(2)

        // Sanity: first action should be LinkStateChanged(UP).
        assertThat(captured.first()).isEqualTo(
            PeerAction.LinkStateChanged(LinkState.UP),
        )

        // Sanity: ordering within a boundary -- the very first peer event for
        // cbe is the update-only PeerIdentityUpdate (from PeerIdentityKnown),
        // then the live PeerSeen + PeerPositionReceived.
        val cbeActions = captured.filter { action ->
            when (action) {
                is PeerAction.PeerIdentityUpdate -> action.identity.longName == "cbe"
                is PeerAction.PeerSeen -> action.identity.longName == "cbe"
                is PeerAction.PeerPositionReceived -> action.identity.longName == "cbe"
                else -> false
            }
        }
        assertThat(cbeActions[0]).isInstanceOf(PeerAction.PeerIdentityUpdate::class.java)
        assertThat(cbeActions[1]).isInstanceOf(PeerAction.PeerSeen::class.java)
        assertThat(cbeActions[2]).isInstanceOf(PeerAction.PeerPositionReceived::class.java)
    }

    // -- Peer identity stability ----------------------------------------

    @Test
    fun `the same peer keeps the same node number across multiple broadcasts`() = runTest {
        val conn = newConnection()
        val events = collectEvents(conn)
        conn.start()
        conn.advanceTo(swarmStart.plusSeconds(180))

        // For each peer, every broadcast must show the same nodeNumber
        // and same hexId; redux keys depend on this.
        val byName: Map<String?, List<PeerIdentity>> = events
            .filterIsInstance<MeshEvent.PeerPositionUpdate>()
            .map { it.peer }
            .groupBy { it.longName }

        for ((name, identities) in byName) {
            val nodeNumbers = identities.map { it.nodeNumber }.toSet()
            val hexes = identities.map { it.hexId }.toSet()
            assertWithMessage("nodeNumber must be stable for $name")
                .that(nodeNumbers).hasSize(1)
            assertWithMessage("hexId must be stable for $name")
                .that(hexes).hasSize(1)
        }
    }

    // -- Speed multiplier ------------------------------------------------

    @Test
    fun `speed multiplier does not affect event count - only wall-clock timing`() = runTest {
        // Walk 90 seconds of virtual time at 30s cadence with speed=1
        // and speed=64. Both must produce the same number and type of
        // events. The multiplier only affects the delay inside
        // driveRealtime(); advanceTo() is unaffected.
        val conn1x = newConnection()
        val events1x = collectEvents(conn1x)
        conn1x.start()
        conn1x.advanceTo(swarmStart.plusSeconds(90))

        val conn64x = SwarmSimulatedConnection(
            scenario = scenario,
            dutPilotId = dut,
            propagation = DistanceOnlyPropagation(SwarmSimulatedConnection.DEFAULT_RANGE_METERS),
            positionBroadcastIntervalSeconds = SwarmSimulatedConnection.DEFAULT_POSITION_INTERVAL_SECONDS,
            playbackTickSeconds = SwarmSimulatedConnection.DEFAULT_TICK_SECONDS,
            speedMultiplier = 64,
            clock = Clock.fixed(Instant.parse("2026-04-25T00:00:00Z"), ZoneOffset.UTC),
        )
        val events64x = collectEvents(conn64x)
        conn64x.start()
        conn64x.advanceTo(swarmStart.plusSeconds(90))

        // Same event types in same order.
        assertThat(events64x.map { it::class }).isEqualTo(events1x.map { it::class })
        // Same total count.
        assertThat(events64x).hasSize(events1x.size)
    }

    @Test
    fun `construction rejects non-positive speed multiplier`() {
        val failZero = runCatching {
            SwarmSimulatedConnection(
                scenario = scenario,
                dutPilotId = dut,
                speedMultiplier = 0,
            )
        }
        assertThat(failZero.isFailure).isTrue()
        assertThat(failZero.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)

        val failNeg = runCatching {
            SwarmSimulatedConnection(
                scenario = scenario,
                dutPilotId = dut,
                speedMultiplier = -1,
            )
        }
        assertThat(failNeg.isFailure).isTrue()
        assertThat(failNeg.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // -- Construction validation ----------------------------------------

    @Test
    fun `construction rejects a DUT id that is not in the scenario`() {
        val failure = runCatching {
            SwarmSimulatedConnection(
                scenario = scenario,
                dutPilotId = PilotId("not-in-scenario"),
            )
        }
        assertThat(failure.isFailure).isTrue()
        assertThat(failure.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `construction rejects an interval not divisible by the tick`() {
        val failure = runCatching {
            SwarmSimulatedConnection(
                scenario = scenario,
                dutPilotId = dut,
                positionBroadcastIntervalSeconds = 30,
                playbackTickSeconds = 7,
            )
        }
        assertThat(failure.isFailure).isTrue()
        assertThat(failure.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
