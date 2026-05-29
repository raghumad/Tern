package com.ternparagliding.sim.swarm.scenarios

import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.Scenario
import com.ternparagliding.sim.swarm.ScenarioPilot
import java.time.LocalDate

/**
 * 2026-05-29 short two-pilot flight from Edith's Gap, Virginia (USA).
 *
 * Two pilots only — Josh (1 h) and Stephen (~2 h) — with about 42 min
 * of overlap. Used as a fast-iteration variant of the Aravis scenario
 * for debugging map / peer-render issues: full-cycle test wall-clock
 * drops to ~1–2 min at 256x instead of ~9 min for Aravis.
 *
 * IGC files live under `app/src/test/resources/igc/flights/us/`.
 */
object EdithsGap2026 {

    val JOSH = PilotId("josh")
    val STEPHEN = PilotId("stephen")

    val scenario: Scenario = Scenario(
        name = "edithsgap-2026-05-29",
        location = "Edith's Gap, Virginia, USA",
        date = LocalDate.of(2026, 5, 29),
        region = "us",
        pilots = listOf(
            ScenarioPilot(
                id = JOSH,
                displayName = "josh",
                igcResourcePath = "/igc/flights/us/2026-05-29-edithsgap-josh.igc",
                notes = "Launched 17:04 UTC; ~1 h flight.",
            ),
            ScenarioPilot(
                id = STEPHEN,
                displayName = "stephen",
                igcResourcePath = "/igc/flights/us/2026-05-29-edithsgap-stephen.igc",
                notes = "Launched 15:42 UTC; ~2 h flight.",
            ),
        ),
        notes = """
            Short two-pilot flight from Edith's Gap, VA. Used as the
            fast-iteration variant when debugging peer rendering on the
            map — same end-to-end shape as the Aravis test but much
            shorter wall-clock, so each cycle takes 1–2 minutes instead
            of 9.
        """.trimIndent(),
    )
}
