package com.ternparagliding.sim.swarm.scenarios

import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.Scenario
import com.ternparagliding.sim.swarm.ScenarioPilot
import java.time.LocalDate

/**
 * 2026-04-25 team flight from the Aravis range, French Alps.
 *
 * Four pilots; three (tonio24, cbe, cor) launched together from the
 * same site within ~20 seconds of each other. The fourth (lma)
 * launched about 22 minutes later from a different site roughly 30 km
 * to the northeast. This staggering is real and is the reason the
 * scenario is interesting: it lets the swarm tests cover the case
 * where one peer is still on the ground while the others are airborne,
 * and the case where two clusters of pilots converge over the course
 * of the flight.
 *
 * IGC files are sourced from the pilots' XContest uploads and live
 * under `app/src/test/resources/igc/flights/fr/`.
 */
object AravisTeam2026 {

    val TONIO24 = PilotId("tonio24")
    val CBE = PilotId("cbe")
    val COR = PilotId("cor")
    val LMA = PilotId("lma")

    val scenario: Scenario = Scenario(
        name = "aravis-team-2026-04-25",
        location = "Aravis range, French Alps",
        date = LocalDate.of(2026, 4, 25),
        region = "fr",
        pilots = listOf(
            ScenarioPilot(
                id = TONIO24,
                displayName = "tonio24",
                igcResourcePath = "/igc/flights/fr/2026-04-25-aravis-team-tonio24.igc",
                notes = "Launched together with cbe and cor.",
            ),
            ScenarioPilot(
                id = CBE,
                displayName = "cbe",
                igcResourcePath = "/igc/flights/fr/2026-04-25-aravis-team-cbe.igc",
                notes = "Launched together with tonio24 and cor.",
            ),
            ScenarioPilot(
                id = COR,
                displayName = "cor",
                igcResourcePath = "/igc/flights/fr/2026-04-25-aravis-team-cor.igc",
                notes = "Launched together with tonio24 and cbe.",
            ),
            ScenarioPilot(
                id = LMA,
                displayName = "lma",
                igcResourcePath = "/igc/flights/fr/2026-04-25-aravis-team-lma.igc",
                notes = "Launched roughly 22 minutes later from a site ~30 km NE.",
            ),
        ),
        notes = """
            Real same-day team XC from the Aravis. Three pilots launched
            together; the fourth launched ~22 min later from a different
            site. Useful for: staggered-launch behaviour, two-cluster
            convergence, terrain shielding behind the Aravis ridge, and
            long-distance LoRa range checks while pilots are spread.
        """.trimIndent(),
    )
}
