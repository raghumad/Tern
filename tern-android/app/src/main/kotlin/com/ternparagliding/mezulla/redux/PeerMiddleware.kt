package com.ternparagliding.mezulla.redux

import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.MeshtasticConnection
import com.ternparagliding.mezulla.connection.PeerIdentity
import com.ternparagliding.mezulla.connection.ble.MeshPacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/**
 * Bridges a [MeshtasticConnection] to a redux dispatcher for [PeerAction].
 *
 * Takes a `dispatch` lambda rather than depending directly on
 * [com.ternparagliding.redux.MapStore] so the middleware is testable
 * without a ViewModel. In production, the caller wires `dispatch` to
 * `MapStore.dispatch(PeerAction)`, which tasks through the sub-reducer
 * into [com.ternparagliding.redux.MapState.peerState].
 *
 * Threading: [start] launches a single collector coroutine on the
 * supplied [scope]. Cancelling that scope cancels the collector cleanly
 * — the [Flow.collect] in [start] returns and no further dispatches
 * happen. Calling [start] again after cancellation throws (a started
 * middleware is single-use) so a test or production caller cannot
 * accidentally double-subscribe.
 *
 * Ordering: events arrive in the order [MeshtasticConnection.events]
 * delivers them. The middleware does not reorder. For events that
 * implicitly register a peer (position/telemetry/alert), the seen-action
 * is dispatched before the type-specific action so the reducer sees
 * the peer registered first.
 */
class PeerMiddleware(
    private val connection: MeshtasticConnection,
    private val dispatch: (PeerAction) -> Unit,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var job: Job? = null

    /**
     * Node numbers confirmed to be NON-Mezulla (public-mesh) nodes — their
     * NodeInfo reported an `hw_model` other than PRIVATE_HW. We drop all their
     * events so a public node can never hold a buddy slot, even via a replayed
     * position that arrived before its NodeInfo. The collector is a single
     * coroutine, so a plain set needs no synchronization.
     */
    private val knownNonMezulla = mutableSetOf<Long>()

    /**
     * Last known named [PeerIdentity] per node, learned from NodeInfo
     * ([MeshEvent.PeerIdentityKnown]). Position/telemetry/alert packets carry only
     * a node number (no name), so when one of those creates a roster entry we look
     * the name up here — otherwise the buddy would render as a bare "!hex" callsign
     * whenever its NodeInfo arrived before (or instead of) its first position, which
     * is the usual order on a connect-time nodeDB replay. Single collector
     * coroutine, so a plain map needs no synchronization.
     */
    private val knownIdentities = mutableMapOf<Long, PeerIdentity>()

    companion object {
        /**
         * A position/telemetry whose own timestamp is older than this on arrival
         * is treated as a cached-nodeDB replay, not live presence (see
         * [handle]). Generous enough never to drop a genuinely-live buddy (they
         * broadcast every few seconds) while rejecting the board's replayed
         * public-mesh stragglers, which carry timestamps from before the team
         * channel was joined.
         */
        private const val REPLAY_STALE_SECONDS = 300L
    }

    /**
     * Subscribe to the connection and start dispatching peer actions.
     * Returns the [Job] of the collector so callers can join/cancel it
     * directly if they prefer that over cancelling [scope].
     */
    fun start(): Job {
        check(job == null) { "PeerMiddleware.start() called twice" }
        val launched = scope.launch {
            connection.events().collect { event -> handle(event) }
        }
        job = launched
        return launched
    }

    private fun handle(event: MeshEvent) {
        val now = Instant.now(clock)
        val node = event.senderNodeNumber()
        // The board's own node is never a buddy — drop it from the roster. But its
        // NodeInfo carries the board's Meshtastic owner name (the OLED name), which
        // the UI needs to label the connected board by its real name instead of a
        // hardcoded string. Capture that into PeerState.selfBoard, then drop.
        if (node != null && node == connection.selfNodeNumber) {
            if (event is MeshEvent.PeerIdentityKnown &&
                (event.peer.longName != null || event.peer.shortName != null)
            ) {
                dispatch(PeerAction.SelfBoardIdentified(event.peer, now))
            }
            return
        }
        // Drop events from nodes confirmed non-Mezulla (public mesh) — EXCEPT a
        // fresh NodeInfo that now advertises PRIVATE_HW, which RE-ADMITS the node.
        // Eviction must NOT be a one-way door: a Mezulla board reflashed from
        // stock firmware changes its advertised hw_model from its real model
        // (e.g. HELTEC_V3) to PRIVATE_HW, and a buddy that cached the pre-reflash
        // NodeInfo would otherwise block it forever — silently killing the core
        // "flash a stock board into a Mezulla" path. Only PeerIdentityKnown
        // carries hw_model, so only it can re-admit.
        if (node != null && node in knownNonMezulla) {
            val readmits = event is MeshEvent.PeerIdentityKnown &&
                event.peer.hwModel == MeshPacketCodec.HW_MODEL_PRIVATE
            if (!readmits) return
            knownNonMezulla.remove(node)
        }
        when (event) {
            is MeshEvent.PeerIdentityKnown -> {
                val hw = event.peer.hwModel
                if (hw != null &&
                    hw != MeshPacketCodec.HW_MODEL_PRIVATE &&
                    hw != MeshPacketCodec.HW_MODEL_UNSET
                ) {
                    // NodeInfo confirms a *real* hardware model that isn't PRIVATE_HW
                    // → a public-mesh node. Remember it so its other events are
                    // dropped, forget any cached name, and evict it if a replayed
                    // position already added it. UNSET is excluded above: it's "not
                    // known yet" (a position-only placeholder), not a confirmed
                    // public node, so it must not be evicted.
                    knownNonMezulla.add(event.peer.nodeNumber)
                    knownIdentities.remove(event.peer.nodeNumber)
                    dispatch(PeerAction.PeerRemoved(event.peer.nodeNumber))
                    return
                }
                // Cache the name so a later position-created roster entry renders it
                // instead of "!hex" (NodeInfo usually arrives before the first
                // position on a connect-time replay).
                if (event.peer.longName != null || event.peer.shortName != null) {
                    knownIdentities[event.peer.nodeNumber] = event.peer
                }
                // Update-only — NodeInfo (incl. the board's NodeDB dump) must
                // not create a roster entry, or non-teammates from the public
                // mesh reappear on every reconnect. A peer joins the roster
                // via live presence (position / telemetry / alert) below.
                dispatch(PeerAction.PeerIdentityUpdate(event.peer, now))
            }

            is MeshEvent.PeerPositionUpdate -> {
                // Reject replays: on connect the board replays its cached nodeDB
                // positions shaped to look exactly like live broadcasts (Mezulla
                // satellite-DB replay) — the only tell is the old timestamp. A
                // position that's already minutes stale isn't live presence, so it
                // must not put a public-mesh straggler on the teammates roster.
                if (isReplayStale(event.fix.timestampSeconds, now)) return
                val identity = namedIdentityFor(event.peer)
                dispatch(PeerAction.PeerSeen(identity, now))
                dispatch(PeerAction.PeerPositionReceived(identity, event.fix, now))
            }

            is MeshEvent.PeerTelemetry -> {
                // Same replay guard as positions — cached telemetry is replayed
                // with its original (old) timestamp and must not register a peer.
                if (isReplayStale(event.timestampSeconds, now)) return
                val identity = namedIdentityFor(event.peer)
                dispatch(PeerAction.PeerSeen(identity, now))
                dispatch(
                    PeerAction.PeerTelemetryReceived(
                        identity = identity,
                        batteryPercent = event.batteryPercent,
                        timestampSeconds = event.timestampSeconds,
                        receivedAt = now,
                    ),
                )
            }

            is MeshEvent.PeerAlert -> {
                val identity = namedIdentityFor(event.peer)
                dispatch(
                    PeerAction.PeerAlertReceived(
                        senderIdentity = identity,
                        lastKnownPosition = event.lastKnownPosition,
                        alertedAt = now,
                    ),
                )
            }

            is MeshEvent.LinkStateChange -> {
                dispatch(PeerAction.LinkStateChanged(event.newState))
            }

            is MeshEvent.ConfigComplete -> {
                // Internal handshake signal — BleConnection consumes it
                // directly to advance the two-stage Meshtastic handshake.
                // Redux doesn't model it; no-op here.
            }
        }
    }

    /**
     * Whether a position/telemetry is NOT live presence — i.e. it's the board
     * replaying a cached nodeDB entry, not a peer we just heard:
     *
     *  - timestamp <= 0 → the entry has no reception time at all. A *live* packet
     *    always carries one (the board stamps rx_time = now when it hears one
     *    over the air); only a cached replay whose stored `pos.time` was 0 comes
     *    through timeless. So a missing timestamp is itself the "replay" tell.
     *  - timestamp older than [REPLAY_STALE_SECONDS] → a stale cached fix.
     *
     * Either way it must not register a public-mesh straggler as a buddy.
     */
    private fun isReplayStale(timestampSeconds: Long, now: Instant): Boolean =
        timestampSeconds <= 0 || (now.epochSecond - timestampSeconds) > REPLAY_STALE_SECONDS

    /**
     * The best identity to register a peer under: prefer a cached named identity
     * from a prior NodeInfo ([knownIdentities]) so the roster shows the board name
     * rather than a bare "!hex" callsign. Falls back to the event's own identity
     * (position/telemetry/alert carry only a node number) when no name is known yet
     * — a later NodeInfo then fills the name in via [PeerAction.PeerIdentityUpdate].
     */
    private fun namedIdentityFor(peer: PeerIdentity): PeerIdentity {
        val cached = knownIdentities[peer.nodeNumber]
        return if (cached != null && (cached.longName != null || cached.shortName != null)) {
            cached
        } else {
            peer.orSynthesize()
        }
    }

    /**
     * The node number an event is *about*, or null for events that carry no
     * peer (link-state, config-complete). Used to filter out the board's own
     * node — see [handle].
     */
    private fun MeshEvent.senderNodeNumber(): Long? = when (this) {
        is MeshEvent.PeerIdentityKnown -> peer.nodeNumber
        is MeshEvent.PeerPositionUpdate -> peer.nodeNumber
        is MeshEvent.PeerTelemetry -> peer.nodeNumber
        is MeshEvent.PeerAlert -> peer.nodeNumber
        is MeshEvent.LinkStateChange -> null
        is MeshEvent.ConfigComplete -> null
    }

    /**
     * Defensive: a buggy transport could in principle hand us an
     * identity that didn't go through [PeerIdentity.fromNodeNumber]. If
     * the [PeerIdentity.hexId] looks wrong we rebuild it from
     * [PeerIdentity.nodeNumber] so downstream code can rely on
     * `!aabbccdd`. In practice the existing transports already do this
     * — this is belt-and-braces.
     */
    private fun PeerIdentity.orSynthesize(): PeerIdentity =
        if (hexId.startsWith("!") && hexId.length == 9) {
            this
        } else {
            PeerIdentity.fromNodeNumber(nodeNumber, longName, shortName)
        }
}
