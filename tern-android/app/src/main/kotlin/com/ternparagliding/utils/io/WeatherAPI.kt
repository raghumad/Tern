package com.ternparagliding.utils.io

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import com.ternparagliding.utils.geo.OfflineGeocoder
import com.ternparagliding.weather.WeatherSourcePolicy
import org.osmdroid.util.GeoPoint

// Create shared mapper instance
private val mapper = jacksonObjectMapper()

/**
 * Consolidated Weather API Data Classes
 */
data class WindData(
    val speed: Double, // knots
    val direction: Double, // degrees (0-360)
    val gust: Double // knots
)

data class WeatherData(
    val wind: WindData,
    val temperature: Double, // Celsius
    val humidity: Double,
    val visibility: Double,
    val pressure: Double,
    val cloudCover: Double, // Percentage 0-100
    val timestamp: Long, // UTC timestamp
    val temp850hPa: Double? = null, // °C at 850hPa pressure level (≈1500m MSL)
    val temp925hPa: Double? = null, // °C at 925hPa pressure level (≈750m MSL)
    val cape: Double? = null,        // Convective Available Potential Energy (J/kg)
    val lightningPotential: Double? = null, // Storm potential
    val windSpeed10m: Double? = null,       // 10m wind speed (knots) — the surface wind a pilot feels at launch
    val windDirection10m: Double? = null,   // 10m wind direction (°) — surface; drives the launch orientation check
    val precipProbability: Double? = null   // precipitation probability (%)
)

data class ForecastPeriod(
    val startTime: Long, // UTC timestamp (ms)
    val endTime: Long,   // UTC timestamp (ms)
    val weather: WeatherData,
    val shortForecast: String,
    // Daily periods carry the day's sun times (same local-wall-clock basis as the
    // hourly timestamps) so the soarable-window scan can bound flyable hours to
    // daylight. Null on hourly periods and where the source omits them.
    val sunriseMs: Long? = null,
    val sunsetMs: Long? = null,
)

data class WeatherForecast(
    val current: WeatherData?,
    val daily: List<ForecastPeriod>,
    val hourly: List<ForecastPeriod>
) {
    fun isStale(): Boolean {
        val firstHourly = hourly.firstOrNull() ?: return true
        val now = System.currentTimeMillis()
        return (now - firstHourly.startTime) > (4 * 3600 * 1000L) // 4 hours in ms
    }

    /**
     * Heuristic for convective danger (Aviation Warning)
     * RFC 005: Significant risk of overdevelopment/convection
     */
    fun hasConvectiveDanger(): Boolean {
        val currentData = current ?: hourly.firstOrNull()?.weather ?: return false
        
        // RFC 005: CAPE > 500 J/kg is convective risk for paragliding
        val hasHighCape = (currentData.cape ?: 0.0) > 500.0
        
        val keywords = listOf("thunderstorm", "overdevelopment", "heavy rain", "convective", "shower")
        val hourlyText = hourly.take(3).any { period -> 
            keywords.any { period.shortForecast.contains(it, ignoreCase = true) }
        }
        
        return hasHighCape || (currentData.cloudCover > 85.0 && currentData.humidity > 85.0) || hourlyText
    }

    /**
     * Heuristic for immediate thunderstorm / lightning danger
     * RFC 005: Immediate tactical threat
     */
    fun hasThunderstorm(): Boolean {
        val currentData = current ?: hourly.firstOrNull()?.weather ?: return false
        
        // RFC 005: Lightning potential > 60%
        val hasLightning = (currentData.lightningPotential ?: 0.0) > 60.0
        
        val keywords = listOf("thunderstorm", "lightning", "squall", "heavy thunderstorm")
        val hourlyText = hourly.take(6).any { period ->
            keywords.any { period.shortForecast.contains(it, ignoreCase = true) }
        }
        
        return hasLightning || hourlyText
    }
}

/**
 * Identifies a physical location for a batch weather request.
 * [id] is returned as the key in the response map, allowing callers to map
 * back to their own domain objects (PG spot IDs, waypoint IDs, etc.)
 */
data class LocationRequest(val id: String, val lat: Double, val lon: Double)

/**
 * WeatherAPI Interface - Supports multiple weather data sources
 * Aviation-grade with graceful failure handling
 */
interface WeatherAPI {
    suspend fun fetchForecast(lat: Double, lng: Double): WeatherForecast?

    /**
     * Fetches weather for multiple locations in a single API call.
     * Significantly reduces API credit usage vs N individual requests.
     * @return Map of [LocationRequest.id] to [WeatherForecast] (null if that location failed)
     */
    suspend fun fetchBatchForecast(locations: List<LocationRequest>): Map<String, WeatherForecast?>

    suspend fun isAvailable(): Boolean

    companion object {
        // Constants for aviation-specific data transformation
        const val KNOTS_TO_MS = 0.514444 // Aviation standard conversion
        const val MS_TO_KNOTS = 1.94384 // Aviation standard conversion
        const val F_TO_C = -17.2222 // Temperature conversion offset
        const val MB_TO_INHG = 0.02953 // Pressure conversion
        const val MAX_BATCH_LOCATIONS = 50 // Hard cap to stay within Open-Meteo weight limits
    }
}

/**
 * OpenMeteo Weather API - European Data Source
 * Based on iOS implementation reference
 */
class OpenMeteoWeatherAPI : WeatherAPI {

    // OpenMeteo client with aviation-optimized timeouts
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS) // Faster than general API limits
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)    // Handle large forecast responses
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        // OpenMeteo API configuration
        @Volatile
        private var baseUrl = "https://api.open-meteo.com/v1/forecast"
        
        @androidx.annotation.VisibleForTesting
        fun setBaseUrlForTesting(url: String) {
            baseUrl = url
        }

        @androidx.annotation.VisibleForTesting
        fun resetBaseUrl() {
            baseUrl = "https://api.open-meteo.com/v1/forecast"
        }

        // Updated to include 80m wind, gusts, cloud cover, visibility, pressure-level temps for Skew-T, and CAPE/Lightning
        private const val HOURLY_PARAMS = "temperature_2m,relative_humidity_2m,precipitation_probability,pressure_msl,wind_speed_10m,wind_direction_10m,wind_speed_80m,wind_direction_80m,wind_gusts_10m,cloud_cover,visibility,temperature_850hPa,temperature_925hPa,cape,lightning_potential"
        private const val DAILY_PARAMS = "temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max,wind_direction_10m_dominant,sunrise,sunset"
        private const val FORECAST_DAYS = 7
        private const val FORECAST_HOURS = 48 // Two days of hourly data
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val testRequest = Request.Builder()
                .url("$baseUrl?latitude=51.5&longitude=-0.1&hourly=temperature_2m&forecast_days=1")
                .build()

            client.newCall(testRequest).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun fetchForecast(lat: Double, lng: Double): WeatherForecast? = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(lat, lng)
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("OpenMeteoWeatherAPI", "API Error: ${response.code} ${response.message}")
                    return@withContext null
                }

                val body = response.body?.string() ?: run {
                    Log.w("OpenMeteoWeatherAPI", "API Empty Body")
                    return@withContext null
                }
                parseForecast(body, lat, lng)
            }
        } catch (e: IOException) {
            // Aviation-grade - fail gracefully without breaking app
            Log.w("OpenMeteoWeatherAPI", "Network error fetching forecast", e)
            null
        } catch (e: Exception) {
            // Any parsing/other errors - fail gracefully
            Log.w("OpenMeteoWeatherAPI", "Error parsing forecast data", e)
            null
        }
    }
    override suspend fun fetchBatchForecast(locations: List<LocationRequest>): Map<String, WeatherForecast?> = withContext(Dispatchers.IO) {
        if (locations.isEmpty()) return@withContext emptyMap()

        // Hard cap to stay within Open-Meteo rate-limit weight formula
        val capped = locations.take(WeatherAPI.MAX_BATCH_LOCATIONS)

        try {
            val lats = capped.joinToString(",") { it.lat.toString() }
            val lons = capped.joinToString(",") { it.lon.toString() }
            val url = "$baseUrl?latitude=$lats&longitude=$lons" +
                      "&hourly=$HOURLY_PARAMS" +
                      "&daily=$DAILY_PARAMS" +
                      "&forecast_days=$FORECAST_DAYS" +
                      "&timezone=auto" +
                      "&windspeed_unit=kn"

            val request = Request.Builder().url(url).build()
            val result = mutableMapOf<String, WeatherForecast?>()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Return null for all on HTTP error
                    capped.forEach { result[it.id] = null }
                    return@withContext result
                }

                val body = response.body?.string() ?: run {
                    capped.forEach { result[it.id] = null }
                    return@withContext result
                }

                Log.d("OpenMeteoWeatherAPI", "Received response: $body")

                // Open-Meteo returns a JSON array when multiple locations are requested,
                // but may return a single object if only one location was requested.
                val jsonArray = try {
                    mapper.readValue<List<Map<String, Any>>>(body)
                } catch (e: Exception) {
                    try {
                        listOf(mapper.readValue<Map<String, Any>>(body))
                    } catch (e2: Exception) {
                        Log.e("OpenMeteoWeatherAPI", "Failed to parse weather response as Array or Object", e2)
                        capped.forEach { result[it.id] = null }
                        return@withContext result
                    }
                }

                jsonArray.forEachIndexed { index, jsonData ->
                    val loc = capped.getOrNull(index) ?: return@forEachIndexed
                    result[loc.id] = try {
                        parseForecast(mapper.writeValueAsString(jsonData), loc.lat, loc.lon)
                    } catch (e: Exception) {
                        Log.w("OpenMeteoWeatherAPI", "Failed to parse batch entry for ${loc.id}", e)
                        null
                    }
                }
            }

            result
        } catch (e: IOException) {
            Log.w("OpenMeteoWeatherAPI", "Network error during batch fetch", e)
            capped.associateBy({ it.id }, { null })
        } catch (e: Exception) {
            Log.w("OpenMeteoWeatherAPI", "Error during batch fetch parsing", e)
            capped.associateBy({ it.id }, { null })
        }
    }


    @androidx.annotation.VisibleForTesting
    internal fun buildUrl(lat: Double, lng: Double): String {
        return "$baseUrl?latitude=$lat&longitude=$lng" +
               "&hourly=$HOURLY_PARAMS" +
               "&daily=$DAILY_PARAMS" +
               modelsParamFor(lat, lng) +
               "&forecast_days=$FORECAST_DAYS" +
               "&timezone=auto" +
               "&windspeed_unit=kn" // Crucial for aviation - knots standard
    }

    /**
     * Per-country model specialization, applied at the *live fetch*. Resolve the
     * country offline (zero-I/O point-in-polygon — no blocking geocode) and ask
     * [WeatherSourcePolicy] for the best free model there (HRRR/AROME/ICON-D2/…).
     * `best_match` (the global default, or an unknown/over-water location) means
     * "let Open-Meteo auto-pick" — so we omit `&models=` entirely and degrade to
     * its built-in selection. Specialize where a better free model exists; degrade
     * to global otherwise.
     */
    @androidx.annotation.VisibleForTesting
    internal fun modelsParamFor(lat: Double, lng: Double): String {
        val country = try {
            OfflineGeocoder.getCountryCode(GeoPoint(lat, lng))
        } catch (e: Exception) {
            null
        }
        val model = WeatherSourcePolicy.modelFor(country)
        return if (model == WeatherSourcePolicy.DEFAULT.model) "" else "&models=$model"
    }

    private fun parseForecast(jsonString: String, lat: Double, lng: Double): WeatherForecast? {
        try {
            @Suppress("UNCHECKED_CAST")
            val jsonData = mapper.readValue<Map<String, Any>>(jsonString)

            val current = extractCurrentWeather(jsonData, lat, lng)
            val hourly = extractHourlyForecast(jsonData)
            val daily = extractDailyForecast(jsonData)

            return WeatherForecast(
                current = current,
                daily = daily,
                hourly = hourly
            )
        } catch (e: Exception) {
            Log.w("OpenMeteoWeatherAPI", "Failed to parse forecast JSON", e)
            return null
        }
    }

    private fun extractCurrentWeather(jsonData: Map<String, Any>, lat: Double, lng: Double): WeatherData? {
        try {
            @Suppress("UNCHECKED_CAST")
            val hourly = jsonData["hourly"] as? Map<String, Any> ?: return null

            // Get first (current) hour data
            @Suppress("UNCHECKED_CAST")
            val temperatures = hourly["temperature_2m"] as? List<Number> ?: return null
            @Suppress("UNCHECKED_CAST")
            val humidities = hourly["relative_humidity_2m"] as? List<Number> ?: return null
            @Suppress("UNCHECKED_CAST")
            val windSpeeds = hourly["wind_speed_80m"] as? List<Number> ?: hourly["wind_speed_10m"] as? List<Number> ?: return null
            @Suppress("UNCHECKED_CAST")
            val windDirections = hourly["wind_direction_80m"] as? List<Number> ?: hourly["wind_direction_10m"] as? List<Number> ?: return null
            @Suppress("UNCHECKED_CAST")
            val windGusts = hourly["wind_gusts_10m"] as? List<Number> ?: return null
            @Suppress("UNCHECKED_CAST")
            val pressures = hourly["pressure_msl"] as? List<Number> ?: return null
            @Suppress("UNCHECKED_CAST")
            val cloudCovers = hourly["cloud_cover"] as? List<Number> ?: return null
            @Suppress("UNCHECKED_CAST")
            val visibilities = hourly["visibility"] as? List<Number>
            @Suppress("UNCHECKED_CAST")
            val temps850 = hourly["temperature_850hPa"] as? List<Number>
            @Suppress("UNCHECKED_CAST")
            val temps925 = hourly["temperature_925hPa"] as? List<Number>
            @Suppress("UNCHECKED_CAST")
            val capes = hourly["cape"] as? List<Number>
            @Suppress("UNCHECKED_CAST")
            val lightnings = hourly["lightning_potential"] as? List<Number>

            if (temperatures.isEmpty()) return null

            return WeatherData(
                wind = WindData(
                    speed = windSpeeds.firstOrNull()?.toDouble() ?: 0.0,
                    direction = windDirections.firstOrNull()?.toDouble() ?: 0.0,
                    gust = windGusts.firstOrNull()?.toDouble() ?: 0.0
                ),
                temperature = temperatures.first().toDouble(),
                humidity = humidities.firstOrNull()?.toDouble() ?: 0.0,
                // OpenMeteo returns visibility in meters. Convert to km. Max out at 10km if missing.
                visibility = visibilities?.firstOrNull()?.toDouble()?.let { it / 1000.0 } ?: 10.0,
                pressure = pressures.firstOrNull()?.toDouble() ?: 1013.25,
                cloudCover = cloudCovers.firstOrNull()?.toDouble() ?: 0.0,
                timestamp = System.currentTimeMillis(),
                temp850hPa = temps850?.firstOrNull()?.toDouble(),
                temp925hPa = temps925?.firstOrNull()?.toDouble(),
                cape = capes?.firstOrNull()?.toDouble(),
                lightningPotential = lightnings?.firstOrNull()?.toDouble(),
                windSpeed10m = (hourly["wind_speed_10m"] as? List<Number>)?.firstOrNull()?.toDouble(),
                windDirection10m = (hourly["wind_direction_10m"] as? List<Number>)?.firstOrNull()?.toDouble(),
                precipProbability = (hourly["precipitation_probability"] as? List<Number>)?.firstOrNull()?.toDouble()
            )
        } catch (e: Exception) {
            Log.w("OpenMeteoWeatherAPI", "Failed to extract current weather", e)
            return null
        }
    }

    private fun extractHourlyForecast(jsonData: Map<String, Any>): List<ForecastPeriod> {
        val periods = mutableListOf<ForecastPeriod>()
        try {
            @Suppress("UNCHECKED_CAST")
            val hourly = jsonData["hourly"] as? Map<String, Any> ?: return emptyList()

            @Suppress("UNCHECKED_CAST")
            val times = hourly["time"] as? List<String> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val temperatures = hourly["temperature_2m"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val humidities = hourly["relative_humidity_2m"] as? List<Number> ?: return emptyList()
            // Prefer 80m wind, fallback to 10m
            @Suppress("UNCHECKED_CAST")
            val windSpeeds = hourly["wind_speed_80m"] as? List<Number> ?: hourly["wind_speed_10m"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val windDirections = hourly["wind_direction_80m"] as? List<Number> ?: hourly["wind_direction_10m"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val windGusts = hourly["wind_gusts_10m"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val pressures = hourly["pressure_msl"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val cloudCovers = hourly["cloud_cover"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val visibilities = hourly["visibility"] as? List<Number>
            @Suppress("UNCHECKED_CAST")
            val temps850 = hourly["temperature_850hPa"] as? List<Number>
            @Suppress("UNCHECKED_CAST")
            val temps925 = hourly["temperature_925hPa"] as? List<Number>
            @Suppress("UNCHECKED_CAST")
            val capes = hourly["cape"] as? List<Number>
            @Suppress("UNCHECKED_CAST")
            val lightnings = hourly["lightning_potential"] as? List<Number>

            val maxPeriods = minOf(times.size, FORECAST_HOURS, temperatures.size)

            for (i in 0 until maxPeriods) {
                try {
                    // Parse time (format: 2025-10-02T13:00)
                    val timeString = times[i]
                    val startTime = parseTimeString(timeString) ?: continue

                    val weather = WeatherData(
                        wind = WindData(
                            speed = windSpeeds.getOrNull(i)?.toDouble() ?: 0.0,
                            direction = windDirections.getOrNull(i)?.toDouble() ?: 0.0,
                            gust = windGusts.getOrNull(i)?.toDouble() ?: 0.0
                        ),
                        temperature = temperatures[i].toDouble(),
                        humidity = humidities.getOrNull(i)?.toDouble() ?: 0.0,
                        visibility = visibilities?.getOrNull(i)?.toDouble()?.let { it / 1000.0 } ?: 10.0,
                        pressure = pressures.getOrNull(i)?.toDouble() ?: 1013.25,
                        cloudCover = cloudCovers.getOrNull(i)?.toDouble() ?: 0.0,
                        timestamp = startTime,
                        temp850hPa = temps850?.getOrNull(i)?.toDouble(),
                        temp925hPa = temps925?.getOrNull(i)?.toDouble(),
                        cape = capes?.getOrNull(i)?.toDouble(),
                        lightningPotential = lightnings?.getOrNull(i)?.toDouble(),
                        windSpeed10m = (hourly["wind_speed_10m"] as? List<Number>)?.getOrNull(i)?.toDouble(),
                        windDirection10m = (hourly["wind_direction_10m"] as? List<Number>)?.getOrNull(i)?.toDouble(),
                        precipProbability = (hourly["precipitation_probability"] as? List<Number>)?.getOrNull(i)?.toDouble()
                    )

                    periods.add(ForecastPeriod(
                        startTime = startTime,
                        endTime = startTime + 3600000L, // 1 hour in ms
                        weather = weather,
                        shortForecast = "Hourly forecast"
                    ))
                } catch (e: Exception) {
                    // Skip malformed periods but continue processing others
                    Log.w("OpenMeteoWeatherAPI", "Skipping malformed hourly period $i", e)
                }
            }
        } catch (e: Exception) {
            Log.w("OpenMeteoWeatherAPI", "Failed to extract hourly forecast", e)
        }
        return periods
    }

    private fun extractDailyForecast(jsonData: Map<String, Any>): List<ForecastPeriod> {
        val periods = mutableListOf<ForecastPeriod>()
        try {
            @Suppress("UNCHECKED_CAST")
            val daily = jsonData["daily"] as? Map<String, Any> ?: return emptyList()

            @Suppress("UNCHECKED_CAST")
            val times = daily["time"] as? List<String> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val maxTemps = daily["temperature_2m_max"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val minTemps = daily["temperature_2m_min"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val windSpeeds = daily["wind_speed_10m_max"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val windDirections = daily["wind_direction_10m_dominant"] as? List<Number> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val sunrises = daily["sunrise"] as? List<String>
            @Suppress("UNCHECKED_CAST")
            val sunsets = daily["sunset"] as? List<String>

            val maxPeriods = minOf(times.size, FORECAST_DAYS, maxTemps.size)

            for (i in 0 until maxPeriods) {
                try {
                    val timeString = times[i]
                    val startTime = parseTimeString(timeString) ?: continue

                    // Use max temp as representative temperature
                    val maxTempValue = maxTemps[i].toDouble()
                    val minTempValue = minTemps.getOrNull(i)?.toDouble()
                    val avgTemp = if (minTempValue != null) {
                        (maxTempValue + minTempValue) / 2.0
                    } else {
                        maxTempValue
                    }

                    val weather = WeatherData(
                        wind = WindData(
                            speed = windSpeeds.getOrNull(i)?.toDouble() ?: 0.0,
                            direction = windDirections.getOrNull(i)?.toDouble() ?: 0.0,
                            gust = windSpeeds.getOrNull(i)?.toDouble() ?: 0.0 // Daily max wind is closest proxy to gust if not explicit
                        ),
                        temperature = avgTemp,
                        humidity = 50.0, // Approximate
                        visibility = 10.0,
                        pressure = 1013.25, // Approximate
                        cloudCover = 0.0, // Not in daily params
                        timestamp = startTime
                    )

                    periods.add(ForecastPeriod(
                        startTime = startTime,
                        endTime = startTime + 86400000L, // 24 hours in ms
                        weather = weather,
                        shortForecast = "Daily forecast",
                        sunriseMs = sunrises?.getOrNull(i)?.let { parseTimeString(it) },
                        sunsetMs = sunsets?.getOrNull(i)?.let { parseTimeString(it) },
                    ))
                } catch (e: Exception) {
                    Log.w("OpenMeteoWeatherAPI", "Skipping malformed daily period $i", e)
                }
            }
        } catch (e: Exception) {
            Log.w("OpenMeteoWeatherAPI", "Failed to extract daily forecast", e)
        }
        return periods
    }

    private fun parseTimeString(timeString: String): Long? {
        return try {
            // Try ISO_LOCAL_DATE_TIME first (e.g., 2025-10-02T13:00)
            try {
                val formatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                val dateTime = java.time.LocalDateTime.parse(timeString, formatter)
                val zoneOffset = java.time.ZoneOffset.UTC
                val zoned = dateTime.atOffset(zoneOffset)
                zoned.toInstant().toEpochMilli()
            } catch (e: java.time.format.DateTimeParseException) {
                // Fallback to ISO_LOCAL_DATE (e.g., 2025-10-02) for daily forecasts
                val formatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                val date = java.time.LocalDate.parse(timeString, formatter)
                val dateTime = date.atStartOfDay()
                val zoneOffset = java.time.ZoneOffset.UTC
                val zoned = dateTime.atOffset(zoneOffset)
                zoned.toInstant().toEpochMilli()
            }
        } catch (e: Exception) {
            Log.w("OpenMeteoWeatherAPI", "Failed to parse time: $timeString", e)
            null
        }
    }
}
