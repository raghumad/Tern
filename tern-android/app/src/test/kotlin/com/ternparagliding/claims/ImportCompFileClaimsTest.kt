package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Spot
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.mapReducer
import com.ternparagliding.utils.io.WaypointFileParser
import org.junit.Test

/**
 * Claim **K11 · Import a comp file** — the *automatable* half of the import journey,
 * exercised with a **real competition waypoint set** (Ozone Chelan Open 2026, fetched
 * from airtribune) bundled under `src/test/resources/comp/`. The system file picker is
 * un-automatable OS UI, but parsing + library-merge is our code — so this asserts the
 * pilot outcome ("my comp's waypoints appear, correctly, and a re-import refreshes
 * rather than duplicates") against real-world data that synthetic fixtures don't stress.
 *
 * (The L2 manual step — discovering the picker, success/failure feedback — stays in
 * ux/validation-checklist.md.)
 */
class ImportCompFileClaimsTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/comp/$name")?.readText()
            ?: error("missing test fixture /comp/$name")

    private val cup get() = fixture("chelan.SeeYou.cup")
    private val gpx get() = fixture("chelan.GPX.gpx")
    private val wpt get() = fixture("chelan.CompeGPS.wpt")

    /** GR201, the first waypoint, in decimal degrees (cup is DDMM.mmm, hemisphere W). */
    private val gr201Lat = 48.0 + 28.671 / 60.0          // 48.47785
    private val gr201Lon = -(120.0 + 50.987 / 60.0)      // -120.84978

    @Test
    fun `the SeeYou cup parses every waypoint with correct geography`() {
        val spots = WaypointFileParser.parse("chelan.SeeYou.cup", cup)
        assertThat(spots).hasSize(252)

        val gr201 = spots.single { it.code == "GR201" }
        assertThat(gr201.lat).isWithin(1e-4).of(gr201Lat)
        assertThat(gr201.lon).isWithin(1e-4).of(gr201Lon)
        assertThat(gr201.alt).isWithin(0.5).of(909.7)
        // Imported identity keys on the (uppercased) code so re-imports refresh, not dup.
        assertThat(gr201.id).isEqualTo("GR201")
    }

    @Test
    fun `cup, gpx and CompeGPS-wpt agree on the same comp set`() {
        val byCup = WaypointFileParser.parse("chelan.SeeYou.cup", cup)
        val byGpx = WaypointFileParser.parse("chelan.GPX.gpx", gpx)
        val byWpt = WaypointFileParser.parse("chelan.CompeGPS.wpt", wpt)

        assertThat(byGpx).hasSize(252)
        assertThat(byWpt).hasSize(252)
        // Same codes across all three formats (the comp's canonical waypoint set).
        val codes = byCup.map { it.code }.toSet()
        assertThat(byGpx.map { it.code }.toSet()).isEqualTo(codes)
        assertThat(byWpt.map { it.code }.toSet()).isEqualTo(codes)
        // And the same geography for GR201 (cup is coarser DDMM.mmm — tolerate ~100 m).
        for (set in listOf(byGpx, byWpt)) {
            val w = set.single { it.code == "GR201" }
            assertThat(w.lat).isWithin(1e-3).of(gr201Lat)
            assertThat(w.lon).isWithin(1e-3).of(gr201Lon)
        }
    }

    @Test
    fun `importing the comp set, then re-importing, refreshes without duplicating`() {
        val spots: List<Spot> = WaypointFileParser.parse("chelan.SeeYou.cup", cup)

        val once = mapReducer(MapState(), MapAction.ImportWaypointsToLibrary(spots))
        assertThat(once.waypointLibrary).hasSize(252)

        // Re-import the same file (the comp re-issues waypoints): merge by code → no dups.
        val twice = mapReducer(once, MapAction.ImportWaypointsToLibrary(spots))
        assertThat(twice.waypointLibrary).hasSize(252)
        assertThat(twice.waypointLibrary.map { it.id }.toSet()).hasSize(252)
    }
}
