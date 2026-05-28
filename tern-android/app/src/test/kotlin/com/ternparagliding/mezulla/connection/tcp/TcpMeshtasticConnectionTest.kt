package com.ternparagliding.mezulla.connection.tcp

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.MeshtasticConnection
import com.ternparagliding.mezulla.connection.PeerPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Tests for [TcpMeshtasticConnection].
 *
 * The read loop runs on `Dispatchers.IO`, so these tests use real
 * coroutines (no virtual time) and a [FakeTcpSocketHandle] that mimics
 * a blocking socket with piped streams. That keeps the behaviour we're
 * testing — read-loop continues until socket closes / errors, write
 * goes through to the board side — close to what the production socket
 * does, without standing up a real TCP server.
 *
 * Each test collects events with Turbine to assert ordering of
 * link-state transitions vs decoded MeshPackets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpMeshtasticConnectionTest {

    /**
     * On a successful connect, the connection reports `UP` and sends a
     * `want_config_id` ToRadio so the board replays its NodeDB.
     */
    @Test
    fun `start emits UP and writes want_config_id to the board`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "10.0.2.2",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()

            // First event must be the UP transition.
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))
            assertThat(conn.linkState).isEqualTo(LinkState.UP)

            // The board sees one framed ToRadio message that carries
            // `want_config_id`.
            val writes = handle.drainToBoard()
            assertThat(writes).hasSize(1)
            val framed = writes[0]
            assertThat(framed[0]).isEqualTo(MeshtasticFraming.MAGIC_0)
            assertThat(framed[1]).isEqualTo(MeshtasticFraming.MAGIC_1)

            conn.stop()
            // Stop emits a DOWN transition.
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))
            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        assertThat(factory.openHosts).containsExactly("10.0.2.2:4403")
    }

    /**
     * If [TcpSocketFactory.open] throws (connection refused, host
     * unreachable, etc.), the connection surfaces DOWN — it never lets
     * the IOException escape `start()`.
     */
    @Test
    fun `start stays DOWN when the factory throws on open`(): Unit = runBlocking {
        val factory = FakeTcpSocketFactory().also { it.enqueueConnectionRefused() }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "nope.local",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        // start() was already DOWN (initial state), so there's no state
        // transition and no event emitted. The caller inspects linkState
        // after start() returns to know the connect attempt failed.
        conn.start()
        assertThat(conn.linkState).isEqualTo(LinkState.DOWN)

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * When the board side EOFs (socket closed by peer), the read loop
     * exits cleanly and we emit DOWN.
     */
    @Test
    fun `clean EOF on the read side flips link to DOWN`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            handle.simulateBoardDisconnect()

            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))
            assertThat(conn.linkState).isEqualTo(LinkState.DOWN)
            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * Socket read throws → no exception propagates; DOWN is emitted.
     * This is the "transient IO error during read" path.
     */
    @Test
    fun `read IOException becomes a DOWN event without throwing`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle(readThrowsOnFirstCall = IOException("simulated read error"))
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            // The first read throws; the read loop catches and emits DOWN.
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.DOWN))
            assertThat(conn.linkState).isEqualTo(LinkState.DOWN)
            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * A FromRadio carrying a NODEINFO_APP MeshPacket from a peer is
     * decoded into [MeshEvent.PeerIdentityKnown] with the peer's name.
     */
    @Test
    fun `incoming NodeInfo MeshPacket surfaces PeerIdentityKnown`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            val user = ProtoUser(id = "!a1b2c3d4", longName = "Antoine", shortName = "AN")
            val packet = ProtoMeshPacket(
                from = 0xa1b2c3d4.toInt(),
                decoded = ProtoData(
                    portnum = MeshtasticProtos.PORT_NODEINFO,
                    payload = user.encode(),
                ),
            )
            val fromRadio = encodeFromRadioWithPacket(packet)
            handle.feedFromBoard(MeshtasticFraming.encodeFrame(fromRadio))

            val ev = awaitItem()
            assertThat(ev).isInstanceOf(MeshEvent.PeerIdentityKnown::class.java)
            val peerEv = ev as MeshEvent.PeerIdentityKnown
            assertThat(peerEv.peer.nodeNumber).isEqualTo(0xa1b2c3d4L)
            assertThat(peerEv.peer.hexId).isEqualTo("!a1b2c3d4")
            assertThat(peerEv.peer.longName).isEqualTo("Antoine")
            assertThat(peerEv.peer.shortName).isEqualTo("AN")

            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * A FromRadio carrying a POSITION_APP MeshPacket surfaces
     * [MeshEvent.PeerPositionUpdate] with the lat/lon scaled back to
     * doubles.
     */
    @Test
    fun `incoming Position MeshPacket surfaces PeerPositionUpdate with scaled coordinates`(): Unit =
        runBlocking {
            val handle = FakeTcpSocketHandle()
            val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
            val scope = CoroutineScope(Dispatchers.IO)

            val conn = TcpMeshtasticConnection(
                host = "127.0.0.1",
                port = 4403,
                scope = scope,
                socketFactory = factory,
            )

            conn.events().test {
                conn.start()
                assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

                // 45.9099°N, 6.1245°E, 2400m, 9 m/s, 270°
                val pos = ProtoPosition(
                    latitudeI = 459_099_000,
                    longitudeI = 61_245_000,
                    altitudeMeters = 2400,
                    timeSeconds = 1_700_000_000,
                    groundSpeedMps = 9,
                    groundTrackDeg = 270,
                )
                val packet = ProtoMeshPacket(
                    from = 0xdeadbeef.toInt(),
                    decoded = ProtoData(
                        portnum = MeshtasticProtos.PORT_POSITION,
                        payload = pos.encode(),
                    ),
                )
                val fromRadio = encodeFromRadioWithPacket(packet)
                handle.feedFromBoard(MeshtasticFraming.encodeFrame(fromRadio))

                val ev = awaitItem()
                assertThat(ev).isInstanceOf(MeshEvent.PeerPositionUpdate::class.java)
                val pe = ev as MeshEvent.PeerPositionUpdate
                assertThat(pe.peer.nodeNumber).isEqualTo(0xdeadbeefL)
                assertThat(pe.fix.latitudeDeg).isWithin(1e-6).of(45.9099)
                assertThat(pe.fix.longitudeDeg).isWithin(1e-6).of(6.1245)
                assertThat(pe.fix.altitudeMeters).isEqualTo(2400)
                assertThat(pe.fix.groundSpeedMetersPerSecond).isEqualTo(9.0)
                assertThat(pe.fix.groundTrackDegrees).isEqualTo(270.0)
                assertThat(pe.fix.timestampSeconds).isEqualTo(1_700_000_000L)

                cancelAndIgnoreRemainingEvents()
            }

            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }

    /**
     * Position arriving in two TCP reads (header in the first, payload
     * in the second) still produces exactly one decoded event. This is
     * the realistic case where MTU + TCP buffering splits a small frame
     * across reads.
     */
    @Test
    fun `position split across two reads still produces one event`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            val pos = ProtoPosition(
                latitudeI = 100_000_000,
                longitudeI = 200_000_000,
                timeSeconds = 1,
            )
            val packet = ProtoMeshPacket(
                from = 0x100,
                decoded = ProtoData(
                    portnum = MeshtasticProtos.PORT_POSITION,
                    payload = pos.encode(),
                ),
            )
            val framed = MeshtasticFraming.encodeFrame(encodeFromRadioWithPacket(packet))

            // Two reads: first half of the frame, then the second half.
            val mid = framed.size / 2
            handle.feedFromBoard(framed.copyOfRange(0, mid))
            // Give the read loop a moment to consume the first chunk
            // (no event yet expected); then send the rest.
            Thread.sleep(50)
            handle.feedFromBoard(framed.copyOfRange(mid, framed.size))

            val ev = awaitItem()
            assertThat(ev).isInstanceOf(MeshEvent.PeerPositionUpdate::class.java)
            val pe = ev as MeshEvent.PeerPositionUpdate
            assertThat(pe.peer.nodeNumber).isEqualTo(0x100L)
            assertThat(pe.fix.latitudeDeg).isWithin(1e-6).of(10.0)
            assertThat(pe.fix.longitudeDeg).isWithin(1e-6).of(20.0)

            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * Two MeshPackets concatenated in one TCP read produce two events
     * in order — same property we asserted on the framer in isolation,
     * end-to-end through the connection.
     */
    @Test
    fun `two frames in one read produce two events in order`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            val pos1 = ProtoPosition(latitudeI = 10_000_000, longitudeI = 0, timeSeconds = 1)
            val pos2 = ProtoPosition(latitudeI = 20_000_000, longitudeI = 0, timeSeconds = 2)
            val p1 = ProtoMeshPacket(
                from = 1,
                decoded = ProtoData(MeshtasticProtos.PORT_POSITION, pos1.encode()),
            )
            val p2 = ProtoMeshPacket(
                from = 2,
                decoded = ProtoData(MeshtasticProtos.PORT_POSITION, pos2.encode()),
            )
            val combined = MeshtasticFraming.encodeFrame(encodeFromRadioWithPacket(p1)) +
                MeshtasticFraming.encodeFrame(encodeFromRadioWithPacket(p2))
            handle.feedFromBoard(combined)

            val ev1 = awaitItem() as MeshEvent.PeerPositionUpdate
            val ev2 = awaitItem() as MeshEvent.PeerPositionUpdate
            assertThat(ev1.peer.nodeNumber).isEqualTo(1L)
            assertThat(ev1.fix.latitudeDeg).isWithin(1e-6).of(1.0)
            assertThat(ev2.peer.nodeNumber).isEqualTo(2L)
            assertThat(ev2.fix.latitudeDeg).isWithin(1e-6).of(2.0)

            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * Telemetry round-trip end-to-end through the connection.
     */
    @Test
    fun `incoming Telemetry MeshPacket surfaces PeerTelemetry with battery percent`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            // Hand-encode a Telemetry: field 1 fixed32 time, field 2
            // length-delimited DeviceMetrics with field 1 uint32 battery.
            val telemetryWriter = ProtoWriter().apply {
                writeFixed32(1, 1_700_000_500)
                writeMessage(2, ProtoDeviceMetrics(batteryPercent = 87).encode())
            }
            val packet = ProtoMeshPacket(
                from = 0x42,
                decoded = ProtoData(
                    portnum = MeshtasticProtos.PORT_TELEMETRY,
                    payload = telemetryWriter.toByteArray(),
                ),
            )
            handle.feedFromBoard(
                MeshtasticFraming.encodeFrame(encodeFromRadioWithPacket(packet)),
            )

            val ev = awaitItem() as MeshEvent.PeerTelemetry
            assertThat(ev.peer.nodeNumber).isEqualTo(0x42L)
            assertThat(ev.batteryPercent).isEqualTo(87)
            assertThat(ev.timestampSeconds).isEqualTo(1_700_000_500L)

            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * Alerts come through with the sender's identity. The payload-rider
     * (last-known-position) is null on the wire today — see the design
     * doc's open question on the SOS payload schema.
     */
    @Test
    fun `incoming ALERT_APP MeshPacket surfaces PeerAlert`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            val packet = ProtoMeshPacket(
                from = 0xa1b2c3d4.toInt(),
                rxTime = 1_700_000_999.toInt(),
                decoded = ProtoData(
                    portnum = MeshtasticProtos.PORT_ALERT,
                    payload = ByteArray(0),
                ),
            )
            handle.feedFromBoard(
                MeshtasticFraming.encodeFrame(encodeFromRadioWithPacket(packet)),
            )

            val ev = awaitItem() as MeshEvent.PeerAlert
            assertThat(ev.peer.nodeNumber).isEqualTo(0xa1b2c3d4L)
            assertThat(ev.timestampSeconds).isEqualTo(1_700_000_999L)
            assertThat(ev.lastKnownPosition).isNull()

            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * Unknown PortNums are silently dropped, not surfaced or crashed
     * over.
     */
    @Test
    fun `MeshPacket on an unknown PortNum is silently ignored`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            val packetUnknown = ProtoMeshPacket(
                from = 0x99,
                decoded = ProtoData(portnum = 999, payload = byteArrayOf(0x01, 0x02)),
            )
            // Follow it with a known POSITION packet — its event must arrive,
            // proving the unknown one didn't stall the read loop.
            val pos = ProtoPosition(latitudeI = 1_000_000, longitudeI = 0, timeSeconds = 5)
            val packetKnown = ProtoMeshPacket(
                from = 0x100,
                decoded = ProtoData(MeshtasticProtos.PORT_POSITION, pos.encode()),
            )
            handle.feedFromBoard(
                MeshtasticFraming.encodeFrame(encodeFromRadioWithPacket(packetUnknown)) +
                    MeshtasticFraming.encodeFrame(encodeFromRadioWithPacket(packetKnown)),
            )

            // First event from the connection must be from the KNOWN packet.
            val ev = awaitItem()
            assertThat(ev).isInstanceOf(MeshEvent.PeerPositionUpdate::class.java)
            assertThat((ev as MeshEvent.PeerPositionUpdate).peer.nodeNumber).isEqualTo(0x100L)

            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * sendOwnPosition encodes a POSITION_APP MeshPacket and writes it to
     * the socket. We assert the written bytes decode back to the same
     * position values we supplied.
     */
    @Test
    fun `sendOwnPosition writes a framed POSITION_APP MeshPacket to the board`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
            ownNodeId = 0x12345678L,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            // Discard the want_config_id write that start() emits.
            handle.drainToBoard()

            conn.sendOwnPosition(
                PeerPosition.Fix(
                    latitudeDeg = 45.9099,
                    longitudeDeg = 6.1245,
                    altitudeMeters = 2400,
                    groundSpeedMetersPerSecond = 9.5,
                    groundTrackDegrees = 270.0,
                    timestampSeconds = 1_700_000_000L,
                ),
            )

            val writes = handle.drainToBoard()
            assertThat(writes).hasSize(1)
            val packet = decodePacketFromToRadioFrame(writes[0])
            assertThat(packet.decoded?.portnum).isEqualTo(MeshtasticProtos.PORT_POSITION)
            val pos = ProtoPosition.decode(packet.decoded!!.payload)
            assertThat(pos.latitudeI).isEqualTo(459_099_000)
            assertThat(pos.longitudeI).isEqualTo(61_245_000)
            assertThat(pos.altitudeMeters).isEqualTo(2400)
            assertThat(pos.timeSeconds).isEqualTo(1_700_000_000)
            // 9.5 m/s rounds to 10.
            assertThat(pos.groundSpeedMps).isEqualTo(10)

            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * sendOwnPosition while the link is DOWN is a silent no-op — no
     * write, no exception, return immediately.
     */
    @Test
    fun `sendOwnPosition is silent when link is DOWN`(): Unit = runBlocking {
        val factory = FakeTcpSocketFactory().also { it.enqueueConnectionRefused() }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "nope",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.start() // ends with DOWN
        assertThat(conn.linkState).isEqualTo(LinkState.DOWN)

        // Doesn't throw.
        conn.sendOwnPosition(
            PeerPosition.Fix(
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                altitudeMeters = null,
                groundSpeedMetersPerSecond = null,
                groundTrackDegrees = null,
                timestampSeconds = 0,
            ),
        )

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * sendAlert encodes ALERT_APP with priority and want_ack, writes,
     * and returns Acked on a successful write. NoLink path is exercised
     * separately below.
     */
    @Test
    fun `sendAlert writes ALERT_APP packet with priority and want_ack and returns Acked`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))
            handle.drainToBoard() // drop want_config_id

            val result = conn.sendAlert(
                lastKnownPosition = PeerPosition.Fix(
                    latitudeDeg = 1.0,
                    longitudeDeg = 2.0,
                    altitudeMeters = 100,
                    groundSpeedMetersPerSecond = null,
                    groundTrackDegrees = null,
                    timestampSeconds = 1_700_000_000,
                ),
            )

            assertThat(result).isEqualTo(MeshtasticConnection.SendResult.Acked)
            val writes = handle.drainToBoard()
            assertThat(writes).hasSize(1)
            val packet = decodePacketFromToRadioFrame(writes[0])
            assertThat(packet.decoded?.portnum).isEqualTo(MeshtasticProtos.PORT_ALERT)
            assertThat(packet.wantAck).isTrue()
            assertThat(packet.priority).isEqualTo(MeshtasticProtos.PRIORITY_ALERT)

            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    @Test
    fun `sendAlert returns NoLink when link is DOWN and does not throw`(): Unit = runBlocking {
        val factory = FakeTcpSocketFactory().also { it.enqueueConnectionRefused() }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "nope",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.start()
        assertThat(conn.linkState).isEqualTo(LinkState.DOWN)

        val result = conn.sendAlert(lastKnownPosition = null)
        assertThat(result).isEqualTo(MeshtasticConnection.SendResult.NoLink)

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * NodeInfo carried as `FromRadio.node_info` (field 4), not wrapped
     * in a MeshPacket. This is what the board hands us during the
     * NodeDB replay on connect.
     */
    @Test
    fun `FromRadio node_info surfaces PeerIdentityKnown and an optional PeerPositionUpdate`(): Unit =
        runBlocking {
            val handle = FakeTcpSocketHandle()
            val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
            val scope = CoroutineScope(Dispatchers.IO)

            val conn = TcpMeshtasticConnection(
                host = "127.0.0.1",
                port = 4403,
                scope = scope,
                socketFactory = factory,
            )

            conn.events().test {
                conn.start()
                assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

                // Build a NodeInfo with user + position and wrap as
                // FromRadio.node_info (field 4).
                val user = ProtoUser(id = "!deadbeef", longName = "Guillaume", shortName = "GU")
                val pos = ProtoPosition(latitudeI = 459_099_000, longitudeI = 61_245_000, timeSeconds = 1)
                val nodeInfoBytes = ProtoWriter().apply {
                    writeUInt32(1, 0xdeadbeef.toInt())
                    writeMessage(4, user.encode())
                    writeMessage(6, pos.encode())
                }.toByteArray()

                val fromRadio = ProtoWriter().apply {
                    writeMessage(4, nodeInfoBytes)
                }.toByteArray()

                handle.feedFromBoard(MeshtasticFraming.encodeFrame(fromRadio))

                val first = awaitItem()
                assertThat(first).isInstanceOf(MeshEvent.PeerIdentityKnown::class.java)
                assertThat((first as MeshEvent.PeerIdentityKnown).peer.longName).isEqualTo("Guillaume")

                val second = awaitItem()
                assertThat(second).isInstanceOf(MeshEvent.PeerPositionUpdate::class.java)
                val pe = second as MeshEvent.PeerPositionUpdate
                assertThat(pe.fix.latitudeDeg).isWithin(1e-6).of(45.9099)

                cancelAndIgnoreRemainingEvents()
            }

            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }

    /**
     * Bytes that don't decode as protobuf are skipped, and the read
     * loop keeps running. Tested by sending a valid frame whose payload
     * is garbage, followed by a valid frame.
     */
    @Test
    fun `malformed protobuf frame is skipped without stopping the read loop`(): Unit = runBlocking {
        val handle = FakeTcpSocketHandle()
        val factory = FakeTcpSocketFactory().also { it.enqueueHandle(handle) }
        val scope = CoroutineScope(Dispatchers.IO)

        val conn = TcpMeshtasticConnection(
            host = "127.0.0.1",
            port = 4403,
            scope = scope,
            socketFactory = factory,
        )

        conn.events().test {
            conn.start()
            assertThat(awaitItem()).isEqualTo(MeshEvent.LinkStateChange(LinkState.UP))

            // Garbage that doesn't parse as a FromRadio. A varint with
            // its high bit set forever truncates — the decoder throws,
            // we catch and skip.
            val garbage = byteArrayOf(
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
            )
            val pos = ProtoPosition(latitudeI = 10_000_000, longitudeI = 0, timeSeconds = 1)
            val good = ProtoMeshPacket(
                from = 0x100,
                decoded = ProtoData(MeshtasticProtos.PORT_POSITION, pos.encode()),
            )

            handle.feedFromBoard(
                MeshtasticFraming.encodeFrame(garbage) +
                    MeshtasticFraming.encodeFrame(encodeFromRadioWithPacket(good)),
            )

            // Only the good packet's event arrives.
            val ev = awaitItem()
            assertThat(ev).isInstanceOf(MeshEvent.PeerPositionUpdate::class.java)
            assertThat((ev as MeshEvent.PeerPositionUpdate).peer.nodeNumber).isEqualTo(0x100L)

            cancelAndIgnoreRemainingEvents()
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Wraps a MeshPacket as a FromRadio with `packet` (field 2). The
     * test version of what the firmware sends on each over-the-air
     * receive.
     */
    private fun encodeFromRadioWithPacket(packet: ProtoMeshPacket): ByteArray =
        ProtoWriter().apply { writeMessage(2, packet.encode()) }.toByteArray()

    /**
     * Strip framing from a ToRadio write and decode the embedded
     * MeshPacket (field 1).
     */
    private fun decodePacketFromToRadioFrame(framed: ByteArray): ProtoMeshPacket {
        val payload = framed.copyOfRange(MeshtasticFraming.HEADER_SIZE, framed.size)
        val r = ProtoReader(payload)
        while (r.hasMore()) {
            val (field, wire) = r.readTag()
            if (field == 1) return ProtoMeshPacket.decode(r.readBytes())
            r.skipField(wire)
        }
        error("no MeshPacket field found in ToRadio")
    }
}
