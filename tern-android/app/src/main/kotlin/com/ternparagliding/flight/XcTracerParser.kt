package com.ternparagliding.flight

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Parses the XC Tracer **`$XCTRC`** BLE/serial sentence into a device-agnostic [SensorFix].
 *
 * Field layout, verified against XCSoar's production driver
 * (`src/Device/Driver/XCTracer/Parser.cpp`) and the XC Tracer Mini II GPS manual:
 *
 * ```
 *   $XCTRC,year,mon,day,hour,min,sec,csec,lat,lon,gpsAlt,gndSpd,course,climb,—,—,—,press,batt*CC
 *      0    1    2   3    4   5   6    7    8   9    10     11     12     13  14 15 16  17    18
 * ```
 * — lat/lon in decimal degrees, gpsAlt in m, **ground speed in m/s** (no scaling),
 * course in deg, climb (fused vario) in m/s, fields 14–16 reserved (IMU; XC Tracer leaves
 * them blank), pressure in hPa, battery in %. Checksum is the standard NMEA XOR of the
 * characters between `$` and `*`.
 *
 * Only `$XCTRC` carries position, which is why Tern asks the device to run in XCTRACER mode
 * (LXWP0/LK8EX1 are vario-only). Other sentence types return null here — a mixed stream just
 * yields fixes from the `$XCTRC` lines.
 */
object XcTracerParser {

    fun parse(raw: String): SensorFix? {
        val line = raw.trim()
        if (!line.startsWith("\$XCTRC")) return null

        val star = line.lastIndexOf('*')
        val body = if (star >= 0) line.substring(1, star) else line.substring(1)
        if (star >= 0) {
            val given = line.substring(star + 1).trim()
            if (!checksumValid(body, given)) return null
        }

        val f = body.split(',')
        if (f.size < 14 || f[0] != "XCTRC") return null

        return try {
            val year = f[1].toInt(); val mon = f[2].toInt(); val day = f[3].toInt()
            val hour = f[4].toInt(); val min = f[5].toInt(); val sec = f[6].toInt()
            val csec = f.num(7)?.toInt() ?: 0
            val timeMs = LocalDateTime.of(year, mon, day, hour, min, sec)
                .toInstant(ZoneOffset.UTC).toEpochMilli() + csec * 10L
            SensorFix(
                timeMs = timeMs,
                lat = f.num(8),
                lon = f.num(9),
                gpsAltitudeM = f.num(10),
                groundSpeedMs = f.num(11),
                courseDeg = f.num(12),
                climbMs = f.num(13),
                pressureHpa = f.num(17),
                batteryPct = f.num(18)?.toInt(),
            )
        } catch (e: Exception) {
            null // out-of-range date fields, malformed numbers → drop the sentence, don't crash
        }
    }

    /** A blank/missing/non-numeric field → null (devices send empty fields before a fix). */
    private fun List<String>.num(i: Int): Double? =
        getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    /** NMEA XOR of every char between `$` and `*`, compared to the two-hex-digit suffix. */
    private fun checksumValid(body: String, given: String): Boolean {
        var x = 0
        for (c in body) x = x xor c.code
        return "%02X".format(x).equals(given.take(2), ignoreCase = true)
    }
}
