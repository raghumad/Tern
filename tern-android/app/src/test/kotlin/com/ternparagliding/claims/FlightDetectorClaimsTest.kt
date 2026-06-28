package com.ternparagliding.claims

import com.ternparagliding.flight.FlightDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claim-driven tests for the **follow-cam airborne gate** ([FlightDetector]).
 *
 * Pilot-visible promise: the map does not grab control while the pilot is on launch. The detector
 * is the pure decision behind that — sitting still stays "grounded"; sustained motion or height gain
 * flips "airborne" and latches it for the session. A lone GPS speed blip at rest must not trip it.
 */
class FlightDetectorClaimsTest {

    private fun fold(
        vararg fixes: Pair<Double?, Double?>, // (groundSpeedMs, heightAboveTakeoffM)
        start: FlightDetector.State = FlightDetector.State(),
    ): FlightDetector.State =
        fixes.fold(start) { s, (spd, h) -> FlightDetector.update(s, spd, h) }

    @Test
    fun `sitting on launch stays grounded`() {
        // Rigging up: near-zero ground speed, no height gained over the launch datum.
        val s = fold(0.1 to 0.0, 0.2 to -1.0, 0.0 to 0.5, 0.3 to 0.0, 0.1 to -0.5)
        assertFalse("on the ground the follow-cam must stay hands-off", s.airborne)
    }

    @Test
    fun `a single GPS speed spike at rest does not launch`() {
        // One noisy fix reads fast, then back to standing still. Must not flip airborne.
        val s = fold(0.1 to 0.0, 5.0 to 0.0, 0.1 to 0.0, 0.2 to 0.0)
        assertFalse("a lone speed blip is not flight", s.airborne)
    }

    @Test
    fun `sustained ground speed flips airborne`() {
        // A sledride off launch: forward speed builds and holds across the confirm streak.
        val s = fold(3.0 to 0.0, 4.0 to -2.0, 5.0 to -5.0)
        assertTrue("sustained motion is flight", s.airborne)
    }

    @Test
    fun `climbing out in light wind flips airborne even with near-zero ground speed`() {
        // Ridge/thermal soaring into wind: parked over the ground, but climbing well above takeoff.
        val s = fold(0.5 to 13.0, 0.4 to 14.5, 0.6 to 16.0)
        assertTrue("height gained above takeoff is flight", s.airborne)
    }

    @Test
    fun `airborne latches once set`() {
        val airborne = fold(4.0 to 0.0, 4.0 to 0.0, 4.0 to 0.0)
        assertTrue(airborne.airborne)
        // Slowing to a crawl mid-flight (top-landing approach) must NOT drop back to grounded.
        val still = FlightDetector.update(airborne, 0.0, 0.0)
        assertTrue("a mid-flight latch must not flicker off", still.airborne)
    }

    @Test
    fun `confirm streak resets on a non-qualifying fix`() {
        // Two fast fixes then a stop, twice — never three in a row, so never airborne.
        val s = fold(4.0 to 0.0, 4.0 to 0.0, 0.0 to 0.0, 4.0 to 0.0, 4.0 to 0.0, 0.0 to 0.0)
        assertFalse(s.airborne)
    }

    @Test
    fun `null sensor values never qualify on their own`() {
        val s = fold(null to null, null to null, null to null)
        assertFalse("no speed and no height is not flight", s.airborne)
    }
}
