package com.ternparagliding.redux

import android.util.Log

/**
 * Weather reducers for aviation weather functionality.
 *
 * Split out of MapReducers.kt (Phase 0c god-file split). Same `redux`
 * package, so callers (combinedMapReducer) are unaffected.
 */
fun weatherReducer(state: MapState, action: WeatherActions): MapState = when (action) {
    // Weather data fetching and caching
    is WeatherActions.FetchWeatherForPGSpot -> {
        val newFetchingSpots = state.weatherState.fetchingSpots + action.pgSpotId
        val newWeatherState = state.weatherState.copy(fetchingSpots = newFetchingSpots)
        state.copy(weatherState = newWeatherState)
    }

    is WeatherActions.WeatherFetched -> {
        val newSpotWeathers = if (action.forecast != null) {
            state.weatherState.spotWeathers + (action.pgSpotId to action.forecast)
        } else {
            state.weatherState.spotWeathers // Keep existing data if null returned
        }
        val newFetchingSpots = state.weatherState.fetchingSpots - action.pgSpotId
        val newWeatherState = state.weatherState.copy(
            spotWeathers = newSpotWeathers,
            fetchingSpots = newFetchingSpots,
            errors = state.weatherState.errors - action.pgSpotId // Clear any previous errors
        )
        state.copy(weatherState = newWeatherState)
    }

    is WeatherActions.FetchWeatherForSpots -> {
        val newFetchingSpots = state.weatherState.fetchingSpots + action.spots.map { it.first }
        val newWeatherState = state.weatherState.copy(fetchingSpots = newFetchingSpots)
        state.copy(weatherState = newWeatherState)
    }

    is WeatherActions.SpotsWeatherFetched -> {
        val newSpotWeathers = state.weatherState.spotWeathers + action.forecasts
        val newFetchingSpots = state.weatherState.fetchingSpots - action.forecasts.keys
        val newWeatherState = state.weatherState.copy(
            spotWeathers = newSpotWeathers,
            fetchingSpots = newFetchingSpots,
            errors = state.weatherState.errors - action.forecasts.keys
        )
        state.copy(weatherState = newWeatherState)
    }

    // Route Waypoint Weather
    is WeatherActions.FetchWeatherForRoute -> {
        // We could track fetching state per route here if needed,
        // but for now, we'll just let the middleware handle it silently.
        state
    }

    is WeatherActions.RouteWeatherFetched -> {
        val newWaypointWeathers = state.weatherState.waypointWeathers + action.waypointForecasts
        val newWaypointEtas = state.weatherState.waypointEtas + action.etas
        val newWeatherState = state.weatherState.copy(
            waypointWeathers = newWaypointWeathers,
            waypointEtas = newWaypointEtas
        )
        state.copy(weatherState = newWeatherState)
    }

    is WeatherActions.WeatherFetchError -> {
        val newFetchingSpots = state.weatherState.fetchingSpots - action.pgSpotId
        val newErrors = state.weatherState.errors + (action.pgSpotId to action.error)
        val newWeatherState = state.weatherState.copy(
            fetchingSpots = newFetchingSpots,
            errors = newErrors
        )
        state.copy(weatherState = newWeatherState)
    }

    // Weather display controls
    is WeatherActions.SetWeatherGaugeEnabled -> {
        val newWeatherState = state.weatherState.copy(showWeatherGauges = action.enabled)
        state.copy(weatherState = newWeatherState)
    }

    is WeatherActions.SetWeatherDetailsEnabled -> {
        val newWeatherState = state.weatherState.copy(showWeatherDetails = action.enabled)
        state.copy(weatherState = newWeatherState)
    }

    // Cache management
    is WeatherActions.ClearWeatherCache -> {
        Log.d("WeatherReducers", "Clearing weather cache from Redux state")
        val newWeatherState = state.weatherState.copy(
            spotWeathers = emptyMap(),
            errors = emptyMap(),
            cacheSize = 0,
            cacheHits = 0,
            cacheMisses = 0,
            lastCacheCleanup = System.currentTimeMillis()
        )
        state.copy(weatherState = newWeatherState)
    }

    is WeatherActions.WeatherCacheCleared -> {
        Log.d("WeatherReducers", "Weather cache cleared - ${action.freedEntries} entries freed")
        val newWeatherState = state.weatherState.copy(
            cacheSize = state.weatherState.cacheSize - action.freedEntries,
            lastCacheCleanup = System.currentTimeMillis()
        )
        state.copy(weatherState = newWeatherState)
    }

    // UI controls for weather details
    is WeatherActions.ShowWeatherDetails -> {
        val newWeatherState = state.weatherState.copy(
            showingWeatherDialog = WeatherDialogState(
                pgSpotId = action.pgSpotId,
                spotName = action.spotName,
                forecast = action.forecast
            )
        )
        state.copy(weatherState = newWeatherState)
    }

    is WeatherActions.DismissWeatherDetails -> {
        val newWeatherState = state.weatherState.copy(showingWeatherDialog = null)
        state.copy(weatherState = newWeatherState)
    }

    // API availability monitoring
    is WeatherActions.WeatherAPIStatus -> {
        val newWeatherState = state.weatherState.copy(
            weatherAPIOnline = action.apiAvailable,
            lastAPIStatusCheck = System.currentTimeMillis()
        )
        state.copy(weatherState = newWeatherState)
    }

    // Test actions
    is WeatherActions.RequestWeatherUpdate -> state // No-op for testing
    is WeatherActions.WeatherDataLoaded -> state // No-op for testing
    is WeatherActions.WeatherError -> state // No-op for testing

    // New: Storm Risk
    is WeatherActions.SetStormRisk -> state.copy(
        weatherState = state.weatherState.copy(hasStormRisk = action.hasRisk)
    )
}
