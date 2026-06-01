package com.ternparagliding.mezulla.connection

import kotlinx.coroutines.flow.Flow

/**
 * One link between Tern and a paired LoRa mesh node (e.g. a LilyGo T3 board
 * running Meshtastic firmware).
 *
 * This is the abstraction every transport plugs into:
 *
 *  - [com.ternparagliding.mezulla.connection.StubMeshtasticConnection] — a
 *    no-real-IO stub used in unit tests; lets a test inject inbound events
 *    and observe outbound commands.
 *  - SwarmSimulatedConnection (WS2.2, not yet built) — drives the same
 *    interface from playback of recorded IGC flights, for the convergence
 *    BDD test.
 *  - [com.ternparagliding.mezulla.connection.tcp.TcpMeshtasticConnection] —
 *    talks to a Meshtastic node over TCP. Used for the dev-time emulator
 *    workflow (board on WiFi) and as a real product feature.
 *  - BleConnection (WS4.3, not yet built) — talks to a real board over BLE.
 *
 * Design rationale (SOS path, peer identity surface, lifecycle granularity,
 * mapping to Meshtastic protobufs) is in
 * `docs/architecture/meshtastic-connection.md`. KDoc here only restates the
 * shape and the load-bearing contracts. If you change either, update both.
 *
 * Threading: implementations expose a cold [Flow] of [MeshEvent]. Subscribers
 * collect on whatever dispatcher they choose. Implementations must be safe
 * for one subscriber; multi-subscriber fan-out is a wrapper concern, not an
 * interface concern.
 *
 * "Connection absent" is a first-class state, not an error: see
 * [LinkState] and the no-board grace requirement in
 * `project_tern_graceful_degradation`.
 */
interface MeshtasticConnection {

    /**
     * Stable identifier for the paired board, as Tern persists it
     * (Meshtastic's "!hex" form of the node number; see
     * `meshtastic.proto User.id`). Null when no board has ever been paired.
     */
    val pairedBoardId: String?

    /**
     * Current link state. Reflects whether Tern can currently exchange
     * packets with the paired board. See [LinkState] for the values and
     * what each one means for the UI.
     */
    val linkState: LinkState

    /**
     * Negotiated MTU of the active link, or null when no link / unknown.
     * Surfaced for the BLE-reliability test suite (T4).
     */
    fun negotiatedMtu(): Int? = null

    /**
     * Active BLE PHY (BluetoothDevice.PHY_LE_1M / PHY_LE_2M / PHY_LE_CODED),
     * or null when not over BLE / unknown. Surfaced for F5.
     */
    fun activePhy(): Int? = null

    /**
     * Cumulative count of heartbeat ToRadio packets Tern has sent on
     * this connection. Used by the T6 BLE-reliability test to verify
     * the periodic heartbeat sender fires while the link is idle.
     */
    fun heartbeatsSent(): Int = 0

    /**
     * TEST-ONLY: trigger a GATT disconnect to exercise the
     * auto-reconnect path. The transport's standard reconnect logic
     * should observe the drop, scan, and reconnect — at which point
     * the handshake re-runs (T3). Production code should never call
     * this; it is a test seam for the BLE reliability suite (T2).
     */
    fun simulateDisconnectForTest() {}

    /**
     * TEST-ONLY: command the board to reboot in [rebootSeconds] seconds
     * (admin reboot over the live link). Unlike [simulateDisconnectForTest]
     * — a graceful local GATT teardown — this is a *real* link loss: the
     * board goes away, re-advertises after boot, and the transport
     * reconnects. Faithful to T2/T3's "board rebooted mid-flight" scenario.
     * Default no-op for non-BLE implementations.
     */
    suspend fun rebootBoardForTest(rebootSeconds: Int = 5) {}

    /**
     * Stream of everything the board hands up to Tern: peer positions,
     * peer telemetry, peer-originated alerts, and link-state transitions.
     *
     * The link-state event is also published on this stream so a single
     * subscriber (the redux middleware in WS2.4) can react in order without
     * a second subscription.
     *
     * Cold: subscribing starts delivery; cancelling stops it. The
     * implementation buffers nothing for late subscribers — a test that
     * needs replay must subscribe before injecting.
     */
    fun events(): Flow<MeshEvent>

    /**
     * Tell the board to broadcast our position over LoRa.
     *
     * Maps to a Meshtastic `MeshPacket` on `PortNum.POSITION_APP` with the
     * payload encoded from [position]. Implementations must not retry
     * silently — position is by nature lossy and frequent; one missed
     * broadcast is fine, a hidden retry storm is not.
     *
     * Returns immediately. Successful queueing onto the board does not
     * guarantee successful transmission over the air — that's a property
     * of LoRa, not of this call.
     *
     * No-op when [linkState] is not [LinkState.UP]. Does not throw.
     */
    suspend fun sendOwnPosition(position: PeerPosition.Fix)

    /**
     * Fire an SOS.
     *
     * Maps to a Meshtastic `MeshPacket` on `PortNum.ALERT_APP` (port 11)
     * with `Priority.ALERT` (110) and `want_ack = true`. Implementations
     * should retransmit until ACK or until [maxRetries] is reached;
     * retransmission cadence is the implementation's call (BLE backpressure
     * differs from the simulator).
     *
     * Unlike [sendOwnPosition], this MUST surface failure to the caller via
     * the returned [SendResult]. The pilot needs to know whether their SOS
     * actually left the board.
     *
     * If [linkState] is not [LinkState.UP] the call returns
     * [SendResult.NoLink] immediately — the caller (the SOS UI) is
     * responsible for falling back to phone satellite SOS in that case
     * (see `project_tern_safety_stack`).
     */
    suspend fun sendAlert(
        lastKnownPosition: PeerPosition.Fix?,
        maxRetries: Int = 3,
    ): SendResult

    /** Result of a send-with-ack operation. See [sendAlert]. */
    sealed interface SendResult {
        /** Board accepted the packet and at least one ACK came back. */
        data object Acked : SendResult

        /** Board accepted the packet but no ACK arrived within retries. */
        data object NoAck : SendResult

        /** Link was not UP at the time of the call. Nothing was sent. */
        data object NoLink : SendResult
    }
}
