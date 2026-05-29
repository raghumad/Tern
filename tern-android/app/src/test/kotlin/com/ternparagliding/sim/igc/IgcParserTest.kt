package com.ternparagliding.sim.igc

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate

/**
 * Tests for [IgcParser].
 *
 * The fixture `fixtures/synthetic-short-flight.igc` is a hand-crafted
 * IGC file checked into `app/src/test/resources/igc/fixtures/`. It
 * was written for this test rather than sourced from XContest /
 * OnlineContest because the licensing of individual pilot uploads on
 * those sites is unclear: the platforms hold the database rights but
 * each IGC belongs to the pilot who uploaded it. A synthetic fixture
 * sidesteps that and gives us exact ground-truth values to assert
 * against.
 *
 * Real flight logs (for the swarm-simulator scenarios) live alongside
 * under `flights/<region>/` and are referenced by the scenario
 * manifests under `scenarios/`. See `igc/README.md`.
 *
 * The fixture contains 11 B-records:
 *  - 10 well-formed records, one of which is flagged 'V' (invalid).
 *  - 1 deliberately malformed record (non-digit in the time field)
 *    that the parser should skip silently.
 */
class IgcParserTest {

    private fun loadFixture(): String =
        requireNotNull(
            javaClass.getResourceAsStream("/igc/fixtures/synthetic-short-flight.igc")
        ) { "test fixture not on classpath" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `parses HFDTE date from long DATE form`() {
        val flight = IgcParser.parseString(loadFixture())
        assertThat(flight.date).isEqualTo(LocalDate.of(2025, 4, 15))
    }

    @Test
    fun `skips the malformed B record but keeps the other ten`() {
        val flight = IgcParser.parseString(loadFixture())
        assertThat(flight.fixes).hasSize(10)
    }

    @Test
    fun `first fix matches the file`() {
        val flight = IgcParser.parseString(loadFixture())
        val first = flight.fixes.first()

        // B0900003201986N07643398EA0240002400
        assertThat(first.timestamp)
            .isEqualTo(Instant.parse("2025-04-15T09:00:00Z"))
        // 32 deg 01.986 min N  =  32 + 1.986/60
        assertThat(first.latitude).isWithin(1e-9).of(32.0 + 1.986 / 60.0)
        // 76 deg 43.398 min E  =  76 + 43.398/60
        assertThat(first.longitude).isWithin(1e-9).of(76.0 + 43.398 / 60.0)
        assertThat(first.pressureAltitude).isEqualTo(2400)
        assertThat(first.gpsAltitude).isEqualTo(2400)
        assertThat(first.fixValid).isTrue()
    }

    @Test
    fun `last fix matches the file`() {
        val flight = IgcParser.parseString(loadFixture())
        val last = flight.fixes.last()

        // B0900103202540N07643725EA0245002500
        assertThat(last.timestamp)
            .isEqualTo(Instant.parse("2025-04-15T09:00:10Z"))
        assertThat(last.latitude).isWithin(1e-9).of(32.0 + 2.540 / 60.0)
        assertThat(last.longitude).isWithin(1e-9).of(76.0 + 43.725 / 60.0)
        assertThat(last.pressureAltitude).isEqualTo(2450)
        assertThat(last.gpsAltitude).isEqualTo(2500)
        assertThat(last.fixValid).isTrue()
    }

    @Test
    fun `invalid V-flagged fix is kept and marked invalid`() {
        val flight = IgcParser.parseString(loadFixture())

        // The fixture has exactly one V row, at 09:00:04.
        val invalid = flight.fixes.filter { !it.fixValid }
        assertThat(invalid).hasSize(1)
        assertThat(invalid.single().timestamp)
            .isEqualTo(Instant.parse("2025-04-15T09:00:04Z"))
    }

    @Test
    fun `fixes are returned in file order with monotonic timestamps`() {
        val flight = IgcParser.parseString(loadFixture())
        val times = flight.fixes.map { it.timestamp }
        assertThat(times).isInOrder()
    }

    @Test
    fun `southern and western hemispheres produce negative coordinates`() {
        val igc = """
            HFDTE010125
            B1200003000000S07000000WA0010000100
        """.trimIndent()
        val flight = IgcParser.parseString(igc)
        val fix = flight.fixes.single()
        assertThat(fix.latitude).isWithin(1e-9).of(-30.0)
        assertThat(fix.longitude).isWithin(1e-9).of(-70.0)
    }

    @Test
    fun `short HFDTE form is parsed`() {
        val igc = """
            HFDTE070824
            B0800004500000N00500000EA0050000500
        """.trimIndent()
        val flight = IgcParser.parseString(igc)
        assertThat(flight.date).isEqualTo(LocalDate.of(2024, 8, 7))
    }

    @Test
    fun `negative altitudes are parsed`() {
        // Pressure altitude -0050 m (e.g. dead-sea floor).
        val igc = """
            HFDTE010125
            B1200003000000N03500000EA-0050-0050
        """.trimIndent()
        val flight = IgcParser.parseString(igc)
        val fix = flight.fixes.single()
        assertThat(fix.pressureAltitude).isEqualTo(-50)
        assertThat(fix.gpsAltitude).isEqualTo(-50)
    }

    @Test
    fun `missing HFDTE raises IgcParseException`() {
        val igc = "B0900003201986N07643398EA0240002400\n"
        val ex = assertThrows<IgcParseException> {
            IgcParser.parseString(igc)
        }
        assertThat(ex.message).contains("HFDTE")
    }

    @Test
    fun `no B records raises IgcParseException`() {
        val igc = "HFDTE010125\nLXXXSome log line\n"
        assertThrows<IgcParseException> {
            IgcParser.parseString(igc)
        }
    }

    @Test
    fun `CRLF line endings are handled`() {
        val igc = "HFDTE010125\r\nB1200004500000N00500000EA0050000500\r\n"
        val flight = IgcParser.parseString(igc)
        assertThat(flight.fixes).hasSize(1)
        assertThat(flight.fixes.single().latitude).isWithin(1e-9).of(45.0)
    }
}
