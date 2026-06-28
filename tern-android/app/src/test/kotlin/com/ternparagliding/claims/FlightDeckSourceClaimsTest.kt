package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.flight.SensorFix
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.PositionSource
import com.ternparagliding.redux.mapReducer
import org.junit.Test

/**
 * Claim **K7 · Resilient (source ladder).** Own-position authority climbs the quality/battery
 * ladder: a *positioned* vario fix promotes the source to the XC Tracer (better data, lets the
 * phone GPS power down); losing the link drops back to the phone. A vario sample without a GPS
 * lock (the vario streams a climb the instant it powers on) must NOT promote, and must not
 * demote once promoted. Drives the real `mapReducer` and asserts the pilot-facing source state.
 */
class FlightDeckSourceClaimsTest {

    private fun fix(lat: Double? = null, lon: Double? = null, climb: Double? = 1.0) =
        SensorFix(timeMs = 1_000L, lat = lat, lon = lon, gpsAltitudeM = 2000.0, climbMs = climb)

    private fun varioFix(fix: SensorFix) = MapAction.UpdateVarioFix(fix, windFromDeg = null, windSpeedMs = null)

    @Test
    fun `default own-position source is the phone`() {
        assertThat(MapState().flightDeck.positionSource).isEqualTo(PositionSource.PHONE)
    }

    @Test
    fun `a positioned vario fix promotes the source to the XC Tracer`() {
        val s = mapReducer(MapState(), varioFix(fix(lat = 46.0, lon = 6.0)))
        assertThat(s.flightDeck.positionSource).isEqualTo(PositionSource.XC_TRACER)
        // ...and that fix becomes own-position (the phone GPS can stand down).
        assertThat(s.userLocation?.latitude).isEqualTo(46.0)
        assertThat(s.isLocationReady).isTrue()
    }

    @Test
    fun `a vario-only sample with no GPS lock does not promote the source`() {
        // The vario streams a climb the instant it powers on, before any GPS fix.
        val s = mapReducer(MapState(), varioFix(fix(lat = null, lon = null, climb = 2.5)))
        assertThat(s.flightDeck.positionSource).isEqualTo(PositionSource.PHONE)
        assertThat(s.flightDeck.climbMs).isEqualTo(2.5) // the vario read is still kept
    }

    @Test
    fun `losing the vario link falls the source back to the phone`() {
        val onTracer = mapReducer(MapState(), varioFix(fix(lat = 46.0, lon = 6.0)))
        assertThat(onTracer.flightDeck.positionSource).isEqualTo(PositionSource.XC_TRACER)

        val dropped = mapReducer(onTracer, MapAction.SetVarioLinkState(connected = false, scanning = true))
        assertThat(dropped.flightDeck.positionSource).isEqualTo(PositionSource.PHONE)
    }

    @Test
    fun `connecting alone does not promote — only a positioned fix does`() {
        // Link up, but no positioned fix yet → still the phone (no premature hand-off).
        val s = mapReducer(MapState(), MapAction.SetVarioLinkState(connected = true, scanning = false))
        assertThat(s.flightDeck.positionSource).isEqualTo(PositionSource.PHONE)
    }

    @Test
    fun `once on the tracer, a later vario-only sample keeps it (no flap)`() {
        val onTracer = mapReducer(MapState(), varioFix(fix(lat = 46.0, lon = 6.0)))
        val varioOnly = mapReducer(onTracer, varioFix(fix(lat = null, lon = null, climb = 3.0)))
        assertThat(varioOnly.flightDeck.positionSource).isEqualTo(PositionSource.XC_TRACER)
    }
}
