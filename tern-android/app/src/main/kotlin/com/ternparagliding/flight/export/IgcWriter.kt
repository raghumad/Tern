package com.ternparagliding.flight.export

import com.ternparagliding.flight.SensorFix
import com.ternparagliding.sim.igc.IgcFix
import com.ternparagliding.sim.igc.IgcFlight
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Writes the FAI **IGC** flight-recorder text format — the inverse of
 * [com.ternparagliding.sim.igc.IgcParser]. The contract that anchors the tests is a clean
 * **round-trip**: `IgcParser.parseString(IgcWriter.write(flight)) == flight` (within IGC's own
 * resolution — altitude is integer metres, lat/lon are thousandths of a minute ≈ 2 m).
 *
 * Honesty (Epic 05 5.2): the **G security record** here is a self-signed digest, NOT an
 * FAI-approved recorder signature. Tern is not an approved flight recorder; the IGC is for
 * logging / XContest / Spedmo, not competition validation. The real tamper-evidence
 * (hardware-backed Keystore signature + server counter-sign) wraps the *file*, not the G record.
 */
object IgcWriter {

    /** Headers that ride in the H records. All optional — sensible defaults for a phone log. */
    data class Headers(
        val pilot: String = "",
        val gliderType: String = "",
        val gliderId: String = "",
        /** Free-form firmware/app version string for HFRFW. */
        val firmware: String = "Tern",
    )

    private const val CRLF = "\r\n"

    /** Manufacturer triple for the A record. "X" = experimental per IGC; "XTN" = Tern. */
    private const val MANUFACTURER = "XTN"

    /**
     * Render [flight] (a parsed/round-trip model) to IGC text. [signature], when present, is
     * emitted as the G record verbatim (already hex/base64); it is opaque to the parser.
     */
    fun write(flight: IgcFlight, headers: Headers = Headers(), signature: String? = null): String {
        val sb = StringBuilder()
        val d = flight.date

        // A record: manufacturer + serial. Parser ignores it.
        sb.append("A").append(MANUFACTURER).append("001").append(CRLF)

        // H records. Long-form date (IGC ≥ 2016); parser also accepts the short form.
        sb.append("HFDTEDATE:")
            .append("%02d%02d%02d".format(d.dayOfMonth, d.monthValue, d.year % 100))
            .append(CRLF)
        if (headers.pilot.isNotBlank()) sb.append("HFPLTPILOTINCHARGE:").append(headers.pilot).append(CRLF)
        if (headers.gliderType.isNotBlank()) sb.append("HFGTYGLIDERTYPE:").append(headers.gliderType).append(CRLF)
        if (headers.gliderId.isNotBlank()) sb.append("HFGIDGLIDERID:").append(headers.gliderId).append(CRLF)
        sb.append("HFDTM100GPSDATUM:WGS-1984").append(CRLF)
        sb.append("HFRFWFIRMWAREVERSION:").append(headers.firmware).append(CRLF)
        sb.append("HFFTYFRTYPE:Tern Paragliding").append(CRLF)

        // B records — the fixes.
        for (fix in flight.fixes) sb.append(bRecord(fix)).append(CRLF)

        // G record — self-signed digest (NOT FAI-validated).
        if (!signature.isNullOrBlank()) sb.append("G").append(signature).append(CRLF)

        return sb.toString()
    }

    /**
     * Render recorded [SensorFix]es straight to IGC, mapping each fix to a B record. Fixes
     * without a GPS position are dropped (a B record requires lat/lon). [pressureAltitudeM]
     * is derived from `pressureHpa` via the ISA standard atmosphere when present, else 0.
     */
    fun fromSensorFixes(
        fixes: List<SensorFix>,
        headers: Headers = Headers(),
        signature: String? = null,
    ): IgcFlight {
        val positioned = fixes.filter { it.hasPosition }
        require(positioned.isNotEmpty()) { "no positioned fixes to write" }
        val date = java.time.Instant.ofEpochMilli(positioned.first().timeMs)
            .atZone(ZoneOffset.UTC).toLocalDate()
        val igcFixes = positioned.map { f ->
            IgcFix(
                timestamp = java.time.Instant.ofEpochMilli(f.timeMs),
                latitude = f.lat!!,
                longitude = f.lon!!,
                pressureAltitude = f.pressureHpa?.let { pressureAltitudeM(it).roundToInt() } ?: 0,
                gpsAltitude = f.gpsAltitudeM?.roundToInt() ?: 0,
                fixValid = true,
            )
        }
        return IgcFlight(date = date, fixes = igcFixes)
    }

    /** Convenience: record [SensorFix]es to IGC text in one step. */
    fun writeSensorFixes(
        fixes: List<SensorFix>,
        headers: Headers = Headers(),
        signature: String? = null,
    ): String = write(fromSensorFixes(fixes, headers), headers, signature)

    /**
     * Barometric (pressure) altitude in metres from station pressure [hpa], ISA standard
     * atmosphere referenced to 1013.25 hPa — the same reference IGC pressure altitude uses.
     */
    fun pressureAltitudeM(hpa: Double): Double =
        44330.0 * (1.0 - (hpa / 1013.25).pow(0.190295))

    // --- B record assembly (column layout per IgcParser.parseBRecord) ---

    private fun bRecord(fix: IgcFix): String {
        val t = fix.timestamp.atZone(ZoneOffset.UTC)
        val time = "%02d%02d%02d".format(t.hour, t.minute, t.second)
        val validity = if (fix.fixValid) 'A' else 'V'
        return "B" + time + formatLat(fix.latitude) + formatLon(fix.longitude) +
            validity + formatAltitude(fix.pressureAltitude) + formatAltitude(fix.gpsAltitude)
    }

    /** `DDMMmmm[N|S]` — 8 chars. Rounding carries minutes→degrees so the parser stays happy. */
    internal fun formatLat(lat: Double): String {
        val hemi = if (lat < 0) 'S' else 'N'
        val (deg, minWhole, minThou) = degMin(abs(lat))
        return "%02d%02d%03d%c".format(deg, minWhole, minThou, hemi)
    }

    /** `DDDMMmmm[E|W]` — 9 chars. */
    internal fun formatLon(lon: Double): String {
        val hemi = if (lon < 0) 'W' else 'E'
        val (deg, minWhole, minThou) = degMin(abs(lon))
        return "%03d%02d%03d%c".format(deg, minWhole, minThou, hemi)
    }

    /** Decompose |coord| into (degrees, whole minutes, thousandths of a minute) with carry. */
    private fun degMin(a: Double): Triple<Int, Int, Int> {
        var deg = floor(a).toInt()
        val minutes = (a - deg) * 60.0
        var minWhole = floor(minutes).toInt()
        var minThou = ((minutes - minWhole) * 1000.0).roundToInt()
        if (minThou >= 1000) { minThou -= 1000; minWhole += 1 }
        if (minWhole >= 60) { minWhole -= 60; deg += 1 }
        return Triple(deg, minWhole, minThou)
    }

    /**
     * 5-char signed altitude field. Non-negative → 5 zero-padded digits (`00824`); negative →
     * `-` + 4 zero-padded digits (`-0012`). Clamped to the field width (paragliding never
     * approaches these bounds).
     */
    internal fun formatAltitude(m: Int): String {
        val c = m.coerceIn(-9999, 99999)
        return if (c < 0) "-%04d".format(-c) else "%05d".format(c)
    }
}
