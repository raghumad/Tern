package com.ternparagliding.mezulla.redux

import com.ternparagliding.mezulla.connection.MeshEvent
import com.ternparagliding.mezulla.connection.MeshtasticConnection
import com.ternparagliding.mezulla.connection.PeerIdentity
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
 * `MapStore.dispatch(PeerAction)`, which routes through the sub-reducer
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
        when (event) {
            is MeshEvent.PeerIdentityKnown -> {
                dispatch(PeerAction.PeerSeen(event.peer, now))
            }

            is MeshEvent.PeerPositionUpdate -> {
                val identity = event.peer.orSynthesize()
                dispatch(PeerAction.PeerSeen(identity, now))
                dispatch(PeerAction.PeerPositionReceived(identity, event.fix, now))
            }

            is MeshEvent.PeerTelemetry -> {
                val identity = event.peer.orSynthesize()
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
                val identity = event.peer.orSynthesize()
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
