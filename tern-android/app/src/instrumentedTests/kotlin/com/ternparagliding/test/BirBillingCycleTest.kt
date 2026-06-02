package com.ternparagliding.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.Scenario
import com.ternparagliding.sim.swarm.scenarios.BirBilling2025
import org.junit.runner.RunWith

/**
 * Bir Billing cycle — three pilots over the Himalayan foothills (India).
 * DUT = Richard; Ariel + Barney are the LoRa buddies. The realistic
 * endurance scenario (~6 h, pilots spread tens of km apart). Same dynamic
 * follow-cam cycle as every other scenario (see [MezullaPeerCycleTest]).
 */
@RunWith(AndroidJUnit4::class)
class BirBillingCycleTest : MezullaPeerCycleTest() {
    override val scenario: Scenario = BirBilling2025.scenario
    override val dutPilotId: PilotId = BirBilling2025.RICHARD
    override val peerNodeNumbers: Map<PilotId, Long> = mapOf(
        BirBilling2025.ARIEL to 0x10000001L,
        BirBilling2025.BARNEY to 0x10000002L,
    )
}
