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
    val visCautionKm: Double = 5.0,         // reduced visibility (model stores km)
    val visNoGoKm: Double = 2.0,            // can't see terrain/traffic
    val gustFactorCautionKt: Double = 8.0,  // gust − 10 m wind — turbulence/gustiness
    val gustFactorNoGoKt: Double = 12.0,
    val gradientCautionKt: Double = 10.0,   // 80 m − 10 m wind — low-level shear
    val gradientNoGoKt: Double = 18.0,
    val precipCautionPct: Double = 40.0,    // precipitation probability
    val precipNoGoPct: Double = 70.0,
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

    // Visibility (km). Parser defaults missing data to 10 km, so a low value is real.
    val vis = weather.visibility
    when {
        vis in 0.0..limits.visNoGoKm ->
            reasons += FlyabilityReason(Verdict.NO_GO, "visibility", "${vis.toInt()} km — can't see terrain/traffic")
        vis <= limits.visCautionKm ->
            reasons += FlyabilityReason(Verdict.CAUTION, "visibility", "${vis.toInt()} km — reduced")
    }

    // Gust factor & low-level gradient — need the 10 m wind for a clean read (a
    // degraded source without it simply skips these, never fudges them).
    weather.windSpeed10m?.let { w10 ->
        val gustFactor = weather.wind.gust - w10
        when {
            gustFactor >= limits.gustFactorNoGoKt -> reasons += FlyabilityReason(Verdict.NO_GO, "gust factor", "+${gustFactor.toInt()} kt over wind — turbulent")
            gustFactor >= limits.gustFactorCautionKt -> reasons += FlyabilityReason(Verdict.CAUTION, "gust factor", "+${gustFactor.toInt()} kt over wind — gusty")
        }
        val gradient = weather.wind.speed - w10 // wind.speed is the 80 m wind
        when {
            gradient >= limits.gradientNoGoKt -> reasons += FlyabilityReason(Verdict.NO_GO, "gradient", "+${gradient.toInt()} kt by 80 m — strong shear")
            gradient >= limits.gradientCautionKt -> reasons += FlyabilityReason(Verdict.CAUTION, "gradient", "+${gradient.toInt()} kt by 80 m — shear")
        }
    }

    // Precipitation probability.
    weather.precipProbability?.let { p ->
        when {
            p >= limits.precipNoGoPct -> reasons += FlyabilityReason(Verdict.NO_GO, "precipitation", "${p.toInt()}% chance")
            p >= limits.precipCautionPct -> reasons += FlyabilityReason(Verdict.CAUTION, "precipitation", "${p.toInt()}% chance")
        }
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

// ── "Worth flying?" — the quality dimension ─────────────────────────────────
// Safety says you *can* fly; quality says whether it's *good*. The recreational
// pilot needs the safety gate; the XC pilot also wants this. The math here is the
// stability analysis that previously lived (un-testable) inside the weather UI.

/** Thermal strength from the day's instability. */
enum class ThermalQuality { NONE, WEAK, WORKABLE, STRONG }

data class FlyingQuality(
    val thermal: ThermalQuality,
    val lapseRateCPerKm: Double?, // null when no upper-air data
    val cloudBaseM: Double,
    val cappedByInversion: Boolean,
    val notes: List<String>,
)

/** Surface→850 hPa lapse rate (°C/km). Steeper = more unstable = stronger climbs. */
fun lapseRateCPerKm(w: WeatherData): Double? {
    val t850 = w.temp850hPa ?: return null
    return (w.temperature - t850) / 1.5 // 850 hPa ≈ 1500 m
}

/** Cloud base (LCL) in metres, from the temperature–humidity spread. */
fun cloudBaseMeters(w: WeatherData): Double {
    val rh = w.humidity.coerceIn(1.0, 100.0)
    return (125.0 * ((100.0 - rh) / 5.0)).coerceAtLeast(0.0)
}

/** A warm layer aloft (850 hPa warmer than 925 hPa) caps the day. */
fun hasInversion(w: WeatherData): Boolean {
    val t850 = w.temp850hPa
    val t925 = w.temp925hPa
    return t850 != null && t925 != null && t850 > t925
}

/** Read the day's flying *quality* — strength + ceiling — transparently. */
fun assessQuality(w: WeatherData): FlyingQuality {
    val lapse = lapseRateCPerKm(w)
    val base = cloudBaseMeters(w)
    val inversion = hasInversion(w)
    val overcast = w.cloudCover >= 80.0
    val notes = mutableListOf<String>()

    var thermal = when {
        overcast -> { notes += "overcast — little sun to trigger thermals"; ThermalQuality.NONE }
        lapse == null -> ThermalQuality.WORKABLE // no upper-air data → stay neutral
        lapse < 5.0 -> { notes += "stable air"; ThermalQuality.WEAK }
        lapse < 7.0 -> ThermalQuality.WORKABLE
        else -> { notes += "unstable — strong climbs (watch overdevelopment)"; ThermalQuality.STRONG }
    }
    // An inversion lids the day: a strong-looking lapse still tops out low.
    if (inversion) {
        notes += "inversion caps the day (low ceiling)"
        if (thermal == ThermalQuality.STRONG) thermal = ThermalQuality.WORKABLE
    }
    notes += "cloudbase ~${base.toInt()} m"

    return FlyingQuality(thermal, lapse, base, inversion, notes)
}
