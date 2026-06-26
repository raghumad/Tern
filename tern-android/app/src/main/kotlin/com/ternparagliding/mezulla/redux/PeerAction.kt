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

    /**
     * Identity (name) info arrived for a peer — a NodeInfo broadcast or an
     * entry from the board's NodeDB dump on handshake. UPDATE-ONLY: it
     * refreshes an existing peer's [PeerIdentity] and [lastSeenAt] but does
     * NOT create a roster entry.
     *
     * A peer earns a roster slot only through live presence (position /
     * telemetry / alert). The board's NodeDB persists every node it has ever
     * heard (e.g. the whole public mesh from before the pilot joined a private
     * team) and re-streams it as NodeInfo on each reconnect; if bare NodeInfo
     * created peers, those non-teammates would keep repopulating the roster.
     */
    data class PeerIdentityUpdate(
        val identity: PeerIdentity,
        val seenAt: Instant,
    ) : PeerAction

    /**
     * Evict a peer by node number. Dispatched when a node is confirmed to be a
     * non-Mezulla (public-mesh) node — its NodeInfo reports an `hw_model` other
     * than PRIVATE_HW — so it must not occupy a buddy slot even if a (replayed)
     * position registered it first. No-op if the node isn't on the roster.
     */
    data class PeerRemoved(val nodeNumber: Long) : PeerAction

    /**
     * The connected board identified itself — its own NodeInfo (carrying the
     * Meshtastic owner name shown on the board's OLED) arrived on connect. Sets
     * [PeerState.selfBoard] so the UI can label the board by its real name
     * instead of a hardcoded string. The board's own node is never a roster
     * peer; this is the one place its identity is kept.
     */
    data class SelfBoardIdentified(
        val identity: PeerIdentity,
        val at: Instant,
    ) : PeerAction

    /** The LoRa link transitioned (NEVER_PAIRED / DOWN / UP). */
    data class LinkStateChanged(val newState: LinkState) : PeerAction

    /**
     * Drop every known peer and active alert. Dispatched when the board
     * changes LoRa channel (team change): peers heard on the *previous*
     * channel are no longer reachable and aren't on the pilot's team, so
     * they must not linger in the roster. Without this the roster keeps the
     * whole public mesh forever after a pilot joins a private team — the
     * peer map otherwise only ever grows (see [PeerState.peers]).
     */
    data object PeersCleared : PeerAction
}
