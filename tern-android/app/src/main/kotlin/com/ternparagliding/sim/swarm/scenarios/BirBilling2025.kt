package com.ternparagliding.sim.swarm.scenarios

import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.Scenario
import com.ternparagliding.sim.swarm.ScenarioPilot
import java.time.LocalDate

/**
 * 2025-10-11 three-pilot XC flight from Bir Billing, Himachal Pradesh
 * (India) — the Himalayan foothills, one of the world's premier
 * cross-country paragliding sites.
 *
 * Three pilots, each flying ~5–6 hours, with roughly five hours of
 * all-three-airborne overlap (≈05:24→10:20 UTC). This is the realistic
 * stress test for Mezulla: a long, high-altitude, deep-mountain flight
 * where pilots genuinely lose sight of each other and cell coverage is
 * absent — exactly the situation peer awareness over LoRa is for.
 *
 *  - Richard Meek (OZONE Alpina 4) — 05:00→10:57 UTC (DUT by convention)
 *  - Ariel Zlatkovski (NKN)        — 05:24→11:01 UTC
 *  - barney woodhead               — 05:12→10:20 UTC
 *
 * IGC files live under `app/src/{main,test}/resources/igc/flights/in/`.
 */
object BirBilling2025 {

    val RICHARD = PilotId("richard")
    val ARIEL = PilotId("ariel")
    val BARNEY = PilotId("barney")

    val scenario: Scenario = Scenario(
        name = "birbilling-2025-10-11",
        location = "Bir Billing, Himachal Pradesh, India",
        date = LocalDate.of(2025, 10, 11),
        region = "in",
        pilots = listOf(
            ScenarioPilot(
                id = RICHARD,
                displayName = "Richard",
                igcResourcePath = "/igc/flights/in/2025-10-11-birbilling-richard.igc",
                wing = "OZONE Alpina 4",
                notes = "05:00→10:57 UTC; ~6 h. DUT by convention.",
            ),
            ScenarioPilot(
                id = ARIEL,
                displayName = "Ariel",
                igcResourcePath = "/igc/flights/in/2025-10-11-birbilling-ariel.igc",
                wing = "NKN",
                notes = "05:24→11:01 UTC; ~5.6 h.",
            ),
            ScenarioPilot(
                id = BARNEY,
                displayName = "Barney",
                igcResourcePath = "/igc/flights/in/2025-10-11-birbilling-barney.igc",
                notes = "05:12→10:20 UTC; ~5 h.",
            ),
        ),
        notes = """
            Long three-pilot Himalayan XC from Bir Billing. The true
            field test for Mezulla peer awareness: ~5 h of overlapping
            flight at altitude in deep mountains with no cell service,
            where buddies routinely drop out of visual range. DUT is
            Richard; Ariel and Barney are the LoRa peers.
        """.trimIndent(),
    )
}
