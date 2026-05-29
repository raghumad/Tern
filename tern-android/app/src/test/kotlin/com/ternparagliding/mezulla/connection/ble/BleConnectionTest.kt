package com.ternparagliding.mezulla.connection.ble

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.MeshtasticConnection
import com.ternparagliding.mezulla.connection.PeerPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Link-state machine + outbound-write tests for [BleConnection].
 *
 * Uses [FakeBleTransport] to inject transport events from the test —
 * `Connected` → expect `LinkStateChange(UP)`, `Disconnected` → expect
 * `LinkStateChange(DOWN)`, reconnect → expect `UP` again. No Android
 * Bluetooth API touched.
 *
 * Concrete BLE-stack behaviour (real BluetoothGatt callbacks, real bond,
 * real notify subscriptions) is the WS4.4 human test on the user's
 * physical phone + the flashed LilyGo board.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleConnectionTest {

    private val sampleFix = PeerPosition.Fix(
        latitudeDeg = 45.95,
        longitudeDeg = 6.42,
        altitudeMeters = 1800,
        groundSpeedMetersPerSecond = 9.0,
        groundTrackDegrees = 90.0,
        timestampSeconds = 1_700_000_000L,
    )

    @Test
    fun `link state transitions UP on connect, DOWN on drop, UP again on reconnect`() = runTest {
        val transport = FakeBleTransport()
        val conn = BleConnection(
            pairedBoardId = "!f9926184",
            ourNodeNumber = 0x12345678L,
            transport = transport,
            scope = backgroundScope,
        )

        conn.events().test {
            conn.start()
            // start() emits the initial NEVER_PAIRED → DOWN transition.
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))

            transport.emit(BleTransportEvent.Connected)
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))
            assertThat(conn.linkState).isEqualTo(LinkState.UP)

            transport.emit(BleTransportEvent.Disconnected)
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))
            assertThat(conn.linkState).isEqualTo(LinkState.DOWN)

            transport.emit(BleTransportEvent.Connected)
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))
            assertThat(conn.linkState).isEqualTo(LinkState.UP)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `inbound FromRadio frame is decoded and emitted as the matching MeshEvent`() = runTest {
        val transport = FakeBleTransport()
        val conn = BleConnection(
            pairedBoardId = "!f9926184",
            ourNodeNumber = 0x12345678L,
            transport = transport,
            scope = backgroundScope,
        )
        val antoine = 0xa1b2c3d4L
        val frame = buildPositionFrame(antoine, sampleFix)

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))

            transport.emit(BleTransportEvent.Connected)
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            transport.emit(BleTransportEvent.FromRadioFrame(frame))
            val event = awaitItem()
            assertThat(event).isInstanceOf(MeshEvent.PeerPositionUpdate::class.java)
            val ppu = event as MeshEvent.PeerPositionUpdate
            assertThat(ppu.peer.nodeNumber).isEqualTo(antoine)
            assertThat(ppu.fix.latitudeDeg).isWithin(1e-6).of(sampleFix.latitudeDeg)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendOwnPosition is silent when link is DOWN`() = runTest {
        val transport = FakeBleTransport()
        val conn = BleConnection(
            pairedBoardId = "!f9926184",
            ourNodeNumber = 0x12345678L,
            transport = transport,
            scope = backgroundScope,
        )
        conn.start()

        conn.sendOwnPosition(sampleFix)

        assertThat(transport.writes).isEmpty()
    }

    @Test
    fun `sendOwnPosition writes a ToRadio frame when link is UP`() = runTest {
        val transport = FakeBleTransport()
        val conn = BleConnection(
            pairedBoardId = "!f9926184",
            ourNodeNumber = 0x12345678L,
            transport = transport,
            scope = backgroundScope,
        )
        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))
            transport.emit(BleTransportEvent.Connected)
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            conn.sendOwnPosition(sampleFix)

            assertThat(transport.writes).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendAlert returns NoLink when link is DOWN`() = runTest {
        val transport = FakeBleTransport()
        val conn = BleConnection(
            pairedBoardId = "!f9926184",
            ourNodeNumber = 0x12345678L,
            transport = transport,
            scope = backgroundScope,
        )
        conn.start()

        val result = conn.sendAlert(lastKnownPosition = sampleFix)

        assertThat(result).isEqualTo(MeshtasticConnection.SendResult.NoLink)
        assertThat(transport.writes).isEmpty()
    }

    @Test
    fun `sendAlert returns Acked on a successful BLE write`() = runTest {
        val transport = FakeBleTransport()
        val conn = BleConnection(
            pairedBoardId = "!f9926184",
            ourNodeNumber = 0x12345678L,
            transport = transport,
            scope = backgroundScope,
        )
        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))
            transport.emit(BleTransportEvent.Connected)
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            val result = conn.sendAlert(lastKnownPosition = sampleFix)

            assertThat(result).isEqualTo(MeshtasticConnection.SendResult.Acked)
            assertThat(transport.writes).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendAlert returns NoAck after maxRetries if every BLE write fails`() = runTest {
        val transport = FakeBleTransport()
        transport.writesShouldFail = true
        val conn = BleConnection(
            pairedBoardId = "!f9926184",
            ourNodeNumber = 0x12345678L,
            transport = transport,
            scope = backgroundScope,
        )
        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))
            transport.emit(BleTransportEvent.Connected)
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            val result = conn.sendAlert(lastKnownPosition = sampleFix, maxRetries = 2)

            assertThat(result).isEqualTo(MeshtasticConnection.SendResult.NoAck)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `start is a no-op when no board has ever been paired`() = runTest {
        val transport = FakeBleTransport()
        val conn = BleConnection(
            pairedBoardId = null,
            ourNodeNumber = 0x12345678L,
            transport = transport,
            scope = backgroundScope,
        )

        conn.start()

        // No transport.start(), no scanning, no state churn — we just stay
        // NEVER_PAIRED. This is the project-tern-graceful-degradation
        // policy: "no LoRa board paired" is a known state, not an error.
        assertThat(transport.started).isFalse()
        assertThat(conn.linkState).isEqualTo(LinkState.NEVER_PAIRED)
    }

    // ---------- helpers ----------

    private fun buildPositionFrame(fromNodeNumber: Long, fix: PeerPosition.Fix): ByteArray {
        val positionBody = MeshPacketCodec.encodePositionPayload(fix)
        val dataBody = ProtoWriter().apply {
            writeInt32(1, MeshPacketCodec.PORT_POSITION_APP)
            writeBytes(2, positionBody)
        }.toByteArray()
        val meshPacketBody = ProtoWriter().apply {
            writeFixed32(1, fromNodeNumber and 0xFFFFFFFFL)
            writeFixed32(7, fix.timestampSeconds and 0xFFFFFFFFL)
            writeMessage(4, dataBody)
        }.toByteArray()
        return ProtoWriter().apply {
            writeMessage(2, meshPacketBody)
        }.toByteArray()
    }
}
