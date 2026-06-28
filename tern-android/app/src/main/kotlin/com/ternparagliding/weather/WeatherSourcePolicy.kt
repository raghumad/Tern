package com.ternparagliding.weather

/**
 * Per-country weather **source specialization**. Open-Meteo already aggregates the
 * national high-resolution models, so "specialize" is just choosing the right
 * model id for where the pilot is — HRRR (US, 3 km hourly), AROME (France, 1.5 km),
 * ICON-D2 (central Europe, 2 km), MetNo-Nordic (Scandinavia, 1 km) — and falling
 * back to `best_match` (Open-Meteo's own auto-pick) everywhere else.
 *
 * Rapid-refresh models update hourly, so their cache TTL is tighter (no point
 * caching away the freshness). The degradation ladder pointing *up*: specialize
 * where a better free model exists, degrade to global otherwise.
 */
object WeatherSourcePolicy {

    data class Source(
        val model: String,        // Open-Meteo &models= id
        val rapidRefresh: Boolean,
        val cacheTtlHours: Int,
        val label: String,        // shown to the pilot ("HRRR 3 km")
    )

    /** Global default: Open-Meteo's own best-available pick, longer cache. */
    val DEFAULT = Source("best_match", rapidRefresh = false, cacheTtlHours = 6, label = "best available")

    private val byCountry: Map<String, Source> = buildMap {
        put("US", Source("gfs_hrrr", true, 1, "HRRR 3 km"))
        for (c in listOf("FR", "BE", "LU")) put(c, Source("meteofrance_arome_france_hd", true, 1, "AROME 1.5 km"))
        for (c in listOf("DE", "AT", "CH", "CZ", "PL", "NL")) put(c, Source("icon_d2", true, 2, "ICON-D2 2 km"))
        for (c in listOf("NO", "SE", "FI", "DK")) put(c, Source("metno_nordic", true, 1, "MetNo Nordic 1 km"))
    }

    /** Best free source for an ISO-2 country code; null/unknown → the global default. */
    fun sourceFor(countryCode: String?): Source =
        countryCode?.uppercase()?.let { byCountry[it] } ?: DEFAULT

    fun modelFor(countryCode: String?): String = sourceFor(countryCode).model
    fun cacheTtlHoursFor(countryCode: String?): Int = sourceFor(countryCode).cacheTtlHours
}
