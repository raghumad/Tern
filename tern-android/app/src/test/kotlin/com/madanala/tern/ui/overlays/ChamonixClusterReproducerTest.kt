package com.madanala.tern.ui.overlays

import com.madanala.tern.model.LocationType
import com.madanala.tern.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChamonixClusterReproducerTest {

    @Test
    fun testSpatialLatticeDemotionInTightCluster() {
        val lattice = SpatialLattice(64f, 64f)
        lattice.reset()

        // Mimic a cluster of 5 waypoints in Chamonix
        // Screen coordinates (simulated)
        val center = 500f to 500f
        
        val waypoints = listOf(
            center, // Center (Target)
            510f to 510f, // Overlap 1
            490f to 490f, // Overlap 2
            550f to 550f, // Borderline (should overlap 64dp cell if distance < 32dp)
            450f to 450f  // Far enough? (500-450 = 50. 64dp is 32dp radius. 50 > 32? No, 50 is distance. Cell is 64x64.)
        )

        val tiers = mutableListOf<RankingTier>()

        waypoints.forEachIndexed { index, (x, y) ->
            var tier = if (index == 0) RankingTier.TARGET else RankingTier.PATH
            
            val rect = SpatialRect(x - 32f, y - 32f, x + 32f, y + 32f)
            
            if (tier != RankingTier.TARGET && lattice.isOccupied(rect)) {
                tier = RankingTier.CONTEXT
            } else {
                lattice.occupy(rect)
            }
            tiers.add(tier)
        }

        // Expectations:
        // 1st is Target
        // 2nd (510,510) overlaps 1st (500,500) -> CONTEXT
        // 3rd (490,490) overlaps 1st (500,500) -> CONTEXT
        // 4th (550,550). Distance is 50. 500-32=468, 500+32=532. 550 is > 532. No overlap with 1st.
        // But wait! (550,550) center. Rect is 518 to 582.
        // Cell (500,500) is cell (7,7) if cellWidth=64.
        // (510,510) is also in (7,7).
        // (550,550) is in cell (8,8)? 550/64 = 8.59 -> 8.
        // 500/64 = 7.8 -> 7.
        // So they are in different cells.
        
        assertEquals(RankingTier.TARGET, tiers[0])
        assertEquals(RankingTier.CONTEXT, tiers[1])
        assertEquals(RankingTier.CONTEXT, tiers[2])
    }
}
