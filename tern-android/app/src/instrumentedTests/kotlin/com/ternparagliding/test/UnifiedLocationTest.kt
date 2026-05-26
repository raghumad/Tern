package com.ternparagliding.test

import org.junit.Ignore
import org.junit.Test

/**
 * Instrumentation test for Unified Location Model and LocationMarker.
 * Verifies RFC 005 compliance across Waypoints and PG Spots.
 *
 * M8: DISABLED. LocationMarker was an OSMDroid Compose component that was
 * deleted when the rendering layer migrated to MapLibre. PG spots are now
 * rendered as a MapLibre SymbolLayer (PgSpotLayer). This test needs to be
 * rewritten against the new rendering path.
 */
@Ignore("M8: LocationMarker deleted during OSMDroid-to-MapLibre migration")
class UnifiedLocationTest {

    @Test
    fun testLocationMarkerRFC005Compliance() {
        // Placeholder -- see class-level @Ignore
    }

    @Test
    fun testHazardVisualizationRFC005() {
        // Placeholder -- see class-level @Ignore
    }
}
