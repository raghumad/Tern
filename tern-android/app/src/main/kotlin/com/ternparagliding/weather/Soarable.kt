package com.ternparagliding.weather

import com.ternparagliding.utils.io.ForecastPeriod
import com.ternparagliding.utils.io.WeatherForecast

/**
 * "When today is this site flyable?" — the temporal companion to [assessFlyability]
 * (which answers "*now*"). Walks a day's hourly forecast, judges each daylight hour
 * with the same site-aware Flyability brain, and reports the contiguous **soarable
 * window(s)** plus a plain daily digest — the read a pilot uses to decide *when to
 * show up*, not just whether it's on right now.
 *
 * This is Tern's **offline fallback** for the Spedmo soarable forecast (backlog
 * Story 3.10): when Spedmo is reachable it's authoritative; offline we compute this
 * locally from cached Open-Meteo/MET Norway data. Deliberately tuned to the same
 * factors Spedmo weighs (wind strength, direction-vs-orientation, daylight) so the
 * two agree. Pure + deterministic; the UI applies units and formats labels.
 */

/** A contiguous run of flyable hours within a day. */
data class SoarableWindow(
    val startMs: Long,    // start of the first flyable hour
    val endMs: Long,      // end of the last flyable hour
    val verdict: Verdict, // GO = soarable, CAUTION = marginal window
)

/** Sky cover bucket for the digest. */
enum class Sky { CLEAR, PARTLY_CLOUDY, CLOUDY, OVERCAST }

/** Precipitation bucket for the digest. */
enum class Precip { DRY, LIGHT, WET }

/**
 * A plain, structured one-day summary — the "Gusting to X, mostly NE — on direction,
 * 9–23°, mostly clear, dry" line, kept as data so the UI formats it in the pilot's units.
 */
data class DayDigest(
    val maxGustKt: Double,
    val dominantOctant: Octant?,   // the day's prevailing wind-*from* octant
    val onDirection: Boolean?,     // dominant octant vs the site's flyable arc (null if no site)
    val tempMinC: Double,
    val tempMaxC: Double,
    val sky: Sky,
    val precip: Precip,
    val precipChancePct: Double,
)

/** One day's soarable read: the flyable windows, the best one, and the digest. */
data class SoarableDay(
    val dayStartMs: Long,
    val sunriseMs: Long?,
    val sunsetMs: Long?,
    val windows: List<SoarableWindow>, // soarable (GO) runs, or marginal (CAUTION) runs if no GO
    val best: SoarableWindow?,         // the longest window (GO preferred)
    val digest: DayDigest?,            // null only if the day has no daylight hours
)

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/** Day bucket for a local-wall-clock timestamp (the basis Open-Meteo timezone=auto uses). */
private fun dayKey(ms: Long): Long = Math.floorDiv(ms, DAY_MS)

/** Local hour-of-day [0,24) from a local-wall-clock timestamp. */
private fun localHour(ms: Long): Int = Math.floorMod(ms / HOUR_MS, 24L).toInt()

/**
 * Assess up to [maxDays] days of soarable windows from a forecast. Days are taken in
 * chronological order from the hourly series; each is bounded to daylight using the
 * matching daily period's sun times (falling back to a 05:00–21:00 local band when a
 * source omits them — e.g. the surface-only fallback provider).
 */
fun assessSoarableDays(
    forecast: WeatherForecast,
    limits: FlyabilityLimits = FlyabilityLimits(),
    site: SiteContext? = null,
    maxDays: Int = 2,
): List<SoarableDay> {
    if (forecast.hourly.isEmpty()) return emptyList()

    val sunByDay = forecast.daily.associateBy { dayKey(it.startTime) }
    val hoursByDay = forecast.hourly.groupBy { dayKey(it.startTime) }

    return hoursByDay.keys.sorted().take(maxDays).map { key ->
        val dayHours = hoursByDay.getValue(key).sortedBy { it.startTime }
        val sun = sunByDay[key]
        val sunrise = sun?.sunriseMs
        val sunset = sun?.sunsetMs

        val daytime = dayHours.filter { isDaylight(it.startTime, sunrise, sunset) }
        val windows = soarableWindows(daytime, limits, site)
        SoarableDay(
            dayStartMs = key * DAY_MS,
            sunriseMs = sunrise,
            sunsetMs = sunset,
            windows = windows,
            best = windows.maxWithOrNull(
                compareBy<SoarableWindow>({ if (it.verdict == Verdict.GO) 1 else 0 }, { it.endMs - it.startMs }),
            ),
            digest = if (daytime.isEmpty()) null else digestOf(daytime, site),
        )
    }
}

private fun isDaylight(ms: Long, sunriseMs: Long?, sunsetMs: Long?): Boolean =
    if (sunriseMs != null && sunsetMs != null) ms in sunriseMs..sunsetMs
    else localHour(ms) in 5..20 // graceful fallback when a source omits sun times

/**
 * Maximal runs of GO hours = soarable windows. If the day has no GO hour, fall back to
 * maximal runs of CAUTION hours = marginal windows. NO_GO hours never form a window.
 */
private fun soarableWindows(
    daytime: List<ForecastPeriod>,
    limits: FlyabilityLimits,
    site: SiteContext?,
): List<SoarableWindow> {
    if (daytime.isEmpty()) return emptyList()
    val verdicts = daytime.map { it to assessFlyability(it.weather, limits, site).verdict }

    fun runs(tier: Verdict): List<SoarableWindow> {
        val out = mutableListOf<SoarableWindow>()
        var startIdx = -1
        for (i in verdicts.indices) {
            val isTier = verdicts[i].second == tier
            if (isTier && startIdx < 0) startIdx = i
            val runEnds = !isTier || i == verdicts.lastIndex
            if (startIdx >= 0 && runEnds) {
                val lastIdx = if (isTier) i else i - 1
                out += SoarableWindow(
                    startMs = verdicts[startIdx].first.startTime,
                    endMs = verdicts[lastIdx].first.endTime,
                    verdict = tier,
                )
                startIdx = -1
            }
        }
        return out
    }

    val go = runs(Verdict.GO)
    return go.ifEmpty { runs(Verdict.CAUTION) }
}

private fun digestOf(daytime: List<ForecastPeriod>, site: SiteContext?): DayDigest {
    val temps = daytime.map { it.weather.temperature }
    val maxGust = daytime.maxOf { it.weather.wind.gust }

    // Prevailing wind-from octant = the most common surface octant across the day.
    val dominant = daytime
        .map { octantOf(it.weather.windDirection10m ?: it.weather.wind.direction) }
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key

    val onDirection = site?.takeIf { it.hasOrientation }?.let { s ->
        dominant != null && (s.orientations[dominant] ?: 0) > 0
    }

    val avgCloud = daytime.map { it.weather.cloudCover }.average()
    val sky = when {
        avgCloud < 25 -> Sky.CLEAR
        avgCloud < 50 -> Sky.PARTLY_CLOUDY
        avgCloud < 85 -> Sky.CLOUDY
        else -> Sky.OVERCAST
    }

    val maxPrecip = daytime.mapNotNull { it.weather.precipProbability }.maxOrNull() ?: 0.0
    val precip = when {
        maxPrecip < 30 -> Precip.DRY
        maxPrecip < 60 -> Precip.LIGHT
        else -> Precip.WET
    }

    return DayDigest(
        maxGustKt = maxGust,
        dominantOctant = dominant,
        onDirection = onDirection,
        tempMinC = temps.min(),
        tempMaxC = temps.max(),
        sky = sky,
        precip = precip,
        precipChancePct = maxPrecip,
    )
}
