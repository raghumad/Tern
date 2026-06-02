package com.ternparagliding.sim.swarm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ternparagliding.sim.swarm.scenarios.BirBilling2025
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Integration check that the three Bir Billing IGCs resolve off the
 * classpath and parse into real tracks with a genuine multi-pilot overlap.
 * Runs on the JVM in seconds — cheap insurance before the ~11 min hardware
 * endurance pass ([com.ternparagliding.test.BirBillingCycleTest]).
 */
class BirBillingScenarioTest {

    @Test
    fun `scenario lists the three pilots with Richard as DUT-by-convention first`() {
        val s = BirBilling2025.scenario
        assertThat(s.region).isEqualTo("in")
        assertThat(s.pilots.map { it.id.value })
            .containsExactly("richard", "ariel", "barney").inOrder()
    }

    @Test
    fun `all three IGCs resolve off the classpath as real tracks`() {
        val flights = ScenarioLoader.load(BirBilling2025.scenario)
        assertThat(flights.keys.map { it.value })
            .containsExactly("richard", "ariel", "barney")
        for ((id, flight) in flights) {
            assertWithMessage("fixes for $id").that(flight.fixes.size).isGreaterThan(10_000)
        }
    }

    @Test
    fun `there is a multi-hour window where all three pilots are airborne`() {
        val playback = SwarmPlayback(BirBilling2025.scenario)
        // Overlap = latest first-fix .. earliest last-fix across pilots.
        val states = BirBilling2025.scenario.pilots.map { playback.pilot(it.id) }
        val overlapStart = states.maxOf { it.firstFixTime }
        val overlapEnd = states.minOf { it.lastFixTime }
        val overlap = Duration.between(overlapStart, overlapEnd)
        assertWithMessage("all-airborne overlap = $overlap")
            .that(overlap.toHours()).isAtLeast(4)

        // At the midpoint of the overlap every pilot has a position.
        val mid = overlapStart.plus(overlap.dividedBy(2))
        for (p in BirBilling2025.scenario.pilots) {
            assertWithMessage("position for ${p.id.value} at overlap midpoint")
                .that(playback.currentPosition(p.id, mid)).isNotNull()
        }
    }
}
