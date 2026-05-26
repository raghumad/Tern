package com.ternparagliding.sim.swarm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ternparagliding.sim.igc.IgcFix
import com.ternparagliding.sim.igc.IgcFlight
import com.ternparagliding.sim.swarm.scenarios.AravisTeam2026
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Tests for [SwarmPlayback].
 *
 * Most assertions go against the real Aravis IGC files so the engine
 * is exercised on real-world data shapes (~40k fixes per pilot, real
 * pilot-staggered launches, etc.). A handful of tightly-controlled
 * synthetic-flight assertions cover the interpolation arithmetic and
 * the edge cases where real data would be either ambiguous or too
 * expensive to set up.
 */
class SwarmPlaybackTest {

    // ---- Real-data assertions on the Aravis scenario --------------

    @Test
    fun `currentPosition at a pilot's first fix returns that exact fix`() {
        val playback = SwarmPlayback(AravisTeam2026.scenario)
        for (id in playback.pilots) {
            val state = playback.pilot(id)
            val firstFix = state.flight.fixes.first()
            val pos = playback.currentPosition(id, firstFix.timestamp)
            assertWithMessage("first-fix position for $id").that(pos).isNotNull()
            pos!!
            assertWithMessage("first-fix should not be interpolated for $id")
                .that(pos.isInterpolated)
                .isFalse()
            assertThat(pos.latitude).isWithin(1e-12).of(firstFix.latitude)
            assertThat(pos.longitude).isWithin(1e-12).of(firstFix.longitude)
            assertThat(pos.altitudeMeters).isEqualTo(firstFix.gpsAltitude)
            assertThat(pos.sourceFixTimestamp).isEqualTo(firstFix.timestamp)
        }
    }

    @Test
    fun `currentPosition at a pilot's last fix returns that exact fix`() {
        val playback = SwarmPlayback(AravisTeam2026.scenario)
        for (id in playback.pilots) {
            val state = playback.pilot(id)
            val lastFix = state.flight.fixes.last()
            val pos = playback.currentPosition(id, lastFix.timestamp)
            assertWithMessage("last-fix position for $id").that(pos).isNotNull()
            pos!!
            assertWithMessage("last-fix should not be interpolated for $id")
                .that(pos.isInterpolated)
                .isFalse()
            assertThat(pos.latitude).isWithin(1e-12).of(lastFix.latitude)
            assertThat(pos.longitude).isWithin(1e-12).of(lastFix.longitude)
            assertThat(pos.altitudeMeters).isEqualTo(lastFix.gpsAltitude)
        }
    }

    @Test
    fun `pre-launch and post-landing queries return null`() {
        val playback = SwarmPlayback(AravisTeam2026.scenario)
        for (id in playback.pilots) {
            val state = playback.pilot(id)
            val beforeFirst = state.firstFixTime.minusSeconds(1)
            val afterLast = state.lastFixTime.plusSeconds(1)
            assertWithMessage("pre-launch position for $id")
                .that(playback.currentPosition(id, beforeFirst))
                .isNull()
            assertWithMessage("post-landing position for $id")
                .that(playback.currentPosition(id, afterLast))
                .isNull()
        }
    }

    @Test
    fun `staggered launch -- lma is still on the ground when tonio24 is airborne`() {
        val playback = SwarmPlayback(AravisTeam2026.scenario)
        val tonio = playback.pilot(AravisTeam2026.TONIO24)
        val lma = playback.pilot(AravisTeam2026.LMA)

        // Real first-fix timestamps from the IGC files put lma's
        // recorder starting ~22 min after tonio24's. Sample one
        // minute past tonio24's first fix — well before lma's.
        val sample = tonio.firstFixTime.plusSeconds(60)
        assertWithMessage("sanity: chosen sample must precede lma's first fix")
            .that(sample.isBefore(lma.firstFixTime))
            .isTrue()

        val tonioPos = playback.currentPosition(AravisTeam2026.TONIO24, sample)
        val lmaPos = playback.currentPosition(AravisTeam2026.LMA, sample)
        assertWithMessage("tonio24 at $sample").that(tonioPos).isNotNull()
        assertWithMessage("lma at $sample").that(lmaPos).isNull()
    }

    @Test
    fun `global clock iterator covers swarm window with monotonic timestamps`() {
        val playback = SwarmPlayback(
            AravisTeam2026.scenario,
            tickInterval = Duration.ofSeconds(60),
        )

        // Take a short slice -- the first 30 minutes of the swarm --
        // so the assertion is fast and the expected count is exact.
        val window = Duration.ofMinutes(30)
        val end = playback.swarmStart.plus(window)
        val ticks = playback.ticks(from = playback.swarmStart, until = end).toList()

        // 30 min at 60s = 31 ticks (inclusive both ends).
        assertThat(ticks).hasSize(31)
        assertThat(ticks.first().time).isEqualTo(playback.swarmStart)
        assertThat(ticks.last().time).isEqualTo(end)

        // Monotonic, evenly spaced.
        for (i in 1 until ticks.size) {
            val delta = Duration.between(ticks[i - 1].time, ticks[i].time)
            assertThat(delta).isEqualTo(Duration.ofSeconds(60))
        }

        // Every tick has an entry for every pilot (value may be null).
        for (tick in ticks) {
            assertThat(tick.positions.keys).containsExactlyElementsIn(playback.pilots)
        }
    }

    @Test
    fun `full-scenario iteration at 60s ticks completes without OOM`() {
        // Performance smoke: load the 4-IGC scenario and iterate the
        // entire flight window once. No tight wall-clock bound -- that
        // would flake. Just proves it runs to completion and produces
        // a sane number of ticks for an ~11-hour scenario.
        val playback = SwarmPlayback(
            AravisTeam2026.scenario,
            tickInterval = Duration.ofSeconds(60),
        )
        var tickCount = 0
        var withAtLeastOnePilot = 0
        for (tick in playback.ticks()) {
            tickCount++
            if (tick.positions.values.any { it != null }) {
                withAtLeastOnePilot++
            }
        }
        // ~11 hours of flight @ 60s ticks -> hundreds of ticks.
        assertThat(tickCount).isGreaterThan(600)
        // Every tick in the swarm window has at least one pilot
        // active, because the window endpoints are exactly the
        // earliest first-fix and latest last-fix of the bundle.
        assertThat(withAtLeastOnePilot).isEqualTo(tickCount)
    }

    // ---- Synthetic-flight assertions for interpolation arithmetic --

    @Test
    fun `linear interpolation halfway between two fixes returns the midpoint`() {
        // Two fixes 10 seconds apart, 0.010 deg apart in lat/lon,
        // 100 m apart in altitude. Halfway through should land
        // exactly on the midpoint.
        val date = LocalDate.of(2026, 1, 1)
        val t0 = Instant.parse("2026-01-01T12:00:00Z")
        val t10 = Instant.parse("2026-01-01T12:00:10Z")
        val flight = IgcFlight(
            date = date,
            fixes = listOf(
                IgcFix(t0, 45.000, 6.000, 0, 1000, true),
                IgcFix(t10, 45.010, 6.010, 0, 1100, true),
            ),
        )
        val pilot = PilotId("synthetic")
        val playback = oneFlightPlayback(pilot, flight)

        val midpoint = playback.currentPosition(pilot, t0.plusSeconds(5))!!
        assertThat(midpoint.isInterpolated).isTrue()
        assertThat(midpoint.latitude).isWithin(1e-12).of(45.005)
        assertThat(midpoint.longitude).isWithin(1e-12).of(6.005)
        assertThat(midpoint.altitudeMeters).isEqualTo(1050)
        // Source fix is the earlier of the bracketing pair.
        assertThat(midpoint.sourceFixTimestamp).isEqualTo(t0)
    }

    @Test
    fun `interpolation at quarter and three-quarter points is correct`() {
        val date = LocalDate.of(2026, 1, 1)
        val t0 = Instant.parse("2026-01-01T12:00:00Z")
        val t8 = Instant.parse("2026-01-01T12:00:08Z")
        val flight = IgcFlight(
            date = date,
            fixes = listOf(
                IgcFix(t0, 0.0, 0.0, 0, 0, true),
                IgcFix(t8, 8.0, 16.0, 0, 800, true),
            ),
        )
        val pilot = PilotId("synthetic")
        val playback = oneFlightPlayback(pilot, flight)

        val quarter = playback.currentPosition(pilot, t0.plusSeconds(2))!!
        assertThat(quarter.latitude).isWithin(1e-12).of(2.0)
        assertThat(quarter.longitude).isWithin(1e-12).of(4.0)
        assertThat(quarter.altitudeMeters).isEqualTo(200)

        val threeQuarter = playback.currentPosition(pilot, t0.plusSeconds(6))!!
        assertThat(threeQuarter.latitude).isWithin(1e-12).of(6.0)
        assertThat(threeQuarter.longitude).isWithin(1e-12).of(12.0)
        assertThat(threeQuarter.altitudeMeters).isEqualTo(600)
    }

    @Test
    fun `exact fix-time query is not flagged as interpolated`() {
        val date = LocalDate.of(2026, 1, 1)
        val t0 = Instant.parse("2026-01-01T12:00:00Z")
        val t10 = Instant.parse("2026-01-01T12:00:10Z")
        val flight = IgcFlight(
            date = date,
            fixes = listOf(
                IgcFix(t0, 45.0, 6.0, 0, 1000, true),
                IgcFix(t10, 45.1, 6.1, 0, 1100, true),
            ),
        )
        val pilot = PilotId("synthetic")
        val playback = oneFlightPlayback(pilot, flight)

        val atFix = playback.currentPosition(pilot, t10)!!
        assertThat(atFix.isInterpolated).isFalse()
        assertThat(atFix.altitudeMeters).isEqualTo(1100)
    }

    private fun oneFlightPlayback(pilotId: PilotId, flight: IgcFlight): SwarmPlayback {
        val scenario = Scenario(
            name = "synthetic",
            location = "",
            date = flight.date,
            region = "xx",
            pilots = listOf(
                ScenarioPilot(
                    id = pilotId,
                    displayName = pilotId.value,
                    igcResourcePath = "/unused-for-override",
                ),
            ),
            notes = "",
        )
        return SwarmPlayback(scenario, flightsOverride = mapOf(pilotId to flight))
    }
}
