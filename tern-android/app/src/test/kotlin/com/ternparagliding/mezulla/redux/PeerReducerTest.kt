package com.ternparagliding.mezulla.redux

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Reducer-level tests for [peerReducer]. Each test drives one
 * [PeerAction] (or a short sequence) against a fresh [PeerState] and
 * asserts the resulting state — no coroutines, no flows. The middleware
 * tests in [PeerMiddlewareTest] cover the connection-to-action wiring.
 */
class PeerReducerTest {

    private val antoine = PeerIdentity.fromNodeNumber(
        nodeNumber = 0xa1b2c3d4L,
        longName = "Antoine",
        shortName = "AN",
    )

    private val guillaume = PeerIdentity.fromNodeNumber(
        nodeNumber = 0xdeadbeefL,
        longName = "Guillaume",
        shortName = "GU",
    )

    private val sampleFix = PeerPosition.Fix(
        latitudeDeg = 45.9099,
        longitudeDeg = 6.1245,
        altitudeMeters = 2400,
        groundSpeedMetersPerSecond = 9.5,
        groundTrackDegrees = 270.0,
        timestampSeconds = 1_700_000_000L,
    )

    private val t0 = Instant.parse("2026-04-25T12:00:00Z")
    private val t1 = t0.plusSeconds(15)
    private val t2 = t0.plusSeconds(30)

    @Test
    fun `empty state is sane and rendering against it produces no nulls in the peer map`() {
        // Graceful-degradation contract: a fresh state must be safe to
        // consume by a renderer that has not seen any LoRa event yet.
        val state = PeerState.empty()

        assertThat(state.peers).isEmpty()
        assertThat(state.activeAlerts).isEmpty()
        assertThat(state.linkState).isEqualTo(LinkState.NEVER_PAIRED)
    }

    @Test
    fun `PeersCleared drops all peers and alerts but keeps link state`() {
        // Simulate a roster full of public-mesh nodes + an alert, then a
        // channel change (team join) clears it so only the new team repopulates.
        var state = PeerState.empty()
        state = peerReducer(state, PeerAction.LinkStateChanged(LinkState.UP))
        state = peerReducer(state, PeerAction.PeerPositionReceived(antoine, sampleFix, t0))
        state = peerReducer(state, PeerAction.PeerSeen(guillaume, t1))
        state = peerReducer(
            state,
            PeerAction.PeerAlertReceived(guillaume, sampleFix, t1),
        )
        assertThat(state.peers).hasSize(2)
        assertThat(state.activeAlerts).hasSize(1)

        val cleared = peerReducer(state, PeerAction.PeersCleared)

        assertThat(cleared.peers).isEmpty()
        assertThat(cleared.activeAlerts).isEmpty()
        // Link state survives — clearing peers is about channel membership,
        // not the board link, which is still up.
        assertThat(cleared.linkState).isEqualTo(LinkState.UP)
    }

    @Test
    fun `PeerIdentityUpdate does NOT create a peer when none exists`() {
        // The board's NodeDB dump arrives as NodeInfo for nodes we've never
        // heard live (the public mesh). It must not put them on the roster.
        val newState = peerReducer(PeerState.empty(), PeerAction.PeerIdentityUpdate(antoine, t0))

        assertThat(newState.peers).isEmpty()
    }

    @Test
    fun `PeerIdentityUpdate refreshes name and lastSeen for an existing peer`() {
        // A peer registered by a live position; then NodeInfo fills in the name.
        val anon = PeerIdentity.fromNodeNumber(antoine.nodeNumber) // no names yet
        var state = peerReducer(PeerState.empty(), PeerAction.PeerPositionReceived(anon, sampleFix, t0))
        assertThat(state.peers[antoine.nodeNumber]!!.identity.longName).isNull()

        state = peerReducer(state, PeerAction.PeerIdentityUpdate(antoine, t1))

        val peer = state.peers[antoine.nodeNumber]!!
        assertThat(peer.identity.longName).isEqualTo("Antoine")
        assertThat(peer.lastSeenAt).isEqualTo(t1)
        // Update-only: it must not have wiped the live position fix.
        assertThat(peer.lastPosition).isEqualTo(sampleFix)
    }

    @Test
    fun `PeerRemoved evicts a peer and is a no-op for an unknown one`() {
        var state = peerReducer(PeerState.empty(), PeerAction.PeerPositionReceived(antoine, sampleFix, t0))
        assertThat(state.peers).containsKey(antoine.nodeNumber)

        state = peerReducer(state, PeerAction.PeerRemoved(antoine.nodeNumber))
        assertThat(state.peers).doesNotContainKey(antoine.nodeNumber)

        // Removing a node that isn't on the roster changes nothing.
        val after = peerReducer(state, PeerAction.PeerRemoved(guillaume.nodeNumber))
        assertThat(after.peers).isEqualTo(state.peers)
    }

    @Test
    fun `SelfBoardIdentified records the board's own identity without adding a peer`() {
        // The connected board's own NodeInfo names the board (OLED name) for the
        // UI, but must not create a roster entry — you are not your own buddy.
        val board = PeerIdentity.fromNodeNumber(0x357d5209L, longName = "Meshtastic 8530", shortName = "8530")
        val state = peerReducer(PeerState.empty(), PeerAction.SelfBoardIdentified(board, t0))

        assertThat(state.selfBoard).isEqualTo(board)
        assertThat(state.peers).isEmpty()
    }

    @Test
    fun `PeerSeen registers a previously-unknown peer`() {
        val newState = peerReducer(PeerState.empty(), PeerAction.PeerSeen(antoine, t0))

        val recorded = newState.peers[antoine.nodeNumber]
        assertThat(recorded).isNotNull()
        assertThat(recorded!!.identity).isEqualTo(antoine)
        assertThat(recorded.lastSeenAt).isEqualTo(t0)
        assertThat(recorded.lastPosition).isNull()
        assertThat(recorded.lastTelemetry).isNull()
    }

    @Test
    fun `PeerSeen for a known peer updates lastSeenAt and replaces identity but keeps position`() {
        // First arrival has only a node number (no NodeInfo yet).
        val nameless = PeerIdentity.fromNodeNumber(antoine.nodeNumber)
        var state = peerReducer(
            PeerState.empty(),
            PeerAction.PeerSeen(nameless, t0),
        )
        state = peerReducer(
            state,
            PeerAction.PeerPositionReceived(nameless, sampleFix, t0),
        )

        // Now NodeInfo arrives with the name.
        val updated = peerReducer(state, PeerAction.PeerSeen(antoine, t1))

        val recorded = updated.peers.getValue(antoine.nodeNumber)
        assertThat(recorded.identity).isEqualTo(antoine)
        assertThat(recorded.identity.longName).isEqualTo("Antoine")
        assertThat(recorded.lastPosition).isEqualTo(sampleFix)
        assertThat(recorded.lastSeenAt).isEqualTo(t1)
    }

    @Test
    fun `PeerPositionReceived stores the fix and creates the peer if needed`() {
        val newState = peerReducer(
            PeerState.empty(),
            PeerAction.PeerPositionReceived(antoine, sampleFix, t0),
        )

        val recorded = newState.peers.getValue(antoine.nodeNumber)
        assertThat(recorded.lastPosition).isEqualTo(sampleFix)
        assertThat(recorded.lastSeenAt).isEqualTo(t0)
    }

    @Test
    fun `PeerTelemetryReceived stores telemetry on the peer`() {
        val newState = peerReducer(
            PeerState.empty(),
            PeerAction.PeerTelemetryReceived(
                identity = antoine,
                batteryPercent = 73,
                timestampSeconds = sampleFix.timestampSeconds,
                receivedAt = t0,
            ),
        )

        val telemetry = newState.peers.getValue(antoine.nodeNumber).lastTelemetry
        assertThat(telemetry).isNotNull()
        assertThat(telemetry!!.batteryPercent).isEqualTo(73)
        assertThat(telemetry.timestampSeconds).isEqualTo(sampleFix.timestampSeconds)
    }

    @Test
    fun `PeerAlertReceived appends an alert and registers the sender`() {
        val newState = peerReducer(
            PeerState.empty(),
            PeerAction.PeerAlertReceived(
                senderIdentity = antoine,
                lastKnownPosition = sampleFix,
                alertedAt = t0,
            ),
        )

        assertThat(newState.peers).containsKey(antoine.nodeNumber)
        assertThat(newState.activeAlerts).hasSize(1)
        val alert = newState.activeAlerts.single()
        assertThat(alert.senderIdentity).isEqualTo(antoine)
        assertThat(alert.lastKnownPosition).isEqualTo(sampleFix)
        assertThat(alert.alertedAt).isEqualTo(t0)
        assertThat(alert.acknowledgedAt).isNull()
    }

    @Test
    fun `PeerAlertReceived with no position is allowed and stored as null`() {
        val newState = peerReducer(
            PeerState.empty(),
            PeerAction.PeerAlertReceived(
                senderIdentity = antoine,
                lastKnownPosition = null,
                alertedAt = t0,
            ),
        )

        assertThat(newState.activeAlerts.single().lastKnownPosition).isNull()
    }

    @Test
    fun `PeerAlertAcknowledged sets acknowledgedAt on the most recent matching alert`() {
        var state = peerReducer(
            PeerState.empty(),
            PeerAction.PeerAlertReceived(antoine, sampleFix, t0),
        )
        state = peerReducer(
            state,
            PeerAction.PeerAlertReceived(antoine, sampleFix, t1),
        )

        val acked = peerReducer(
            state,
            PeerAction.PeerAlertAcknowledged(antoine.nodeNumber, t2),
        )

        // First alert stays unacknowledged; second (most recent) is acked.
        assertThat(acked.activeAlerts[0].acknowledgedAt).isNull()
        assertThat(acked.activeAlerts[1].acknowledgedAt).isEqualTo(t2)
    }

    @Test
    fun `PeerAlertAcknowledged with no matching alert is a no-op`() {
        val state = peerReducer(
            PeerState.empty(),
            PeerAction.PeerAlertReceived(antoine, sampleFix, t0),
        )

        val acked = peerReducer(
            state,
            PeerAction.PeerAlertAcknowledged(guillaume.nodeNumber, t1),
        )

        // lastEventTime advances (the reducer saw an event at t1) but
        // peers and alerts are untouched.
        assertThat(acked.peers).isEqualTo(state.peers)
        assertThat(acked.activeAlerts).isEqualTo(state.activeAlerts)
        assertThat(acked.linkState).isEqualTo(state.linkState)
    }

    @Test
    fun `PeerAlertAcknowledged does not re-acknowledge already-acknowledged alerts`() {
        var state = peerReducer(
            PeerState.empty(),
            PeerAction.PeerAlertReceived(antoine, sampleFix, t0),
        )
        state = peerReducer(state, PeerAction.PeerAlertAcknowledged(antoine.nodeNumber, t1))
        val firstAckedAt = state.activeAlerts.single().acknowledgedAt

        // Second acknowledge with no fresh alert in between: alert stays unchanged.
        val again = peerReducer(state, PeerAction.PeerAlertAcknowledged(antoine.nodeNumber, t2))

        assertThat(again.activeAlerts.single().acknowledgedAt).isEqualTo(firstAckedAt)
        assertThat(again.peers).isEqualTo(state.peers)
    }

    @Test
    fun `LinkStateChanged updates the link state and nothing else`() {
        val seeded = peerReducer(PeerState.empty(), PeerAction.PeerSeen(antoine, t0))

        val downed = peerReducer(seeded, PeerAction.LinkStateChanged(LinkState.DOWN))
        assertThat(downed.linkState).isEqualTo(LinkState.DOWN)
        assertThat(downed.peers).isEqualTo(seeded.peers)

        val upped = peerReducer(downed, PeerAction.LinkStateChanged(LinkState.UP))
        assertThat(upped.linkState).isEqualTo(LinkState.UP)
        assertThat(upped.peers).isEqualTo(seeded.peers)
    }

    @Test
    fun `multiple peers are tracked independently keyed by node number`() {
        var state = peerReducer(PeerState.empty(), PeerAction.PeerSeen(antoine, t0))
        state = peerReducer(state, PeerAction.PeerPositionReceived(antoine, sampleFix, t0))
        state = peerReducer(state, PeerAction.PeerSeen(guillaume, t1))

        assertThat(state.peers).hasSize(2)
        assertThat(state.peers.getValue(antoine.nodeNumber).lastPosition).isEqualTo(sampleFix)
        assertThat(state.peers.getValue(guillaume.nodeNumber).lastPosition).isNull()
    }
}
