package com.madanala.tern.redux

sealed class OverlayActions {
    data class SetOverlayEnabled(val type: OverlayType, val enabled: Boolean) : OverlayActions()
    data class UpdateOverlayConfig(val type: OverlayType, val config: OverlayConfig) : OverlayActions()
    data class SetMultipleEnabled(val enabledTypes: Map<OverlayType, Boolean>) : OverlayActions()
    data object LoadOverlayDefaults : OverlayActions()
}

/**
 * Weather-specific Redux actions for PG spot weather management
 * Aviation-grade weather orchestration through Redux state management
 */
sealed class WeatherActions {
    // Weather data fetching and caching
    data class FetchWeatherForPGSpot(val pgSpotId: String, val latitude: Double, val longitude: Double) : WeatherActions()
    data class WeatherFetched(val pgSpotId: String, val forecast: com.madanala.tern.utils.WeatherForecast?) : WeatherActions()
    data class WeatherFetchError(val pgSpotId: String, val error: Throwable) : WeatherActions()

    // Weather display controls
    data class SetWeatherGaugeEnabled(val enabled: Boolean) : WeatherActions()
    data class SetWeatherDetailsEnabled(val enabled: Boolean) : WeatherActions()

    // UI controls for weather details
    data class ShowWeatherDetails(val pgSpotId: String, val forecast: com.madanala.tern.utils.WeatherForecast?) : WeatherActions()
    data object DismissWeatherDetails : WeatherActions()

    // Cache management
    data object ClearWeatherCache : WeatherActions()
    data class WeatherCacheCleared(val freedEntries: Int) : WeatherActions()

    // API availability monitoring
    data class WeatherAPIStatus(val apiAvailable: Boolean) : WeatherActions()

    // Additional weather actions for testing
    data object RequestWeatherUpdate : WeatherActions()
    data class WeatherDataLoaded(val data: List<Any>) : WeatherActions()
    data class WeatherError(val error: String) : WeatherActions()
}
