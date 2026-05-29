package com.ternparagliding.mezulla.redux

import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.PeerPosition
import java.time.Instant

/**
 * Actions that mutate [PeerState].
 *
 * Each variant maps to one cause: either an inbound mesh event
 * (translated by [PeerMiddleware]) or a UI action the pilot takes (the
 * acknowledge button — that one is dispatched from WS3, not from
 * middleware).
 *
 * The reducer is total over this sealed type — every variant has an
 * explicit branch and no branch throws.
 */
sealed interface PeerAction : com.ternparagliding.redux.TernAction {

    /**
     * A peer was heard from. Used to register a peer the moment any
     * event mentions them, including before their first position fix.
     * Idempotent: dispatching this for an already-known peer updates
     * [lastSeenAt] and replaces [identity] (which lets late-arriving
     * NodeInfo fill in [PeerIdentity.longName] / [PeerIdentity.shortName]
     * without dropping existing position/telemetry).
     */
    data class PeerSeen(
        val identity: PeerIdentity,
        val seenAt: Instant,
    ) : PeerAction

    /**
     * Recorded position fix for a peer. Replaces [KnownPeer.lastPosition].
     */
    data class PeerPositionReceived(
        val identity: PeerIdentity,
        val fix: PeerPosition.Fix,
        val receivedAt: Instant,
    ) : PeerAction

    /**
     * Recorded telemetry for a peer. Replaces [KnownPeer.lastTelemetry].
     */
    data class PeerTelemetryReceived(
        val identity: PeerIdentity,
        val batteryPercent: Int?,
        val timestampSeconds: Long,
        val receivedAt: Instant,
    ) : PeerAction

    /**
     * A peer fired an SOS. Appends to [PeerState.activeAlerts] and also
     * acts as a [PeerSeen] for the sender so the SOS UI can show a marker
     * even if no prior event registered them.
     */
    data class PeerAlertReceived(
        val senderIdentity: PeerIdentity,
        val lastKnownPosition: PeerPosition.Fix?,
        val alertedAt: Instant,
    ) : PeerAction

    /**
     * The pilot acknowledged a peer's SOS in the UI. Sets
     * [ActiveAlert.acknowledgedAt] on the most recent unacknowledged
     * alert from the matching sender. If no matching unacknowledged
     * alert exists, the action is a no-op (does not throw).
     */
    data class PeerAlertAcknowledged(
        val senderNodeNumber: Long,
        val acknowledgedAt: Instant,
    ) : PeerAction

    /** The LoRa link transitioned (NEVER_PAIRED / DOWN / UP). */
    data class LinkStateChanged(val newState: LinkState) : PeerAction
}
