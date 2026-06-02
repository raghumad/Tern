package com.ternparagliding.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.Scenario
import com.ternparagliding.sim.swarm.scenarios.EdithsGap2026
import org.junit.runner.RunWith

/**
 * Edith's Gap cycle — two pilots over Virginia (USA). DUT = Josh; Stephen
 * is the single LoRa buddy. The fast-iteration scenario (~2 h flight).
 * Same dynamic follow-cam cycle as every other scenario (see
 * [MezullaPeerCycleTest]).
 */
@RunWith(AndroidJUnit4::class)
class EdithsGapCycleTest : MezullaPeerCycleTest() {
    override val scenario: Scenario = EdithsGap2026.scenario
    override val dutPilotId: PilotId = EdithsGap2026.JOSH
    override val peerNodeNumbers: Map<PilotId, Long> = mapOf(
        EdithsGap2026.STEPHEN to 0x10000001L,
    )
}
