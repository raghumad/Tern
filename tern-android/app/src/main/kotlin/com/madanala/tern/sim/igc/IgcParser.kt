package com.madanala.tern.sim.igc

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Parser for the FAI IGC flight-recorder text format.
 *
 * Handles the subset needed by the swarm simulator (WS1.1):
 *  - H records: the flight date from HFDTE (both the short `HFDTEDDMMYY`
 *    and long `HFDTEDATE:DDMMYY[,nn]` forms).
 *  - B records: position fixes (time, latitude, longitude, validity,
 *    pressure altitude, GPS altitude).
 *
 * Other record types (I, J, K, L, C, D, E, F, G, A) are skipped. They
 * are valid IGC content but carry no information the simulator uses,
 * so ignoring them keeps the parser simple without losing data we need.
 *
 * IGC times are UTC by spec, so timestamps are built with
 * [ZoneOffset.UTC]. Coordinates are returned as decimal degrees, with
 * south and west expressed as negative numbers.
 *
 * Malformed B lines (wrong length, non-numeric fields, missing
 * hemisphere) are skipped silently. A real IGC file is occasionally
 * truncated mid-line at landing or has a stray non-ASCII byte; the
 * parser should still return whatever it could read rather than
 * throwing. Truly unparseable input — missing HFDTE, or zero valid B
 * records — raises [IgcParseException] so callers know they got
 * nothing useful.
 */
object IgcParser {

    fun parseFile(file: File): IgcFlight = parseString(file.readText())

    fun parseString(text: String): IgcFlight {
        var date: LocalDate? = null
        val fixes = ArrayList<IgcFix>()

        // Split on either CRLF or LF. IGC spec mandates CRLF but many
        // tools (and most ground-station exports) emit LF only.
        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd('\r', ' ', '\t')
            if (line.isEmpty()) continue

            when (line[0]) {
                'H' -> {
                    val parsed = parseHfdte(line)
                    if (parsed != null) date = parsed
                }
                'B' -> {
                    val currentDate = date ?: continue
                    val fix = parseBRecord(line, currentDate)
                    if (fix != null) fixes.add(fix)
                }
                // All other record types ignored on purpose.
                else -> Unit
            }
        }

        val flightDate = date
            ?: throw IgcParseException("no HFDTE header found")
        if (fixes.isEmpty()) {
            throw IgcParseException("no B-record fixes found")
        }
        return IgcFlight(date = flightDate, fixes = fixes)
    }

    /**
     * Parse a flight date from either:
     *  - Short form:  `HFDTEDDMMYY`               (older recorders)
     *  - Long form:   `HFDTEDATE:DDMMYY[,nn]`     (IGC spec ≥ 2016)
     *
     * Returns null if the header isn't an HFDTE line we recognise.
     * Two-digit year is interpreted as 2000–2099 — the IGC format has
     * no Y2.1K provision and no flight recorder predates 2000 with
     * data anyone wants to replay.
     */
    private fun parseHfdte(line: String): LocalDate? {
        if (!line.startsWith("HFDTE")) return null
        val payload = line.substring(5)

        val digits = when {
            payload.startsWith("DATE:") -> {
                val rest = payload.substring(5)
                // Optional `,nn` flight-number suffix.
                rest.substringBefore(',').take(6)
            }
            payload.length >= 6 -> payload.take(6)
            else -> return null
        }
        if (digits.length != 6 || digits.any { !it.isDigit() }) return null

        val day = digits.substring(0, 2).toInt()
        val month = digits.substring(2, 4).toInt()
        val year = 2000 + digits.substring(4, 6).toInt()
        return try {
            LocalDate.of(year, month, day)
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    /**
     * Parse a B record. Column layout (0-based, inclusive):
     *
     *   0      'B'
     *   1..6   HHMMSS
     *   7..14  lat: DD MM mmm [N|S]   (8 chars)
     *   15..23 lon: DDD MM mmm [E|W]  (9 chars)
     *   24     fix validity ('A' = 3D, 'V' = invalid)
     *   25..29 pressure altitude, signed 5 chars
     *   30..34 GPS altitude, signed 5 chars
     *
     * Total minimum length = 35. Records may carry extra optional
     * fields after column 35 (declared via I records); we ignore them.
     */
    private fun parseBRecord(line: String, date: LocalDate): IgcFix? {
        if (line.length < 35) return null

        val time = parseTime(line, 1) ?: return null
        val lat = parseLatitude(line, 7) ?: return null
        val lon = parseLongitude(line, 15) ?: return null
        val validity = when (line[24]) {
            'A' -> true
            'V' -> false
            else -> return null
        }
        val pressureAlt = parseSignedAltitude(line, 25) ?: return null
        val gpsAlt = parseSignedAltitude(line, 30) ?: return null

        val timestamp = date.atTime(time).toInstant(ZoneOffset.UTC)
        return IgcFix(
            timestamp = timestamp,
            latitude = lat,
            longitude = lon,
            pressureAltitude = pressureAlt,
            gpsAltitude = gpsAlt,
            fixValid = validity,
        )
    }

    private fun parseTime(line: String, offset: Int): LocalTime? {
        val hh = intOrNull(line, offset, 2) ?: return null
        val mm = intOrNull(line, offset + 2, 2) ?: return null
        val ss = intOrNull(line, offset + 4, 2) ?: return null
        return try {
            LocalTime.of(hh, mm, ss)
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    /** Latitude field: `DDMMmmm[N|S]` — 8 chars total. */
    private fun parseLatitude(line: String, offset: Int): Double? {
        val deg = intOrNull(line, offset, 2) ?: return null
        val minWhole = intOrNull(line, offset + 2, 2) ?: return null
        val minThou = intOrNull(line, offset + 4, 3) ?: return null
        val hemi = line[offset + 7]
        if (hemi != 'N' && hemi != 'S') return null
        if (deg > 90) return null

        val minutes = minWhole + (minThou / 1000.0)
        if (minutes >= 60.0) return null
        val signed = deg + minutes / 60.0
        return if (hemi == 'S') -signed else signed
    }

    /** Longitude field: `DDDMMmmm[E|W]` — 9 chars total. */
    private fun parseLongitude(line: String, offset: Int): Double? {
        val deg = intOrNull(line, offset, 3) ?: return null
        val minWhole = intOrNull(line, offset + 3, 2) ?: return null
        val minThou = intOrNull(line, offset + 5, 3) ?: return null
        val hemi = line[offset + 8]
        if (hemi != 'E' && hemi != 'W') return null
        if (deg > 180) return null

        val minutes = minWhole + (minThou / 1000.0)
        if (minutes >= 60.0) return null
        val signed = deg + minutes / 60.0
        return if (hemi == 'W') -signed else signed
    }

    /**
     * 5-char altitude field. IGC allows a leading `-` for negative
     * values (e.g. below sea level pressure altitude), in which case
     * the remaining 4 chars are the magnitude.
     */
    private fun parseSignedAltitude(line: String, offset: Int): Int? {
        val field = line.substring(offset, offset + 5)
        return if (field[0] == '-') {
            val n = field.substring(1).toIntOrNull() ?: return null
            -n
        } else {
            field.toIntOrNull()
        }
    }

    private fun intOrNull(line: String, offset: Int, length: Int): Int? {
        val s = line.substring(offset, offset + length)
        if (s.any { !it.isDigit() }) return null
        return s.toInt()
    }
}

class IgcParseException(message: String) : RuntimeException(message)
