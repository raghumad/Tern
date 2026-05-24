package com.madanala.tern.mezulla.redux

import com.madanala.tern.mezulla.connection.LinkState
import com.madanala.tern.mezulla.connection.PeerIdentity
import com.madanala.tern.mezulla.connection.PeerPosition
import java.time.Instant

/**
 * The slice of Tern's redux state that holds everything coming from the
 * paired Meshtastic board: known peers, active SOS alerts, and the current
 * link state.
 *
 * Hosted as a sub-state inside [com.madanala.tern.redux.MapState.peerState].
 * This keeps the store topology simple (one MapStore, one state tree) while
 * preserving the reducer's independence: [peerReducer] is a standalone pure
 * function with its own unit tests, called as a sub-reducer by MapStore.
 *
 * Graceful degradation: [empty] is the always-safe default. It is what
 * the UI sees before any LoRa event has arrived, before any board has
 * been paired, and when no peers are in range. A renderer that consumes
 * [PeerState.empty] must produce no nulls and no crashes — that is the
 * "no LoRa board" pilot experience required by
 * `project_tern_graceful_degradation`.
 */
data class PeerState(
    /**
     * Every peer we have heard from since this state was created, keyed by
     * stable Meshtastic node number. A peer enters the map on the first
     * event that mentions it (NodeInfo, position, telemetry, or alert)
     * and never leaves on its own — staleness is a render-time concern
     * (WS3), not a state-shape concern.
     */
    val peers: Map<Long, KnownPeer> = emptyMap(),

    /**
     * SOS alerts received from peers, in the order they arrived. An entry
     * is kept on the list after it has been acknowledged (its
     * [ActiveAlert.acknowledgedAt] is set) so the UI can show a fading
     * "was alerting" indicator if it wants to — the WS3 UI decides what
     * to do with acknowledged entries; the state keeps the history.
     */
    val activeAlerts: List<ActiveAlert> = emptyList(),

    /**
     * Current LoRa link state. Defaults to [LinkState.NEVER_PAIRED] so a
     * fresh install with no board surfaces nothing — same rationale as
     * the [LinkState] enum doc.
     */
    val linkState: LinkState = LinkState.NEVER_PAIRED,
) {
    companion object {
        /** Always-safe starting state. See class doc. */
        fun empty(): PeerState = PeerState()
    }
}

/**
 * One peer pilot Tern knows about. The [identity] is updated as more
 * information arrives (e.g. NodeInfo lands after the first position) so
 * the redux key (node number) stays stable while the displayable
 * name fields fill in.
 */
data class KnownPeer(
    val identity: PeerIdentity,
    val lastPosition: PeerPosition.Fix? = null,
    val lastTelemetry: PeerTelemetrySnapshot? = null,
    val lastSeenAt: Instant,

    /**
     * Derived climb rate in m/s from the two most recent position fixes.
     * Positive = climbing, negative = sinking. Null when fewer than two
     * fixes with altitude have arrived.
     */
    val climbRateMs: Double? = null,

    /**
     * Previous altitude (meters) used for climb-rate derivation.
     * Kept on the peer so the reducer can compute the delta when the
     * next fix arrives, without maintaining a separate history buffer.
     */
    val previousAltitude: Int? = null,

    /**
     * Timestamp of the previous fix used for climb-rate derivation.
     */
    val previousFixAt: Instant? = null,
)

/**
 * Compact form of [com.madanala.tern.mezulla.connection.MeshEvent.PeerTelemetry]
 * stored on a [KnownPeer]. We drop the [PeerIdentity] because the parent
 * [KnownPeer] already holds it; we keep just the fields the UI displays.
 */
data class PeerTelemetrySnapshot(
    val batteryPercent: Int?,
    val timestampSeconds: Long,
)

/**
 * One SOS alert in progress.
 *
 * [lastKnownPosition] is null when the sender's board had no GPS fix at
 * the moment they triggered — the UI then surfaces "position unknown"
 * (per the project mission of turning unknowns into known unknowns
 * rather than hiding them).
 *
 * [acknowledgedAt] is null until the pilot taps "acknowledge" on the
 * WS3 UI. The reducer does not auto-clear: the SOS pilot decides when
 * the alert is dealt with, not the app.
 */
data class ActiveAlert(
    val senderIdentity: PeerIdentity,
    val lastKnownPosition: PeerPosition.Fix?,
    val alertedAt: Instant,
    val acknowledgedAt: Instant? = null,
)
