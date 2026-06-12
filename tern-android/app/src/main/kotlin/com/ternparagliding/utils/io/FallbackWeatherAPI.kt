package com.ternparagliding.utils.io

/**
 * Provider-independence resilience. Open-Meteo aggregates the *data*, but it's a
 * single point of failure for *availability*. This tries [primary]; if it is
 * unavailable or returns nothing, it degrades to [secondary] (an independent
 * provider — e.g. MET Norway, surface-only). It never throws: a provider failure
 * degrades to the next, and ultimately to null, at which point the caller serves
 * the last cache. Never breaks, only degrades.
 */
class FallbackWeatherAPI(
    private val primary: WeatherAPI,
    private val secondary: WeatherAPI,
) : WeatherAPI {

    override suspend fun fetchForecast(lat: Double, lng: Double): WeatherForecast? {
        runCatching { primary.fetchForecast(lat, lng) }.getOrNull()?.let { return it }
        return runCatching { secondary.fetchForecast(lat, lng) }.getOrNull()
    }

    override suspend fun fetchBatchForecast(locations: List<LocationRequest>): Map<String, WeatherForecast?> {
        val fromPrimary = runCatching { primary.fetchBatchForecast(locations) }.getOrNull() ?: emptyMap()
        val missing = locations.filter { fromPrimary[it.id] == null }
        if (missing.isEmpty()) return fromPrimary
        val fromSecondary = runCatching { secondary.fetchBatchForecast(missing) }.getOrNull() ?: emptyMap()
        return fromPrimary + fromSecondary.filterValues { it != null }
    }

    override suspend fun isAvailable(): Boolean =
        runCatching { primary.isAvailable() }.getOrDefault(false) ||
            runCatching { secondary.isAvailable() }.getOrDefault(false)
}
