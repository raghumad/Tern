package com.ternparagliding.mezulla.redux

import com.ternparagliding.mezulla.connection.PeerIdentity
import java.time.Instant

/**
 * Pure reducer for [PeerState]. Total over [PeerAction]: every variant
 * has a branch, no branch throws.
 *
 * Threading: pure function, safe to call from any thread. Side effects
 * (subscribing to the mesh, dispatching) live in [PeerMiddleware].
 */
fun peerReducer(state: PeerState, action: PeerAction): PeerState {
    val eventTime = when (action) {
        is PeerAction.PeerSeen -> action.seenAt
        is PeerAction.PeerPositionReceived -> action.receivedAt
        is PeerAction.PeerTelemetryReceived -> action.receivedAt
        is PeerAction.PeerAlertReceived -> action.alertedAt
        is PeerAction.PeerAlertAcknowledged -> action.acknowledgedAt
        is PeerAction.LinkStateChanged -> state.lastEventTime
    }
    val newState = peerReduceAction(state, action)
    return if (eventTime.isAfter(state.lastEventTime)) newState.copy(lastEventTime = eventTime) else newState
}

private fun peerReduceAction(state: PeerState, action: PeerAction): PeerState = when (action) {
    is PeerAction.PeerSeen -> state.withPeerSeen(action.identity, action.seenAt)

    is PeerAction.PeerPositionReceived -> {
        val updatedPeers = state.peers.upsert(action.identity, action.receivedAt) { existing ->
            val newAlt = action.fix.altitudeMeters
            val prevAlt = existing.lastPosition?.altitudeMeters
            val prevFixAt = existing.lastSeenAt

            // Derive climb rate from successive fixes when both have altitude.
            val climbRate = if (newAlt != null && prevAlt != null) {
                val dtSeconds = java.time.Duration.between(prevFixAt, action.receivedAt).seconds.toDouble()
                if (dtSeconds > 0.0) (newAlt - prevAlt).toDouble() / dtSeconds else null
            } else {
                null
            }

            existing.copy(
                lastPosition = action.fix,
                climbRateMs = climbRate ?: existing.climbRateMs,
                previousAltitude = prevAlt ?: existing.previousAltitude,
                previousFixAt = prevFixAt,
            )
        }
        state.copy(peers = updatedPeers)
    }

    is PeerAction.PeerTelemetryReceived -> {
        val snapshot = PeerTelemetrySnapshot(
            batteryPercent = action.batteryPercent,
            timestampSeconds = action.timestampSeconds,
        )
        val updatedPeers = state.peers.upsert(action.identity, action.receivedAt) { existing ->
            existing.copy(lastTelemetry = snapshot)
        }
        state.copy(peers = updatedPeers)
    }

    is PeerAction.PeerAlertReceived -> {
        // Also register the sender as a seen peer, so the SOS UI can put a
        // marker even if no PeerIdentityKnown / position arrived first.
        val peersWithSender = state.peers.upsert(action.senderIdentity, action.alertedAt) {
            // Don't overwrite an existing peer's recorded position with
            // null just because the alert had no fix; PeerAlertReceived
            // only updates lastSeenAt + identity here. The
            // lastKnownPosition on the alert itself is what the SOS UI
            // reads — it lives on ActiveAlert, not on KnownPeer.
            it
        }
        val newAlert = ActiveAlert(
            senderIdentity = action.senderIdentity,
            lastKnownPosition = action.lastKnownPosition,
            alertedAt = action.alertedAt,
        )
        state.copy(
            peers = peersWithSender,
            activeAlerts = state.activeAlerts + newAlert,
        )
    }

    is PeerAction.PeerAlertAcknowledged -> {
        // Acknowledge the most recent unacknowledged alert from this sender.
        // Walk the list from the end; if none match, do nothing.
        val idx = state.activeAlerts.indexOfLast {
            it.senderIdentity.nodeNumber == action.senderNodeNumber && it.acknowledgedAt == null
        }
        if (idx < 0) {
            state
        } else {
            val updated = state.activeAlerts.toMutableList().also {
                it[idx] = it[idx].copy(acknowledgedAt = action.acknowledgedAt)
            }
            state.copy(activeAlerts = updated)
        }
    }

    is PeerAction.LinkStateChanged -> state.copy(linkState = action.newState)
}

/**
 * Insert or update a peer keyed by node number. The [merge] callback
 * receives the existing [KnownPeer] (or a freshly-constructed empty one
 * if there isn't one) and returns the post-event peer. The reducer
 * always advances `lastSeenAt` to [seenAt] and always replaces
 * [KnownPeer.identity] with [identity] — the latter is how late-arriving
 * NodeInfo fills in long/short name without dropping the existing
 * position fix.
 */
private fun Map<Long, KnownPeer>.upsert(
    identity: PeerIdentity,
    seenAt: Instant,
    merge: (KnownPeer) -> KnownPeer,
): Map<Long, KnownPeer> {
    val existing = this[identity.nodeNumber] ?: KnownPeer(
        identity = identity,
        lastSeenAt = seenAt,
    )
    val merged = merge(existing).copy(
        identity = identity,
        lastSeenAt = seenAt,
    )
    return this + (identity.nodeNumber to merged)
}

/** Convenience extension used only by [PeerAction.PeerSeen]. */
private fun PeerState.withPeerSeen(identity: PeerIdentity, seenAt: Instant): PeerState {
    val updated = peers.upsert(identity, seenAt) { it }
    return copy(peers = updated)
}
