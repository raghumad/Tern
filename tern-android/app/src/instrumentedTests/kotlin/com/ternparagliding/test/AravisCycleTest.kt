package com.ternparagliding.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.Scenario
import com.ternparagliding.sim.swarm.scenarios.AravisTeam2026
import org.junit.runner.RunWith

/**
 * Aravis convergence cycle — four pilots over Col de la Forclaz (France).
 * DUT = tonio24; cbe / cor / lma are the LoRa buddies. Same dynamic
 * follow-cam cycle as every other scenario (see [MezullaPeerCycleTest]).
 */
@RunWith(AndroidJUnit4::class)
class AravisCycleTest : MezullaPeerCycleTest() {
    override val scenario: Scenario = AravisTeam2026.scenario
    override val dutPilotId: PilotId = AravisTeam2026.TONIO24
    override val peerNodeNumbers: Map<PilotId, Long> = mapOf(
        AravisTeam2026.CBE to 0x10000001L,
        AravisTeam2026.COR to 0x10000002L,
        AravisTeam2026.LMA to 0x10000003L,
    )
}
