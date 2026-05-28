package com.ternparagliding.sim.igc

import java.time.Instant
import java.time.LocalDate

/**
 * A parsed IGC flight: the date from the HFDTE header, plus every
 * B-record fix that survived parsing, in file order.
 *
 * Fix order is preserved as-is; the IGC spec mandates ascending
 * timestamps within a file, but the parser does not enforce or
 * re-sort. That's the playback engine's problem (WS1.2).
 */
data class IgcFlight(
    val date: LocalDate,
    val fixes: List<IgcFix>,
)

/**
 * One B-record fix.
 *
 * @property timestamp UTC instant built from the flight date plus the
 *   B-record time-of-day.
 * @property latitude decimal degrees, positive north, negative south.
 * @property longitude decimal degrees, positive east, negative west.
 * @property pressureAltitude metres above the 1013.25 hPa reference
 *   surface, as reported by the recorder. May be zero or negative if
 *   the recorder has no baro or has not been calibrated.
 * @property gpsAltitude metres above the WGS-84 ellipsoid as reported
 *   by the GPS.
 * @property fixValid true if the recorder reported a 3D fix ('A'),
 *   false if it reported the fix as invalid ('V').
 */
data class IgcFix(
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
    val pressureAltitude: Int,
    val gpsAltitude: Int,
    val fixValid: Boolean,
)
