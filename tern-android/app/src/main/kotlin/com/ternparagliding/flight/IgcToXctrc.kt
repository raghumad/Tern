package com.ternparagliding.flight

import com.ternparagliding.sim.igc.IgcFix
import com.ternparagliding.sim.igc.IgcFlight
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Replays an IGC flight as a stream of XC Tracer `$XCTRC` sentences — the own-ship analogue of
 * [com.ternparagliding.sim.injector.VirtualPeerInjector] (which replays IGC as Meshtastic peer
 * frames). This lets deck claims run real flights through the **actual parse path**
 * (`$XCTRC → XcTracerParser → SensorFix → deck logic`), not a shortcut.
 *
 * Fidelity notes (see flight-deck-ui.md): position/altitude/ground-speed map straight from the
 * IGC; the **climb** field is `Δ(pressure-altitude)/Δt` from the 1 Hz log, so it is coarse
 * (±~1 m/s, integer-metre quantized) — fine after a thermal-averager smooths it, but not a
 * substitute for the device's inertially-fused vario. Raw pressure is reconstructed from the
 * IGC pressure-altitude via the barometric formula.
 */
object IgcToXctrc {

    private const val EARTH_R_M = 6_371_000.0
    private const val BATTERY_PCT = 85
    private const val GAP_S = 10.0 // above this dt, treat as a logger gap (no velocity/climb)

    /** One `$XCTRC` sentence per valid fix, in order. */
    fun sentences(flight: IgcFlight): List<String> {
        val fixes = flight.fixes.filter { it.fixValid }
        return fixes.indices.map { sentence(fixes, it) }
    }

    fun sentence(fixes: List<IgcFix>, i: Int): String {
        val cur = fixes[i]
        val prev = if (i > 0) fixes[i - 1] else null
        val dt = if (prev != null) (cur.timestamp.toEpochMilli() - prev.timestamp.toEpochMilli()) / 1000.0 else 0.0

        var gs = 0.0
        var course = 0.0
        var climb = 0.0
        // dt > GAP_S means a logger pause / stitched-segment boundary, not a real sample — skip
        // its bogus velocity/climb. A real device emits nothing across such a gap.
        if (prev != null && dt > 0 && dt <= GAP_S) {
            val latMean = Math.toRadians((prev.latitude + cur.latitude) / 2)
            val dE = Math.toRadians(cur.longitude - prev.longitude) * cos(latMean) * EARTH_R_M
            val dN = Math.toRadians(cur.latitude - prev.latitude) * EARTH_R_M
            gs = hypot(dE, dN) / dt
            course = (Math.toDegrees(atan2(dE, dN)) + 360.0) % 360.0
            // Baro-derived vario (coarse), bounded to a physical range — the real device caps its
            // output too, and a 1 Hz log can throw non-physical single-sample spikes.
            climb = ((cur.pressureAltitude - prev.pressureAltitude) / dt).coerceIn(-15.0, 15.0)
        }

        val z = cur.timestamp.atZone(ZoneOffset.UTC)
        val fields = listOf(
            "XCTRC",
            z.year.toString(), z.monthValue.toString(), z.dayOfMonth.toString(),
            z.hour.toString(), z.minute.toString(), z.second.toString(), "0",
            f(cur.latitude, 6), f(cur.longitude, 6), f(cur.gpsAltitude.toDouble(), 1),
            f(gs, 2), f(course, 1), f(climb, 2),
            "", "", "", // 14–16: IMU reserved (device leaves blank)
            f(pressureHpa(cur.pressureAltitude.toDouble()), 2),
            BATTERY_PCT.toString(),
        )
        val body = fields.joinToString(",")
        return "\$$body*%02X".format(checksum(body))
    }

    /** Inverse of the standard barometric-altitude formula → pressure in hPa. */
    private fun pressureHpa(altM: Double): Double = 1013.25 * Math.pow(1 - 2.25577e-5 * altM, 5.25588)

    private fun checksum(body: String): Int {
        var x = 0
        for (c in body) x = x xor c.code
        return x
    }

    private fun f(v: Double, dec: Int): String = String.format(Locale.US, "%.${dec}f", v)
}
