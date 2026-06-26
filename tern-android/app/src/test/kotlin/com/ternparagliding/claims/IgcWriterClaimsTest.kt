package com.ternparagliding.claims

import com.ternparagliding.flight.SensorFix
import com.ternparagliding.flight.export.IgcWriter
import com.ternparagliding.sim.igc.IgcFix
import com.ternparagliding.sim.igc.IgcFlight
import com.ternparagliding.sim.igc.IgcParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claim-driven tests for **Epic 05 5.2 — IGC export**. The pilot-visible promise is "the flight I
 * recorded comes back out as a valid IGC that other tools accept." The anchor is a strict
 * **round-trip** against the existing [IgcParser]: what we write, the parser reads back to the same
 * fixes (within IGC's own resolution). If the writer and parser disagree, the export is a lie.
 */
class IgcWriterClaimsTest {

    private fun fixAt(epochSec: Long, lat: Double, lon: Double, pAlt: Int, gAlt: Int) = IgcFix(
        timestamp = Instant.ofEpochSecond(epochSec),
        latitude = lat,
        longitude = lon,
        pressureAltitude = pAlt,
        gpsAltitude = gAlt,
        fixValid = true,
    )

    /**
     * **CLAIM K6 · Correct (round-trip).** A hand-built flight written to IGC text parses back to
     * the same date and fixes — position to ~1e-4° (thousandth-of-a-minute quantisation), altitude
     * exact (integer metres), time exact (whole seconds). Both hemispheres covered.
     */
    @Test
    fun `correct - a flight round-trips through writer and parser`() {
        val base = LocalDate.of(2026, 4, 25).atStartOfDay().toInstant(ZoneOffset.UTC).epochSecond
        val flight = IgcFlight(
            date = LocalDate.of(2026, 4, 25),
            fixes = listOf(
                fixAt(base + 9 * 3600, 45.812345, 6.123456, 1850, 1875),     // N, E
                fixAt(base + 9 * 3600 + 2, 45.812900, 6.124000, 1852, 1877),
                fixAt(base + 9 * 3600 + 4, -33.500000, -70.666000, 900, 925), // S, W (Santiago-ish)
            ),
        )

        val text = IgcWriter.write(flight, IgcWriter.Headers(pilot = "Richard", gliderType = "Klimber 3P"))
        val reparsed = IgcParser.parseString(text)

        assertEquals("date survives", flight.date, reparsed.date)
        assertEquals("every fix survives", flight.fixes.size, reparsed.fixes.size)
        for (i in flight.fixes.indices) {
            val a = flight.fixes[i]
            val b = reparsed.fixes[i]
            assertEquals("lat[$i]", a.latitude, b.latitude, 1e-4)
            assertEquals("lon[$i]", a.longitude, b.longitude, 1e-4)
            assertEquals("pAlt[$i]", a.pressureAltitude, b.pressureAltitude)
            assertEquals("gAlt[$i]", a.gpsAltitude, b.gpsAltitude)
            assertEquals("time[$i]", a.timestamp.epochSecond, b.timestamp.epochSecond)
        }
    }

    /**
     * **CLAIM K6 · Resilient (real flight).** A real Bir Billing flight parsed → written → reparsed
     * preserves the fix count and a mid-flight fix to IGC resolution. Real, messy GPS, not a
     * hand-fed track.
     */
    @Test
    fun `resilient - a real IGC flight survives a parse-write-parse cycle`() {
        val text = javaClass.getResourceAsStream("/igc/flights/in/2025-10-11-birbilling-richard.igc")
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("Bir Billing fixture on classpath", text)
        val original = IgcParser.parseString(text!!)

        val written = IgcWriter.write(original)
        val again = IgcParser.parseString(written)

        assertEquals("fix count preserved", original.fixes.size, again.fixes.size)
        assertEquals("date preserved", original.date, again.date)
        val k = original.fixes.size / 2
        assertEquals(original.fixes[k].latitude, again.fixes[k].latitude, 1e-4)
        assertEquals(original.fixes[k].longitude, again.fixes[k].longitude, 1e-4)
        assertEquals(original.fixes[k].gpsAltitude, again.fixes[k].gpsAltitude)
    }

    /**
     * **CLAIM K6 · Correct (record from sensor fixes).** The recorder's own-ship [SensorFix]
     * stream maps to IGC B records: positioned fixes are written (vario-only pre-GPS fixes are
     * dropped), GPS altitude carried through, and pressure altitude derived from raw hPa via the
     * ISA standard atmosphere.
     */
    @Test
    fun `correct - sensor fixes export to IGC, dropping pre-GPS samples`() {
        val t0 = LocalDate.of(2026, 4, 25).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val fixes = listOf(
            SensorFix(timeMs = t0, climbMs = 1.0, pressureHpa = 1013.25),          // pre-GPS: dropped
            SensorFix(timeMs = t0 + 1000, lat = 45.8, lon = 6.1, gpsAltitudeM = 1850.0, pressureHpa = 800.0),
            SensorFix(timeMs = t0 + 2000, lat = 45.81, lon = 6.11, gpsAltitudeM = 1870.0, pressureHpa = 798.0),
        )
        val flight = IgcWriter.fromSensorFixes(fixes)
        assertEquals("only the 2 positioned fixes are written", 2, flight.fixes.size)
        assertEquals(1850, flight.fixes[0].gpsAltitude)

        // 1013.25 hPa is the ISA reference → ~0 m pressure altitude.
        assertEquals("ISA reference pressure is ~0 m", 0.0, IgcWriter.pressureAltitudeM(1013.25), 0.5)
        // 800 hPa is roughly 1950 m in the standard atmosphere.
        assertEquals(1949.0, IgcWriter.pressureAltitudeM(800.0), 25.0)

        // And the whole thing still round-trips through the parser.
        val reparsed = IgcParser.parseString(IgcWriter.write(flight))
        assertEquals(2, reparsed.fixes.size)
    }

    /**
     * **CLAIM K6 · Correct (B-record format).** Field formatting matches the parser's fixed
     * columns exactly: an 8-char latitude, 9-char longitude, and signed 5-char altitudes, with
     * minute-rounding carrying cleanly. A negative altitude uses the `-` + 4-digit form.
     */
    @Test
    fun `correct - B-record fields format to the parser's columns`() {
        // 45.8123° = 45°48.738' → "4548738N" (8 chars: DD MM mmm hemi)
        assertEquals("4548738N", IgcWriter.formatLat(45.8123))
        assertEquals("00607400E", IgcWriter.formatLon(6.123333))
        assertEquals("southern hemisphere is negative", 'S', IgcWriter.formatLat(-33.5).last())
        assertEquals("western hemisphere is negative", 'W', IgcWriter.formatLon(-70.0).last())

        assertEquals("non-negative is 5 zero-padded digits", "01850", IgcWriter.formatAltitude(1850))
        assertEquals("negative is dash + 4 digits", "-0012", IgcWriter.formatAltitude(-12))
        assertEquals("zero", "00000", IgcWriter.formatAltitude(0))
    }
}
