package com.madanala.tern.redux

/**
 * Redux state for overlay management
 */
enum class OverlayType {
    AIRSPACE,
    PG_SPOTS,
    SENSORS,
    TERRAIN
}

data class OverlayConfig(
    val enabled: Boolean = false,
    val opacity: Float = 0.8f,
    val showLabels: Boolean = true,
    val filterRadiusMiles: Double = 300.0
)

data class OverlayState(
    val airspaces: OverlayConfig = OverlayConfig(enabled = true), // Default enabled
    val pgSpots: OverlayConfig = OverlayConfig(enabled = false),  // Disabled by default
    val sensors: OverlayConfig = OverlayConfig(enabled = false),  // Future use
    val terrain: OverlayConfig = OverlayConfig(enabled = false)   // Future use
)

/**
 * Weather state for dynamic PG spot weather overlays
 * Aviation-grade weather data management through Redux
 */
data class WeatherState(
    // Weather data for PG spots
    val spotWeathers: Map<String, com.madanala.tern.utils.WeatherForecast> = emptyMap(),

    // Fetch states and errors
    val fetchingSpots: Set<String> = emptySet(),
    val errors: Map<String, Throwable> = emptyMap(),

    // Display controls
    val showWeatherGauges: Boolean = true,
    val showWeatherDetails: Boolean = true,

    // API and cache status
    val weatherAPIOnline: Boolean = true,
    val cacheSize: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,

    // Last update tracking
    val lastCacheCleanup: Long = 0L,
    val lastAPIStatusCheck: Long = 0L
)
