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

    /** "Now" for the fixed test clock below — used to keep [sampleFix] fresh. */
    private val nowInstant = Instant.parse("2026-04-25T12:00:00Z")

    private val sampleFix = PeerPosition.Fix(
        latitudeDeg = 45.9099,
        longitudeDeg = 6.1245,
        altitudeMeters = 2400,
        groundSpeedMetersPerSecond = 9.5,
        groundTrackDegrees = 270.0,
        // Fresh relative to the fixed clock so it counts as live presence (the
        // middleware now drops stale-on-arrival positions as nodeDB replays).
        timestampSeconds = nowInstant.epochSecond,
    )

    /** Fixed clock so dispatched timestamps are predictable. */
    private val fixedClock: Clock = Clock.fixed(nowInstant, ZoneOffset.UTC)

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
    fun `the board's own node never joins the roster but its name is captured`() = runTest {
        // The board reports its own node via selfNodeNumber. Its own position /
        // telemetry must never reach the roster (you are not your own buddy), but
        // its self NodeInfo carries the board's OLED name — that is captured as
        // SelfBoardIdentified (not a roster peer) so the UI can label the board.
        val conn = StubMeshtasticConnection(
            initialLinkState = LinkState.UP,
            selfNodeNumber = antoine.nodeNumber,
        )
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        conn.emit(MeshEvent.PeerPositionUpdate(antoine, sampleFix))
        conn.emit(MeshEvent.PeerIdentityKnown(antoine))
        conn.emit(
            MeshEvent.PeerTelemetry(antoine, batteryPercent = 90, timestampSeconds = 1),
        )

        // Only the board's identity is captured; position/telemetry are dropped and
        // no roster peer is created for the self node.
        assertThat(dispatched).containsExactly(
            PeerAction.SelfBoardIdentified(antoine, Instant.now(fixedClock)),
        )

        // A different node still comes through normally.
        val buddy = PeerIdentity.fromNodeNumber(0xbbbbbbbbL, longName = "Buddy", shortName = "BD")
        conn.emit(MeshEvent.PeerPositionUpdate(buddy, sampleFix))
        assertThat(dispatched.map { it::class }).containsExactly(
            PeerAction.SelfBoardIdentified::class,
            PeerAction.PeerSeen::class,
            PeerAction.PeerPositionReceived::class,
        ).inOrder()

        // Link-state events are not peer events and must pass regardless.
        conn.setLinkState(LinkState.DOWN)
        assertThat(dispatched.last()).isEqualTo(PeerAction.LinkStateChanged(LinkState.DOWN))
    }

    @Test
    fun `a stale-on-arrival position is dropped as a replay, not registered`() = runTest {
        // The board replays cached nodeDB positions on connect, shaped like live
        // broadcasts but carrying their original (old) timestamp. Those must not
        // register a peer — only fresh positions are live presence.
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        val staleFix = sampleFix.copy(timestampSeconds = nowInstant.epochSecond - 3_600) // 1h old
        conn.emit(MeshEvent.PeerPositionUpdate(antoine, staleFix))
        assertThat(dispatched).isEmpty()

        // A fresh position from the same peer still registers normally.
        conn.emit(MeshEvent.PeerPositionUpdate(antoine, sampleFix))
        assertThat(dispatched.map { it::class }).containsExactly(
            PeerAction.PeerSeen::class,
            PeerAction.PeerPositionReceived::class,
        ).inOrder()
    }

    @Test
    fun `a position with no timestamp is dropped as a timeless replay`() = runTest {
        // The board replays cached nodeDB positions whose stored time was 0, so
        // they arrive timeless (rx_time = 0). A live packet always carries a
        // reception time, so a 0 timestamp means "cached replay" — drop it.
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        val timelessFix = sampleFix.copy(timestampSeconds = 0L)
        conn.emit(MeshEvent.PeerPositionUpdate(antoine, timelessFix))
        conn.emit(MeshEvent.PeerTelemetry(antoine, batteryPercent = 50, timestampSeconds = 0L))

        assertThat(dispatched).isEmpty()
    }

    @Test
    fun `NodeInfo from a non-Mezulla node evicts it and drops its later events`() = runTest {
        // hw_model != PRIVATE_HW means a public-mesh node. It must be evicted
        // (PeerRemoved), not name-updated, and all its later events dropped —
        // even a fresh live position.
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        val publicNode = PeerIdentity.fromNodeNumber(0xc0ffee01L, longName = "Stranger", hwModel = 43) // HELTEC_V3
        conn.emit(MeshEvent.PeerIdentityKnown(publicNode))
        assertThat(dispatched.map { it::class }).containsExactly(PeerAction.PeerRemoved::class)

        // A subsequent fresh position from that node is dropped (known non-Mezulla).
        conn.emit(MeshEvent.PeerPositionUpdate(publicNode, sampleFix))
        assertThat(dispatched.map { it::class }).containsExactly(PeerAction.PeerRemoved::class)
    }

    @Test
    fun `a re-flashed node that re-advertises PRIVATE_HW is re-admitted, not blocked forever`() = runTest {
        // The core "flash a stock board into a Mezulla" path: a buddy first heard
        // our board as its stock model (e.g. HELTEC_V3) and evicted it; once the
        // board is reflashed it advertises PRIVATE_HW. That fresh NodeInfo must
        // RE-ADMIT the node (eviction is not a one-way door), and its live
        // positions must then register on the roster.
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        val node = 0xc0ffee01L
        // First seen as a stock board → evicted.
        conn.emit(MeshEvent.PeerIdentityKnown(PeerIdentity.fromNodeNumber(node, hwModel = 43)))
        assertThat(dispatched.map { it::class }).containsExactly(PeerAction.PeerRemoved::class)

        // After reflash it advertises PRIVATE_HW → re-admitted (identity update),
        // no longer blocked.
        val reflashed = PeerIdentity.fromNodeNumber(node, longName = "Buddy", shortName = "BD", hwModel = 255)
        conn.emit(MeshEvent.PeerIdentityKnown(reflashed))
        assertThat(dispatched.last()).isEqualTo(PeerAction.PeerIdentityUpdate(reflashed, Instant.now(fixedClock)))

        // A live position from it now registers (PeerSeen + PeerPositionReceived),
        // proving the node is off the block-list.
        conn.emit(MeshEvent.PeerPositionUpdate(reflashed, sampleFix))
        assertThat(dispatched.map { it::class }).containsAtLeast(
            PeerAction.PeerSeen::class,
            PeerAction.PeerPositionReceived::class,
        )
    }

    @Test
    fun `a buddy's name from NodeInfo names the roster entry its later position creates`() = runTest {
        // The usual connect-time order: the board replays the buddy's NodeInfo
        // (carrying the board name) BEFORE its first position. NodeInfo is
        // update-only and creates nothing yet, so the position is what registers
        // the peer — and it must do so under the cached NAME, not a bare "!hex".
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        val node = 0x357d5209L
        val named = PeerIdentity.fromNodeNumber(node, longName = "Meshtastic 8530", shortName = "8530", hwModel = 255)
        conn.emit(MeshEvent.PeerIdentityKnown(named))
        // A position carrying only the node number (no name), as real ones do.
        val bare = PeerIdentity.fromNodeNumber(node)
        conn.emit(MeshEvent.PeerPositionUpdate(bare, sampleFix))

        val seen = dispatched.filterIsInstance<PeerAction.PeerSeen>().single()
        assertThat(seen.identity.longName).isEqualTo("Meshtastic 8530")
        val pos = dispatched.filterIsInstance<PeerAction.PeerPositionReceived>().single()
        assertThat(pos.identity.longName).isEqualTo("Meshtastic 8530")
    }

    @Test
    fun `a placeholder NodeInfo with hwModel UNSET is not evicted and still names the peer`() = runTest {
        // A board that has only heard a buddy's POSITION synthesizes a placeholder
        // NodeInfo with a default name and hw_model = UNSET (0). UNSET is "unknown",
        // not "confirmed public" — it must NOT be evicted, and its name must carry
        // through to the roster entry the position creates.
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        val node = 0x0fb88838L
        val placeholder = PeerIdentity.fromNodeNumber(node, longName = "Meshtastic 8838", shortName = "8838", hwModel = 0)
        conn.emit(MeshEvent.PeerIdentityKnown(placeholder))
        // UNSET must not produce a PeerRemoved (eviction).
        assertThat(dispatched.map { it::class }).containsExactly(PeerAction.PeerIdentityUpdate::class)

        conn.emit(MeshEvent.PeerPositionUpdate(PeerIdentity.fromNodeNumber(node), sampleFix))
        assertThat(dispatched.filterIsInstance<PeerAction.PeerSeen>().single().identity.longName)
            .isEqualTo("Meshtastic 8838")
    }

    @Test
    fun `NodeInfo from a Mezulla node (PRIVATE_HW) is admitted`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)
        val dispatched = newDispatchedList()
        buildAndStart(conn, dispatched)

        val buddy = PeerIdentity.fromNodeNumber(
            0xabcdef01L, longName = "Buddy", shortName = "BD", hwModel = 255,
        )
        conn.emit(MeshEvent.PeerIdentityKnown(buddy))
        assertThat(dispatched).containsExactly(
            PeerAction.PeerIdentityUpdate(buddy, Instant.now(fixedClock)),
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
