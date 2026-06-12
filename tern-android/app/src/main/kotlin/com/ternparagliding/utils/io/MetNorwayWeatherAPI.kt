package com.ternparagliding.utils.io

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * MET Norway (Yr) — the independent **secondary** weather provider behind
 * [FallbackWeatherAPI]. Open-Meteo aggregates the national models we prefer, but
 * it is a single point of failure for *availability*; MET Norway is a separate,
 * free, no-key, global, government-grade source. When Open-Meteo is unreachable
 * the pilot still gets the essentials.
 *
 * It is deliberately **surface-only**: the `locationforecast/compact` product
 * carries wind, temperature, humidity, cloud, pressure and precipitation — but no
 * CAPE, no pressure-level temps, no lightning potential. So those fields come back
 * null and the convective/stability/Skew-T reads gracefully degrade (a null factor
 * is skipped, never a false alarm). The point of the fallback is availability, not
 * parity. Never breaks, only degrades.
 */
class MetNorwayWeatherAPI(
    // MET Norway's ToU require an identifying User-Agent; requests without one are blocked.
    private val userAgent: String = "TernParagliding/1.0 (paragliding flight app; contact via github.com/Tern)",
) : WeatherAPI {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        @Volatile
        private var baseUrl = "https://api.met.no/weatherapi/locationforecast/2.0/compact"

        @androidx.annotation.VisibleForTesting
        fun setBaseUrlForTesting(url: String) { baseUrl = url }

        @androidx.annotation.VisibleForTesting
        fun resetBaseUrl() { baseUrl = "https://api.met.no/weatherapi/locationforecast/2.0/compact" }

        private const val FORECAST_HOURS = 48
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl?lat=51.5&lon=-0.1")
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun fetchForecast(lat: Double, lng: Double): WeatherForecast? = withContext(Dispatchers.IO) {
        try {
            // MET Norway expects coordinates truncated to 4 decimals (caching/ToU).
            val rlat = Math.round(lat * 10000.0) / 10000.0
            val rlon = Math.round(lng * 10000.0) / 10000.0
            val req = Request.Builder()
                .url("$baseUrl?lat=$rlat&lon=$rlon")
                .header("User-Agent", userAgent)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("MetNorwayWeatherAPI", "API Error: ${response.code} ${response.message}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                parseMetNorwayCompact(body)
            }
        } catch (e: IOException) {
            Log.w("MetNorwayWeatherAPI", "Network error fetching forecast", e)
            null
        } catch (e: Exception) {
            Log.w("MetNorwayWeatherAPI", "Error parsing forecast data", e)
            null
        }
    }

    override suspend fun fetchBatchForecast(locations: List<LocationRequest>): Map<String, WeatherForecast?> =
        withContext(Dispatchers.IO) {
            // MET Norway has no multi-point endpoint; one call per location. The cap
            // keeps a fallback burst polite. Each failure degrades to null independently.
            locations.take(WeatherAPI.MAX_BATCH_LOCATIONS).associate { loc ->
                loc.id to fetchForecast(loc.lat, loc.lon)
            }
        }
}

/**
 * Parse MET Norway `locationforecast/2.0/compact` JSON into a [WeatherForecast].
 * Top-level [parseMetNorwayCompact] so the mapping is unit-testable without network.
 * Returns null only if the document has no usable timeseries.
 */
internal fun parseMetNorwayCompact(json: String): WeatherForecast? {
    return try {
        val mapper = jacksonObjectMapper()
        @Suppress("UNCHECKED_CAST")
        val root = mapper.readValue<Map<String, Any>>(json)

        @Suppress("UNCHECKED_CAST")
        val properties = root["properties"] as? Map<String, Any> ?: return null
        @Suppress("UNCHECKED_CAST")
        val timeseries = properties["timeseries"] as? List<Map<String, Any>> ?: return null
        if (timeseries.isEmpty()) return null

        val periods = timeseries.asSequence()
            .take(FORECAST_HOURS_PARSE)
            .mapNotNull { entry -> metEntryToPeriod(entry) }
            .toList()
        if (periods.isEmpty()) return null

        WeatherForecast(
            current = periods.first().weather,
            daily = emptyList(),          // compact has no daily roll-up; degrade rather than fabricate
            hourly = periods,
        )
    } catch (e: Exception) {
        Log.w("MetNorwayWeatherAPI", "Failed to parse compact forecast", e)
        null
    }
}

private const val FORECAST_HOURS_PARSE = 48

private fun metEntryToPeriod(entry: Map<String, Any>): ForecastPeriod? {
    val timeStr = entry["time"] as? String ?: return null
    val startTime = try {
        java.time.Instant.parse(timeStr).toEpochMilli()
    } catch (e: Exception) {
        return null
    }

    @Suppress("UNCHECKED_CAST")
    val data = entry["data"] as? Map<String, Any> ?: return null
    @Suppress("UNCHECKED_CAST")
    val instant = (data["instant"] as? Map<String, Any>)?.get("details") as? Map<String, Any> ?: return null

    fun num(m: Map<String, Any>?, key: String): Double? = (m?.get(key) as? Number)?.toDouble()

    // Precipitation lives in the forward-looking blocks, not the instant.
    @Suppress("UNCHECKED_CAST")
    val next1h = (data["next_1_hours"] as? Map<String, Any>)?.get("details") as? Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    val next6h = (data["next_6_hours"] as? Map<String, Any>)?.get("details") as? Map<String, Any>
    val precipProb = num(next1h, "probability_of_precipitation") ?: num(next6h, "probability_of_precipitation")

    val windMs = num(instant, "wind_speed") ?: 0.0
    val gustMs = num(instant, "wind_speed_of_gust")
    val windKn = windMs * WeatherAPI.MS_TO_KNOTS

    val weather = WeatherData(
        wind = WindData(
            speed = windKn,
            direction = num(instant, "wind_from_direction") ?: 0.0,
            gust = (gustMs?.let { it * WeatherAPI.MS_TO_KNOTS }) ?: 0.0,
        ),
        temperature = num(instant, "air_temperature") ?: 0.0,
        humidity = num(instant, "relative_humidity") ?: 0.0,
        visibility = 10.0,                                  // not reported by MET → assume clear (km)
        pressure = num(instant, "air_pressure_at_sea_level") ?: 1013.25,
        cloudCover = num(instant, "cloud_area_fraction") ?: 0.0,
        timestamp = startTime,
        // Surface-only source: these stay null on purpose → convective/stability reads degrade.
        temp850hPa = null,
        temp925hPa = null,
        cape = null,
        lightningPotential = null,
        windSpeed10m = windKn,                              // MET wind is the 10 m wind
        precipProbability = precipProb,
    )

    return ForecastPeriod(
        startTime = startTime,
        endTime = startTime + 3600000L,
        weather = weather,
        shortForecast = "MET Norway",
    )
}
