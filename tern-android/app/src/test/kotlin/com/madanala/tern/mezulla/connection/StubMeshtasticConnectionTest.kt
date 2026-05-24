package com.madanala.tern.mezulla.connection

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Smoke test for the WS2.1 MeshtasticConnection abstraction.
 *
 * Purpose: prove the interface compiles, prove a single subscriber sees
 * injected events in the order they were injected, and prove the link
 * state and outbound command recording behave as the KDoc claims. This is
 * not a substitute for the swarm-driven BDD test (WS1.5) — it is the
 * minimum mechanical check that the abstraction is wired correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StubMeshtasticConnectionTest {

    private val sampleFix = PeerPosition.Fix(
        latitudeDeg = 32.2319,
        longitudeDeg = 76.6334,
        altitudeMeters = 2400,
        groundSpeedMetersPerSecond = 9.5,
        groundTrackDegrees = 270.0,
        timestampSeconds = 1_700_000_000L,
    )

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

    @Test
    fun `subscriber observes injected events in the order they were injected`() = runTest {
        val conn = StubMeshtasticConnection()

        conn.events().test {
            conn.emit(MeshEvent.PeerIdentityKnown(antoine))
            conn.emit(MeshEvent.PeerPositionUpdate(antoine, sampleFix))
            conn.emit(
                MeshEvent.PeerTelemetry(
                    peer = antoine,
                    batteryPercent = 87,
                    timestampSeconds = sampleFix.timestampSeconds,
                ),
            )
            conn.emit(
                MeshEvent.PeerAlert(
                    peer = antoine,
                    lastKnownPosition = sampleFix,
                    timestampSeconds = sampleFix.timestampSeconds + 30,
                ),
            )
            conn.emit(MeshEvent.PeerPositionUpdate(guillaume, sampleFix))

            assertThat(awaitItem()).isEqualTo(MeshEvent.PeerIdentityKnown(antoine))
            assertThat(awaitItem()).isEqualTo(MeshEvent.PeerPositionUpdate(antoine, sampleFix))
            assertThat(awaitItem()).isEqualTo(
                MeshEvent.PeerTelemetry(antoine, 87, sampleFix.timestampSeconds),
            )
            assertThat(awaitItem()).isEqualTo(
                MeshEvent.PeerAlert(antoine, sampleFix, sampleFix.timestampSeconds + 30),
            )
            assertThat(awaitItem()).isEqualTo(MeshEvent.PeerPositionUpdate(guillaume, sampleFix))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `link state change is reflected on the property and published on the stream`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)

        conn.events().test {
            conn.setLinkState(LinkState.DOWN)
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))
            assertThat(conn.linkState).isEqualTo(LinkState.DOWN)

            conn.setLinkState(LinkState.UP)
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))
            assertThat(conn.linkState).isEqualTo(LinkState.UP)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendOwnPosition records the fix when link is UP`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)

        conn.sendOwnPosition(sampleFix)

        assertThat(conn.sentPositions).containsExactly(sampleFix)
    }

    @Test
    fun `sendOwnPosition is silent and records nothing when link is DOWN`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.DOWN)

        conn.sendOwnPosition(sampleFix)

        assertThat(conn.sentPositions).isEmpty()
    }

    @Test
    fun `sendAlert returns Acked and records the position when link is UP`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)

        val result = conn.sendAlert(lastKnownPosition = sampleFix)

        assertThat(result).isEqualTo(MeshtasticConnection.SendResult.Acked)
        assertThat(conn.sentAlerts).containsExactly(sampleFix)
    }

    @Test
    fun `sendAlert returns NoLink and records nothing when link is DOWN`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.DOWN)

        val result = conn.sendAlert(lastKnownPosition = sampleFix)

        assertThat(result).isEqualTo(MeshtasticConnection.SendResult.NoLink)
        assertThat(conn.sentAlerts).isEmpty()
    }

    @Test
    fun `sendAlert with no last known position is allowed and recorded as null`() = runTest {
        val conn = StubMeshtasticConnection(initialLinkState = LinkState.UP)

        val result = conn.sendAlert(lastKnownPosition = null)

        assertThat(result).isEqualTo(MeshtasticConnection.SendResult.Acked)
        assertThat(conn.sentAlerts).containsExactly(null)
    }

    @Test
    fun `PeerIdentity hex form matches the !aabbccdd Meshtastic convention`() {
        assertThat(antoine.hexId).isEqualTo("!a1b2c3d4")
        assertThat(guillaume.hexId).isEqualTo("!deadbeef")
    }

    @Test
    fun `PeerIdentity rejects node numbers outside the unsigned 32-bit range`() {
        runCatching { PeerIdentity.fromNodeNumber(-1L) }
            .also { assertThat(it.isFailure).isTrue() }
        runCatching { PeerIdentity.fromNodeNumber(0x1_0000_0000L) }
            .also { assertThat(it.isFailure).isTrue() }
    }
}
