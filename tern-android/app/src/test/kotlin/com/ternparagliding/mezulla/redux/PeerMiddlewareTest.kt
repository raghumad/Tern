package com.ternparagliding.mezulla.redux

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition
import com.ternparagliding.mezulla.connection.StubMeshtasticConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections

/**
 * End-to-end tests for [PeerMiddleware]: inject events on a
 * [StubMeshtasticConnection], capture the dispatched [PeerAction]s in a
 * list, assert what came through.
 *
 * Threading: the middleware's collector runs on an
 * [UnconfinedTestDispatcher] tied to the test's scheduler, parented to
 * [runTest]'s `backgroundScope`. Two reasons for this setup:
 *
 *  - **Unconfined dispatcher** → `Flow.collect` runs synchronously up to
 *    its first real suspension. That means the collector is subscribed
 *    to the stub's `MutableSharedFlow` by the time
 *    [PeerMiddleware.start] returns. Without this, events emitted before
 *    the collector subscribed would be dropped — the WS2.1 stub
 *    deliberately has no replay buffer for late subscribers.
 *  - **`backgroundScope`** → the collector coroutine is auto-cancelled
 *    when the test body ends. Otherwise `runTest` reports an
 *    "uncompleted coroutines" error because `collect` on a SharedFlow
 *    never completes on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerMiddlewareTest {

    private val antoine = PeerIdentity.fromNodeNumber(
        nodeNumber = 0xa1b2c3d4L,
        longName = "Antoine",
        shortName = "AN",
    )

    private val sampleFix = PeerPosition.Fix(
        latitudeDeg = 45.9099,
        longitudeDeg = 6.1245,
        altitudeMeters = 2400,
        groundSpeedMetersPerSecond = 9.5,
        groundTrackDegrees = 270.0,
        timestampSeconds = 1_700_000_000L,
    )

    /** Fixed clock so dispatched timestamps are predictable. */
    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-04-25T12:00:00Z"),
        ZoneOffset.UTC,
    )

    @Test
    fun `PeerPositionUpdate dispatches PeerSeen then PeerPositionReceived in order`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        conn.emit(MeshEvent.PeerPositionUpdate(antoine, sampleFix))

        assertThat(dispatched).hasSize(2)
        assertThat(dispatched[0]).isEqualTo(
            PeerAction.PeerSeen(antoine, Instant.now(fixedClock)),
        )
        assertThat(dispatched[1]).isEqualTo(
            PeerAction.PeerPositionReceived(antoine, sampleFix, Instant.now(fixedClock)),
        )
    }

    @Test
    fun `PeerTelemetry dispatches PeerSeen then PeerTelemetryReceived in order`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        conn.emit(
            MeshEvent.PeerTelemetry(
                peer = antoine,
                batteryPercent = 87,
                timestampSeconds = sampleFix.timestampSeconds,
            ),
        )

        assertThat(dispatched).hasSize(2)
        assertThat(dispatched[0]).isEqualTo(
            PeerAction.PeerSeen(antoine, Instant.now(fixedClock)),
        )
        assertThat(dispatched[1]).isEqualTo(
            PeerAction.PeerTelemetryReceived(
                identity = antoine,
                batteryPercent = 87,
                timestampSeconds = sampleFix.timestampSeconds,
                receivedAt = Instant.now(fixedClock),
            ),
        )
    }

    @Test
    fun `PeerIdentityKnown dispatches a single update-only PeerIdentityUpdate`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        conn.emit(MeshEvent.PeerIdentityKnown(antoine))

        // NodeInfo must NOT register a roster peer (PeerSeen) — only update an
        // existing one's name. This keeps the board's NodeDB dump from
        // repopulating the roster with non-teammates.
        assertThat(dispatched).containsExactly(
            PeerAction.PeerIdentityUpdate(antoine, Instant.now(fixedClock)),
        )
    }

    @Test
    fun `PeerAlert dispatches PeerAlertReceived`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        conn.emit(
            MeshEvent.PeerAlert(
                peer = antoine,
                lastKnownPosition = sampleFix,
                timestampSeconds = sampleFix.timestampSeconds + 30,
            ),
        )

        assertThat(dispatched).containsExactly(
            PeerAction.PeerAlertReceived(
                senderIdentity = antoine,
                lastKnownPosition = sampleFix,
                alertedAt = Instant.now(fixedClock),
            ),
        )
    }

    @Test
    fun `LinkStateChange dispatches LinkStateChanged with the new state`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        conn.setLinkState(LinkState.DOWN)

        assertThat(dispatched).containsExactly(
            PeerAction.LinkStateChanged(LinkState.DOWN),
        )
    }

    @Test
    fun `cancelling the collector job stops the subscription cleanly`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        val job = buildAndStart(conn, dispatched)

        // One event in; observe it arrived.
        conn.emit(MeshEvent.PeerIdentityKnown(antoine))
        assertThat(dispatched).hasSize(1)

        // Cancel the collector.
        job.cancelAndJoin()

        // Anything emitted after cancellation must not be dispatched.
        conn.emit(MeshEvent.PeerPositionUpdate(antoine, sampleFix))

        assertThat(dispatched).hasSize(1)
    }

    @Test
    fun `feeding a sequence of events delivers them in order`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        conn.emit(MeshEvent.PeerIdentityKnown(antoine))
        conn.emit(MeshEvent.PeerPositionUpdate(antoine, sampleFix))
        conn.emit(
            MeshEvent.PeerAlert(
                peer = antoine,
                lastKnownPosition = sampleFix,
                timestampSeconds = sampleFix.timestampSeconds + 5,
            ),
        )
        conn.setLinkState(LinkState.DOWN)

        val classes = dispatched.map { it::class }
        assertThat(classes).containsExactly(
            PeerAction.PeerIdentityUpdate::class,        // from PeerIdentityKnown (update-only)
            PeerAction.PeerSeen::class,                  // from PeerPositionUpdate
            PeerAction.PeerPositionReceived::class,
            PeerAction.PeerAlertReceived::class,
            PeerAction.LinkStateChanged::class,
        ).inOrder()
    }

    @Test
    fun `middleware feeds through to reducer end-to-end producing correct state`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        var state = PeerState.empty()
        val scope = collectorScope()
        val middleware = PeerMiddleware(
            connection = conn,
            dispatch = { action -> state = peerReducer(state, action) },
            scope = scope,
            clock = fixedClock,
        )
        middleware.start()

        conn.emit(MeshEvent.PeerPositionUpdate(antoine, sampleFix))
        conn.emit(
            MeshEvent.PeerAlert(
                peer = antoine,
                lastKnownPosition = sampleFix,
                timestampSeconds = sampleFix.timestampSeconds,
            ),
        )

        val recorded = state.peers.getValue(antoine.nodeNumber)
        assertThat(recorded.lastPosition).isEqualTo(sampleFix)
        assertThat(state.activeAlerts).hasSize(1)
        assertThat(state.activeAlerts.single().acknowledgedAt).isNull()
    }

    @Test
    fun `start cannot be called twice on the same middleware`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        val middleware = PeerMiddleware(
            connection = conn,
            dispatch = { action -> dispatched.add(action) },
            scope = collectorScope(),
            clock = fixedClock,
        )
        middleware.start()

        val failed = runCatching { middleware.start() }
        assertThat(failed.isFailure).isTrue()
        assertThat(failed.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    // ---- helpers ----

    /**
     * Thread-safe captured-action list. Synchronized because in some
     * dispatcher configurations the collector may resume on a different
     * thread than the test body.
     */
    private fun newDispatchedList(): MutableList<PeerAction> =
        Collections.synchronizedList(mutableListOf())

    /**
     * Scope for the middleware's collector. Parented to the test's
     * `backgroundScope` (auto-cancel at test end) and overridden to use
     * an [UnconfinedTestDispatcher] tied to the test scheduler (so
     * subscription is in place before the first `emit`).
     */
    private fun TestScope.collectorScope(): CoroutineScope =
        CoroutineScope(
            backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler),
        )

    /**
     * Build a middleware that dispatches into [dispatched] and start it
     * immediately. Returns the collector's [kotlinx.coroutines.Job] so
     * the test can cancel it explicitly when it wants to test
     * cancellation behaviour.
     */
    private fun TestScope.buildAndStart(
        conn: StubMeshtasticConnection,
        dispatched: MutableList<PeerAction>,
    ) = PeerMiddleware(
        connection = conn,
        dispatch = { action -> dispatched.add(action) },
        scope = collectorScope(),
        clock = fixedClock,
    ).start()
}
