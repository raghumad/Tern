package com.ternparagliding.weather

import com.ternparagliding.model.Waypoint
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.WeatherForecast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **Flight risk** — the whole-task safety synthesis that replaces the old
 * convective-only "storm risk" alarm. It folds the full breadth of what we already
 * know — every air-mass factor the [assessFlyability] engine reads (wind, gusts,
 * gust factor, low-level shear, CAPE/convection, lightning/thunderstorm, visibility,
 * precip) **plus airspace penetration** — sampled at *each waypoint's ETA*, not "now".
 *
 * It is **advisory and transparent**, never a decision: the verdict is simply the
 * worst *known* factor, and every factor with its value + where/when is surfaced so
 * the **pilot** judges. Missing forecast is reported ([dataComplete] = false), never
 * faked green or red — the core principle is to empower the pilot, not decide for them.
 */
data class FlightRisk(
    val verdict: Verdict,
    /** At least one waypoint has a forecast to read. */
    val anyData: Boolean,
    /** Every waypoint has a forecast at its ETA (the verdict sees the whole task). */
    val dataComplete: Boolean,
    /** Contributing factors, worst-first — the analysis breadth the pilot decides on. */
    val factors: List<FlightRiskFactor>,
    /** Per-waypoint 4D read, for the drill-down timeline. */
    val points: List<FlightRiskPoint>,
) {
    /** The single deciding factor for the headline — the worst, or the first if all clear. */
    val headline: FlightRiskFactor? get() =
        factors.firstOrNull { it.verdict != Verdict.GO } ?: factors.firstOrNull()
}

/** One contributing factor — transparent: severity, what it is, its value, and where/when. */
data class FlightRiskFactor(
    val verdict: Verdict,
    val label: String,   // "WIND", "AIRSPACE", "CONVECTION", …
    val detail: String,  // "18 kt @ 240°"
    val where: String?,  // "WP3, 14:20" — null when task-wide (e.g. airspace)
)

/** One waypoint's 4D read: the forecast sampled at its ETA, run through the engine. */
data class FlightRiskPoint(
    val seq: Int,
    val name: String,
    val etaMs: Long?,
    val etaLabel: String?,
    val weather: WeatherData?,
    val flyability: Flyability?,
    val quality: FlyingQuality?,
)

/** The hourly forecast nearest the arrival time — the "when you'll be there" read.
 *  Falls back to the period covering [ms], then to current. */
internal fun WeatherForecast.weatherAt(ms: Long?): WeatherData? {
    if (ms == null) return current
    if (hourly.isEmpty()) return current
    hourly.firstOrNull { ms >= it.startTime && ms < it.endTime }?.let { return it.weather }
    return hourly.minByOrNull { abs(it.startTime - ms) }?.weather ?: current
}

/** Engine factor key → short display label for the pilot. */
private fun factorLabel(key: String): String = when (key) {
    "wind" -> "WIND"
    "gusts" -> "GUSTS"
    "gust factor" -> "GUST FACTOR"
    "gradient" -> "WIND SHEAR"
    "thunderstorm" -> "THUNDERSTORM"
    "convective" -> "CONVECTION"
    "visibility" -> "VISIBILITY"
    "precipitation" -> "PRECIP"
    "wind direction" -> "WIND DIRECTION"
    "cloudbase" -> "CLOUDBASE"
    else -> key.uppercase()
}

// Forecast timestamps and ETAs are now both **true epoch** (the WeatherAPI parse shifts
// Open-Meteo's wall-clock strings by the site offset; TrajectoryAnalyzer ETAs are epoch from
// System.currentTimeMillis()), so they compare directly — no clock-shim. For *display* we
// format a true-epoch instant in the relevant waypoint's site zone (its forecast offset).
private fun siteHm(ms: Long, utcOffsetSeconds: Int): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .apply { timeZone = com.ternparagliding.utils.io.siteTimeZone(utcOffsetSeconds) }
        .format(java.util.Date(ms))

/** Sunrise/sunset (ms) for the day an [etaMs] falls on, from the daily forecast's sun
 *  times. Null when the source omits them (e.g. MET Norway) — daylight degrades silently. */
internal fun sunTimesFor(fc: WeatherForecast, etaMs: Long): Pair<Long, Long>? {
    val day = fc.daily.firstOrNull { etaMs in it.startTime..it.endTime }
        ?: fc.daily.minByOrNull { abs(it.startTime - etaMs) }
    val sunrise = day?.sunriseMs
    val sunset = day?.sunsetMs
    return if (sunrise != null && sunset != null) sunrise to sunset else null
}

/**
 * Synthesise the task's flight risk from the per-waypoint forecasts (read at each ETA),
 * the same [assessFlyability]/[assessQuality] engine the site weather sheet uses, and the
 * airspace conflicts already computed for the corridor.
 *
 * Air-mass flyability only (no launch-orientation/in-cloud gate — those are launch-specific,
 * not turnpoint concerns). The verdict is the worst *known* factor; airspace folds in as a
 * CAUTION-level factor (balanced — a regulatory awareness cue, the pilot decides). Quality
 * (cloudbase, thermal top, climb strength) rides along on each [FlightRiskPoint] for the
 * drill-down breadth but does not gate the safety verdict.
 */
fun assessTaskFlightRisk(
    waypoints: List<Waypoint>,
    weathers: Map<String, WeatherForecast>,
    etas: Map<String, Long>,
    airspaceConflicts: List<String> = emptyList(),
    limits: FlyabilityLimits = FlyabilityLimits(),
): FlightRisk {
    val points = waypoints.mapIndexed { i, wp ->
        val fc = weathers[wp.id]
        val etaMs = etas[wp.id]
        val w = fc?.weatherAt(etaMs)
        FlightRiskPoint(
            seq = i + 1,
            name = wp.displayName ?: wp.label ?: "WP ${i + 1}",
            etaMs = etaMs,
            etaLabel = etaMs?.let { siteHm(it, fc?.utcOffsetSeconds ?: 0) },
            weather = w,
            flyability = w?.let { assessFlyability(it, limits) },
            quality = w?.let {
                // 0 alt = unknown (default for ad-hoc drops); a wrong sea-level root
                // against MSL profile heights would yield a bogus thermal top.
                assessQuality(it, SiteContext(elevationM = wp.alt?.takeIf { a -> a != 0.0 }))
            },
        )
    }

    val anyData = points.any { it.weather != null }
    val dataComplete = points.isNotEmpty() && points.all { it.weather != null }

    // Worst reason per factor across all points, tagged with where/when. Folding to one
    // line per factor keeps the headline honest (one WIND line, not five) while the
    // per-point timeline still shows each waypoint's own read.
    val weatherFactors = points
        .flatMap { p -> (p.flyability?.reasons ?: emptyList()).map { it to p } }
        .groupBy { it.first.factor }
        .map { (_, lst) -> lst.maxByOrNull { it.first.verdict.ordinal }!! }
        .map { (r, p) ->
            FlightRiskFactor(
                verdict = r.verdict,
                label = factorLabel(r.factor),
                detail = r.detail,
                where = listOfNotNull(p.name, p.etaLabel).joinToString(", ").ifEmpty { null },
            )
        }

    val airspaceFactor = airspaceConflicts.takeIf { it.isNotEmpty() }?.let {
        FlightRiskFactor(Verdict.CAUTION, "AIRSPACE", "crosses ${it.joinToString(", ")}", null)
    }

    // Daylight: an ETA before sunrise or after sunset means soaring/landing in the dark.
    // Per-waypoint (each leg's ETA against that day's sun times); the latest offender wins.
    val daylightFactor = waypoints.mapIndexedNotNull { i, wp ->
        val etaMs = etas[wp.id] ?: return@mapIndexedNotNull null
        val fc = weathers[wp.id] ?: return@mapIndexedNotNull null
        val (sunrise, sunset) = sunTimesFor(fc, etaMs) ?: return@mapIndexedNotNull null
        val off = fc.utcOffsetSeconds
        val name = wp.displayName ?: wp.label ?: "WP ${i + 1}"
        val where = "$name, ${siteHm(etaMs, off)}"
        when {
            etaMs > sunset -> FlightRiskFactor(Verdict.CAUTION, "DAYLIGHT", "after sunset ${siteHm(sunset, off)}", where)
            etaMs < sunrise -> FlightRiskFactor(Verdict.CAUTION, "DAYLIGHT", "before sunrise ${siteHm(sunrise, off)}", where)
            else -> null
        }
    }.lastOrNull()

    // Terrain: when the day's lowest cloudbase (MSL, from the sounding) sits below the
    // highest terrain on the task, that high ground is in cloud — you can't top it in the
    // clear. Honest only when both a profile-derived MSL base and a real elevation exist.
    val maxTerrain = waypoints.mapNotNull { it.alt?.takeIf { a -> a != 0.0 } }.maxOrNull()
    val terrainFactor = maxTerrain?.let { terr ->
        points.mapNotNull { it.quality?.cloudBaseMslM }.minOrNull()?.takeIf { it < terr }?.let { base ->
            FlightRiskFactor(
                Verdict.CAUTION, "TERRAIN",
                "cloudbase ${base.roundToInt()} m below high terrain ${terr.roundToInt()} m", null,
            )
        }
    }

    val factors = (listOfNotNull(airspaceFactor, daylightFactor, terrainFactor) + weatherFactors)
        .sortedByDescending { it.verdict.ordinal }

    val verdict = factors.maxByOrNull { it.verdict.ordinal }?.verdict ?: Verdict.GO
    return FlightRisk(verdict, anyData, dataComplete, factors, points)
}
