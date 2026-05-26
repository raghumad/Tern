package com.ternparagliding.sim.swarm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ternparagliding.sim.swarm.scenarios.AravisTeam2026
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/**
 * Tests for [ScenarioLoader] and the [Scenario] data class.
 *
 * The Aravis scenario is the only real-data scenario in the repo right
 * now; it doubles as the loader's integration test against actual IGC
 * files on the classpath.
 */
class ScenarioLoaderTest {

    @Test
    fun `Aravis scenario lists the four expected pilots in order`() {
        val s = AravisTeam2026.scenario
        assertThat(s.name).isEqualTo("aravis-team-2026-04-25")
        assertThat(s.date).isEqualTo(LocalDate.of(2026, 4, 25))
        assertThat(s.region).isEqualTo("fr")
        assertThat(s.pilots.map { it.id.value }).containsExactly(
            "tonio24", "cbe", "cor", "lma",
        ).inOrder()
    }

    @Test
    fun `Aravis scenario resolves all four IGCs off the classpath`() {
        val flights = ScenarioLoader.load(AravisTeam2026.scenario)
        assertThat(flights.keys.map { it.value })
            .containsExactly("tonio24", "cbe", "cor", "lma")
        // Each pilot has tens of thousands of fixes — a non-trivial
        // sanity check that we got real files, not empty ones.
        for ((id, flight) in flights) {
            assertWithMessage("fixes for $id")
                .that(flight.fixes.size)
                .isGreaterThan(10_000)
            assertThat(flight.date).isEqualTo(LocalDate.of(2026, 4, 25))
        }
    }

    @Test
    fun `loader fails fast when an IGC resource is missing`() {
        val broken = Scenario(
            name = "broken",
            location = "nowhere",
            date = LocalDate.of(2026, 1, 1),
            region = "xx",
            pilots = listOf(
                ScenarioPilot(
                    id = PilotId("ghost"),
                    displayName = "ghost",
                    igcResourcePath = "/igc/flights/xx/does-not-exist.igc",
                ),
            ),
            notes = "",
        )
        val ex = assertThrows<ScenarioLoadException> { ScenarioLoader.load(broken) }
        assertThat(ex.message).contains("ghost")
        assertThat(ex.message).contains("does-not-exist.igc")
    }

    @Test
    fun `Scenario rejects duplicate pilot ids`() {
        assertThrows<IllegalArgumentException> {
            Scenario(
                name = "dupes",
                location = "",
                date = LocalDate.of(2026, 1, 1),
                region = "xx",
                pilots = listOf(
                    ScenarioPilot(PilotId("a"), "a", "/x.igc"),
                    ScenarioPilot(PilotId("a"), "a2", "/y.igc"),
                ),
                notes = "",
            )
        }
    }

    @Test
    fun `Scenario rejects empty pilot list`() {
        assertThrows<IllegalArgumentException> {
            Scenario(
                name = "empty",
                location = "",
                date = LocalDate.of(2026, 1, 1),
                region = "xx",
                pilots = emptyList(),
                notes = "",
            )
        }
    }
}
