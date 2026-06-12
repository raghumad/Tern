package com.ternparagliding.weather

import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.WeatherForecast

/**
 * "Is it flyable here, now and soon?" — the universal pilot question, answered
 * **transparently and advisorily** from the weather we already gather.
 *
 * This does NOT decide for the pilot. It surfaces each factor and its severity;
 * the overall verdict is simply the worst factor, and every reason is shown.
 * Removing the unknown means showing the *why* and letting the pilot judge —
 * a 25 kt day is fine for one pilot and a no-go for another, so the limits are
 * configurable. Works at a point (the pilot's site); the route layer applies it
 * per-waypoint.
 */

/** Pilot-configurable thresholds. Defaults are conservative-recreational. */
data class FlyabilityLimits(
    val windCautionKt: Double = 15.0,
    val windNoGoKt: Double = 22.0,
    val gustCautionKt: Double = 20.0,
    val gustNoGoKt: Double = 28.0,
    val capeCaution: Double = 500.0,        // J/kg — overdevelopment risk
    val lightningNoGo: Double = 60.0,       // % — active storm potential
)

/** Ordered worst-last: GO < CAUTION < NO_GO. The overall verdict is the worst reason. */
enum class Verdict { GO, CAUTION, NO_GO }

/** One transparent factor behind the verdict. */
data class FlyabilityReason(val verdict: Verdict, val factor: String, val detail: String)

/** A reasoned flyability read of a single moment's conditions. */
data class Flyability(val verdict: Verdict, val reasons: List<FlyabilityReason>)

/** Now plus the next worsening, if conditions deteriorate soon ("and soon"). */
data class FlyabilityOutlook(
    val now: Flyability,
    val deterioratesTo: Flyability?, // the worst upcoming read, only if worse than now
    val deterioratesAtMs: Long?,     // when that worsening first occurs
)

/** Assess a single moment's conditions. */
fun assessFlyability(weather: WeatherData, limits: FlyabilityLimits = FlyabilityLimits()): Flyability {
    val reasons = mutableListOf<FlyabilityReason>()

    val wind = weather.wind.speed
    when {
        wind >= limits.windNoGoKt -> reasons += FlyabilityReason(Verdict.NO_GO, "wind", "${wind.toInt()} kt — too strong")
        wind >= limits.windCautionKt -> reasons += FlyabilityReason(Verdict.CAUTION, "wind", "${wind.toInt()} kt — strong")
    }

    val gust = weather.wind.gust
    when {
        gust >= limits.gustNoGoKt -> reasons += FlyabilityReason(Verdict.NO_GO, "gusts", "gusting ${gust.toInt()} kt")
        gust >= limits.gustCautionKt -> reasons += FlyabilityReason(Verdict.CAUTION, "gusts", "gusting ${gust.toInt()} kt")
    }

    // Reuse the established hazard thresholds (RFC 005) so flyability and the
    // map hazard halos never disagree.
    val lightning = weather.lightningPotential ?: 0.0
    val cape = weather.cape ?: 0.0
    when {
        lightning > limits.lightningNoGo ->
            reasons += FlyabilityReason(Verdict.NO_GO, "thunderstorm", "lightning potential ${lightning.toInt()}%")
        cape > limits.capeCaution ->
            reasons += FlyabilityReason(Verdict.CAUTION, "convective", "CAPE ${cape.toInt()} — overdevelopment risk")
    }

    val verdict = reasons.maxByOrNull { it.verdict.ordinal }?.verdict ?: Verdict.GO
    return Flyability(verdict, reasons.sortedByDescending { it.verdict.ordinal })
}

/**
 * Assess now, then scan the next [hoursAhead] hourly periods for the first time
 * conditions get *worse* than now — the "and soon" the pilot needs before they
 * commit to a flight.
 */
fun assessOutlook(
    forecast: WeatherForecast,
    hoursAhead: Int = 4,
    limits: FlyabilityLimits = FlyabilityLimits(),
): FlyabilityOutlook {
    val nowWeather = forecast.current ?: forecast.hourly.firstOrNull()?.weather
    val now = nowWeather?.let { assessFlyability(it, limits) }
        ?: Flyability(Verdict.NO_GO, listOf(FlyabilityReason(Verdict.NO_GO, "data", "no weather available")))

    val worseningPeriod = forecast.hourly
        .take(hoursAhead)
        .map { it to assessFlyability(it.weather, limits) }
        .firstOrNull { it.second.verdict.ordinal > now.verdict.ordinal }

    return FlyabilityOutlook(now, worseningPeriod?.second, worseningPeriod?.first?.startTime)
}
