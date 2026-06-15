package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.utils.io.WaypointFileParser
import org.junit.Test

/**
 * Claim **K8 · Waypoints exist without tasks** — the comp waypoint files an
 * organiser issues (.cup / .wpt / .gpx) import into the standalone library, with
 * code, name, position and elevation preserved. Driven through the exact parser
 * the import path uses ([WaypointFileParser]).
 */
class WaypointParserClaimsTest {

    /** **CLAIM.** A SeeYou .cup imports its waypoints (and stops at the task section). */
    @Test
    fun `cup parses code name position and elevation`() {
        val cup = """
            name,code,country,lat,lon,elev,style,rwdir,rwlen,freq,desc
            "Bir Takeoff",B01,IN,3204.200N,07642.000E,2400.0m,1,,,,
            "Gold's Point",B42,IN,3212.600N,07625.800E,2438.4m,5,,,,
            -----Related Tasks-----
            "Day 3","B01","B42"
        """.trimIndent()

        val wps = WaypointFileParser.parse("waypoints.cup", cup)

        assertThat(wps).hasSize(2) // the task section is ignored
        val b01 = wps.first { it.code == "B01" }
        assertThat(b01.name).isEqualTo("Bir Takeoff")
        assertThat(b01.lat).isWithin(1e-4).of(32.07)   // 32°04.2'
        assertThat(b01.lon).isWithin(1e-4).of(76.70)   // 76°42.0'
        val b42 = wps.first { it.code == "B42" }
        assertThat(b42.name).isEqualTo("Gold's Point")
        assertThat(b42.alt!!).isWithin(1.0).of(2438.4)
    }

    /** **CLAIM.** A GPX waypoint file imports its <wpt> points. */
    @Test
    fun `gpx parses wpt elements`() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="32.07" lon="76.70"><name>B01</name><ele>2400</ele><desc>Bir Takeoff</desc></wpt>
              <wpt lat="32.21" lon="76.43"><name>B42</name><ele>2438</ele><desc>Gold's Point</desc></wpt>
            </gpx>
        """.trimIndent()

        val wps = WaypointFileParser.parse("wp.gpx", gpx)

        assertThat(wps).hasSize(2)
        assertThat(wps.map { it.code }).containsExactly("B01", "B42")
        val b01 = wps.first { it.code == "B01" }
        assertThat(b01.name).isEqualTo("Bir Takeoff")
        assertThat(b01.lat).isWithin(1e-6).of(32.07)
    }

    /** **CLAIM.** An OziExplorer .wpt imports (decimal-degree CSV, alt in feet). */
    @Test
    fun `ozi wpt parses csv rows`() {
        val wpt = """
            OziExplorer Waypoint File Version 1.1
            WGS 84
            Reserved 2
            Reserved 3
            1,B01, 32.070000, 76.700000,40139.0,0,1,3,0,65535,Bir Takeoff,0,0,0,7874
        """.trimIndent()

        val wps = WaypointFileParser.parse("wp.wpt", wpt)

        assertThat(wps).hasSize(1)
        val b01 = wps.first()
        assertThat(b01.code).isEqualTo("B01")
        assertThat(b01.name).isEqualTo("Bir Takeoff")
        assertThat(b01.lat).isWithin(1e-5).of(32.07)
        assertThat(b01.alt!!).isWithin(1.0).of(2400.0) // 7874 ft → ~2400 m
    }

    /** **CLAIM.** A CompeGPS .wpt imports ("W code A lat lon ... alt desc"). */
    @Test
    fun `compegps wpt parses W rows`() {
        val wpt = """
            G  WGS 84
            U  1
            W  B01 A 32.0700000ºN 76.7000000ºE 27-MAR-09 00:00:00 2400.000000 Bir Takeoff
        """.trimIndent()

        val wps = WaypointFileParser.parse("wp.wpt", wpt)

        assertThat(wps).hasSize(1)
        val b01 = wps.first()
        assertThat(b01.code).isEqualTo("B01")
        assertThat(b01.lat).isWithin(1e-5).of(32.07)
        assertThat(b01.lon).isWithin(1e-5).of(76.70)
        assertThat(b01.alt!!).isWithin(1.0).of(2400.0)
    }
}
