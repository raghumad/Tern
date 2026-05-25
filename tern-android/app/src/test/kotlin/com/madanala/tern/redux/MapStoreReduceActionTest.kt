package com.madanala.tern.redux

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.mezulla.redux.PeerAction
import com.madanala.tern.mezulla.connection.PeerIdentity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Verifies that [reduceAction] handles every known action family and
 * rejects unknown ones. This is a safety-critical test: if someone adds
 * a new [TernAction] subtype and forgets to wire it into the reducer,
 * the debug-mode throw caught here prevents a silent action drop that
 * could swallow an SOS alert.
 */
class MapStoreReduceActionTest {

    private val initialState = MapState()

    // -- Known action families are dispatched to their reducers ----------

    @Test
    fun `MapAction is routed to mapReducer`() {
        val result = reduceAction(initialState, MapAction.SetLocationReady(true))
        assertThat(result.isLocationReady).isTrue()
    }

    @Test
    fun `WeatherActions is routed to weatherReducer`() {
        val result = reduceAction(initialState, WeatherActions.SetWeatherGaugeEnabled(false))
        assertThat(result.weatherState.showWeatherGauges).isFalse()
    }

    @Test
    fun `PeerAction is routed to peerReducer`() {
        val identity = PeerIdentity.fromNodeNumber(42)
        val now = Instant.now()
        val result = reduceAction(initialState, PeerAction.PeerSeen(identity, now))
        assertThat(result.peerState.peers).containsKey(42L)
    }

    // -- Unknown TernAction type returns state unchanged (with Log.w) ----

    /** Test-only action type that nobody handles. */
    private data class UnhandledTestAction(val payload: String) : TernAction

    @Test
    fun `unknown TernAction returns state unchanged`() {
        val result = reduceAction(initialState, UnhandledTestAction("unhandled"))
        assertThat(result).isEqualTo(initialState)
    }
}
