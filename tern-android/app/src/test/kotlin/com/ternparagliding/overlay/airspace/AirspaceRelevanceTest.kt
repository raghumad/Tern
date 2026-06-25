package com.ternparagliding.overlay.airspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claim **K2 · airspace altitude-relevance.** Each airspace's emphasis tracks the pilot's
 * live altitude vs its floor/ceiling: inside → BOLD, approaching → NORMAL, floored far above
 * → FAINT (recede). The headline case is the high TMA you fly *under* — Geneva TMA floored at
 * 8500 ft while you're at 5500 ft should recede, not shout.
 */
class AirspaceRelevanceTest {

    @Test
    fun `no altitude falls back to a static class emphasis`() {
        assertEquals(AirspaceEmphasis.BOLD, AirspaceRelevance.emphasisFor("CTR", 1000.0, 5000.0, false, null))
        assertEquals(AirspaceEmphasis.NORMAL, AirspaceRelevance.emphasisFor("C", 1000.0, 5000.0, false, null))
        assertEquals(AirspaceEmphasis.FAINT, AirspaceRelevance.emphasisFor("E", 1000.0, 5000.0, false, null))
    }

    @Test
    fun `inside the slab is BOLD`() {
        assertEquals(
            AirspaceEmphasis.BOLD,
            AirspaceRelevance.emphasisFor("C", floorFtMsl = 6000.0, ceilingFtMsl = 9000.0, floorIsGround = false, pilotFtMsl = 7500.0),
        )
    }

    @Test
    fun `within ~1000 ft of the floor is NORMAL (approaching)`() {
        assertEquals(
            AirspaceEmphasis.NORMAL,
            AirspaceRelevance.emphasisFor("C", floorFtMsl = 8500.0, ceilingFtMsl = 12000.0, floorIsGround = false, pilotFtMsl = 7800.0),
        )
    }

    @Test
    fun `a high TMA you fly well under recedes to FAINT`() {
        // Geneva-TMA case: floor 8500 ft MSL, you at 5500 ft → 3000 ft below → recede.
        assertEquals(
            AirspaceEmphasis.FAINT,
            AirspaceRelevance.emphasisFor("C", floorFtMsl = 8500.0, ceilingFtMsl = 12000.0, floorIsGround = false, pilotFtMsl = 5500.0),
        )
    }

    @Test
    fun `a ground-floored block is BOLD whenever you're below its ceiling`() {
        assertEquals(
            AirspaceEmphasis.BOLD,
            AirspaceRelevance.emphasisFor("CTR", floorFtMsl = null, ceilingFtMsl = 5000.0, floorIsGround = true, pilotFtMsl = 3000.0),
        )
    }

    @Test
    fun `climbing well above a ground-floored block's ceiling recedes it`() {
        assertEquals(
            AirspaceEmphasis.FAINT,
            AirspaceRelevance.emphasisFor("CTR", floorFtMsl = null, ceilingFtMsl = 5000.0, floorIsGround = true, pilotFtMsl = 9000.0),
        )
    }

    @Test
    fun `parseLimitFeet handles flight levels, metres, and ground reference`() {
        // FL130 → value 13000 ft (unit 6), MSL.
        val fl = AirspaceRelevance.parseLimitFeet(mapOf("value" to 13000, "unit" to 6, "referenceDatum" to 2))!!
        assertEquals(13000.0, fl.feet, 0.5)
        assertTrue(!fl.ground)
        // 1000 m MSL → ~3280.84 ft.
        val m = AirspaceRelevance.parseLimitFeet(mapOf("value" to 1000, "unit" to 0, "referenceDatum" to 1))!!
        assertEquals(3280.84, m.feet, 0.5)
        // SFC (0 ft, GND) → ground = true.
        val gnd = AirspaceRelevance.parseLimitFeet(mapOf("value" to 0, "unit" to 1, "referenceDatum" to 0))!!
        assertTrue(gnd.ground)
    }
}
