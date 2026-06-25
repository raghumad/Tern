package com.ternparagliding.weather

import com.ternparagliding.utils.io.WeatherForecast
import com.ternparagliding.utils.io.dewpointC

/**
 * **Thermal outlook** — the temporal climb-strength companion to [assessQuality] (which reads
 * one moment). Walks the day's daylight hours and estimates, per hour, how *strong* the
 * thermals are (a numeric **w\*** climb rate), how *high* they reach (parcel thermal top), and
 * whether they'll mark with cumulus — then reports the working window and the peak. The read a
 * pilot uses to time an XC: *when* to launch for the best lift, *how strong*, *how high*.
 *
 * **w\*** is the Deardorff convective velocity scale, the standard soaring-forecast measure of
 * thermal updraft strength (what RASP/Dr-Jack call "thermal updraft velocity"):
 *
 *     w* = ( (g / θ) · (w'θ')ₛ · zᵢ )^(1/3)
 *
 * where the surface kinematic heat flux (w'θ')ₛ = H / (ρ·cₚ) and H, the sensible heat flux, is
 * estimated as a fraction of the incoming solar shortwave (Open-Meteo `shortwave_radiation`) —
 * so cloud and time-of-day fall out physically. zᵢ is the convective boundary-layer depth
 * (`boundary_layer_height`, falling back to the sounding's parcel thermal top). Pure +
 * transparent; null where a source omits the inputs (the UI then shows the qualitative
 * strength alone rather than a fabricated number).
 */
data class ThermalHour(
    val startMs: Long,            // true epoch ms (hour start)
    val wStarMs: Double?,         // convective velocity scale w* (m/s); null when inputs missing
    val strength: ThermalQuality, // qualitative read (reuses assessQuality's instability model)
    val ziAglM: Double?,          // boundary-layer / thermal depth used for w* (m AGL)
    val thermalTopMslM: Double?,  // parcel thermal top (sounding), MSL
    val thermalTopAglM: Double?,  // ... above ground
    val cuBaseMslM: Double?,      // cumulus cloudbase (MSL) when cumulus is expected, else null (blue)
) {
    /** A workable thermal: a real w* over the usable bar, or — lacking w* — a WORKABLE+ read. */
    val usable: Boolean get() =
        wStarMs?.let { it >= USABLE_WSTAR_MS } ?: (strength.ordinal >= ThermalQuality.WORKABLE.ordinal)
}

/** One day's thermal outlook: the hour-by-hour strength, the working window, and the peak. */
data class ThermalOutlook(
    val dayStartMs: Long,         // true-epoch instant of site-local midnight
    val hours: List<ThermalHour>, // daylight hours, chronological
    val windowStartMs: Long?,     // first usable-lift hour (null if none usable)
    val windowEndMs: Long?,       // end of the last usable-lift hour
    val peak: ThermalHour?,       // strongest hour (by w*, then qualitative strength)
    val utcOffsetSeconds: Int,    // site offset, for site-local display via siteTimeZone(...)
)

// ── w* model constants ───────────────────────────────────────────────────────
private const val G = 9.81           // gravity (m/s²)
private const val AIR_DENSITY = 1.2  // ρ near surface (kg/m³)
private const val AIR_CP = 1005.0    // cₚ of dry air (J/kg·K)
/** Sensible-heat-flux fraction of incoming shortwave — a pragmatic mid-day land value
 *  (the rest goes to latent heat + ground). Conservative; documented, not tuned per site. */
private const val HEATING_EFFICIENCY = 0.30
/** w* at/above which thermals are worth working for a paraglider (≈ 1 m/s net after sink). */
private const val USABLE_WSTAR_MS = 1.0

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/**
 * Deardorff convective velocity scale **w\*** (m/s) from the surface solar heating and the
 * convective boundary-layer depth. Returns null when there's no sun ([shortwaveWm2] ≤ 0) or
 * no depth — i.e. no convection to scale, rather than a fabricated zero-ish number.
 */
fun convectiveVelocityScale(shortwaveWm2: Double?, ziAglM: Double?, surfaceTempC: Double): Double? {
    val sw = shortwaveWm2 ?: return null
    val zi = ziAglM ?: return null
    if (sw <= 0.0 || zi <= 0.0) return null
    val sensibleHeatFlux = HEATING_EFFICIENCY * sw          // W/m²
    val kinematicFlux = sensibleHeatFlux / (AIR_DENSITY * AIR_CP) // K·m/s
    val tempK = surfaceTempC + 273.15
    val wStarCubed = (G / tempK) * kinematicFlux * zi
    return if (wStarCubed > 0.0) Math.cbrt(wStarCubed) else null
}

/**
 * Assess up to [maxDays] days of thermal outlook from a forecast. Days are taken in
 * chronological order from the hourly series and bounded to daylight using the matching daily
 * period's sun times (falling back to a 05:00–21:00 local band when a source omits them). A
 * [site] elevation is needed for the parcel thermal top / cumulus base; w* needs only the
 * solar + boundary-layer inputs, so it still reads where the launch geometry is unknown.
 */
fun assessThermalDays(
    forecast: WeatherForecast,
    site: SiteContext? = null,
    maxDays: Int = 2,
): List<ThermalOutlook> {
    if (forecast.hourly.isEmpty()) return emptyList()

    val offsetMs = forecast.utcOffsetSeconds * 1000L
    fun dayKey(ms: Long): Long = Math.floorDiv(ms + offsetMs, DAY_MS)
    fun localHour(ms: Long): Int = Math.floorMod((ms + offsetMs) / HOUR_MS, 24L).toInt()

    val sunByDay = forecast.daily.associateBy { dayKey(it.startTime) }
    val hoursByDay = forecast.hourly.groupBy { dayKey(it.startTime) }

    return hoursByDay.keys.sorted().take(maxDays).map { key ->
        val sun = sunByDay[key]
        val sunrise = sun?.sunriseMs
        val sunset = sun?.sunsetMs
        fun daylight(ms: Long) =
            if (sunrise != null && sunset != null) ms in sunrise..sunset
            else localHour(ms) in 5..20

        val hours = hoursByDay.getValue(key).sortedBy { it.startTime }
            .filter { daylight(it.startTime) }
            .map { period -> thermalHour(period.startTime, period.weather, site) }

        val usable = hours.filter { it.usable }
        val peak = hours.maxWithOrNull(
            compareBy<ThermalHour>({ it.wStarMs ?: -1.0 }, { it.strength.ordinal }),
        )?.takeIf { it.wStarMs != null || it.strength.ordinal >= ThermalQuality.WEAK.ordinal }

        ThermalOutlook(
            dayStartMs = key * DAY_MS - offsetMs,
            hours = hours,
            windowStartMs = usable.firstOrNull()?.startMs,
            windowEndMs = usable.lastOrNull()?.let { it.startMs + HOUR_MS },
            peak = peak,
            utcOffsetSeconds = forecast.utcOffsetSeconds,
        )
    }
}

/** Build one hour's thermal read: w* strength + the parcel-derived top/cumulus geometry. */
private fun thermalHour(
    startMs: Long,
    w: com.ternparagliding.utils.io.WeatherData,
    site: SiteContext?,
): ThermalHour {
    val sounding = w.profile?.let { prof ->
        site?.elevationM?.let { elev ->
            analyseSounding(w.temperature, dewpointC(w.temperature, w.humidity), elev, prof)
        }
    }
    // Depth for w*: the model's boundary-layer height when available, else the parcel thermal
    // top from the sounding (both are "how high convection reaches").
    val zi = w.boundaryLayerHeightM ?: sounding?.thermalTopAglM
    val wStar = convectiveVelocityScale(w.shortwaveRadiation, zi, w.temperature)
    val strength = assessQuality(w, site).thermal

    return ThermalHour(
        startMs = startMs,
        wStarMs = wStar,
        strength = strength,
        ziAglM = zi,
        thermalTopMslM = sounding?.thermalTopMslM,
        thermalTopAglM = sounding?.thermalTopAglM,
        cuBaseMslM = if (sounding?.cumulus == true) sounding.cloudBaseMslM else null,
    )
}
