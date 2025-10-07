package com.madanala.tern.redux
import com.madanala.tern.model.FlightData
import com.madanala.tern.model.SensorState
import com.madanala.tern.model.FlightComputerData
import com.madanala.tern.model.FlightMetrics
import org.osmdroid.util.GeoPoint

/**
 * Redux reducers for map functionality
 */
fun mapReducer(state: MapState, action: MapAction): MapState = when (action) {
    // Permission actions
    is MapAction.RequestLocationPermission -> {
        state.copy(permissionRequested = true)
    }
    is MapAction.UpdateLocationPermission -> {
        state.copy(hasLocationPermission = action.granted, permissionRequested = true)
    }

    // Location actions
    is MapAction.UpdateUserLocation -> {
        state.copy(userLocation = action.location)
    }
    is MapAction.SetLocationReady -> {
        state.copy(isLocationReady = action.ready)
    }
    is MapAction.UpdateGpsStatus -> {
        state.copy(gpsStatus = action.status)
    }
    MapAction.RetryGpsAcquisition -> {
        state.copy(
            gpsStatus = com.madanala.tern.redux.GpsStatus.ACQUIRING,
            isLocationReady = false,
            userLocation = null
        )
    }

    // Map viewport actions
    is MapAction.UpdateRotation -> {
        state.copy(rotation = action.rotation)
    }
    is MapAction.UpdateCenter -> {
        state.copy(center = action.center)
    }
    is MapAction.UpdateZoom -> {
        state.copy(zoom = action.zoom)
    }
    is MapAction.UpdateMapMovement -> {
        // Combined map movement update for performance optimization
        state.copy(
            rotation = action.rotation ?: state.rotation,
            center = action.center ?: state.center,
            zoom = action.zoom ?: state.zoom
        )
    }

    is MapAction.SetOverlayEnabled -> {
        val newOverlayState = when (action.type) {
            OverlayType.AIRSPACE -> state.overlayState.copy(airspaces = state.overlayState.airspaces.copy(enabled = action.enabled))
            OverlayType.PG_SPOTS -> state.overlayState.copy(pgSpots = state.overlayState.pgSpots.copy(enabled = action.enabled))
        }
        state.copy(overlayState = newOverlayState)
    }
    is MapAction.UpdateOverlayConfig -> {
        val newOverlayState = when (action.type) {
            OverlayType.AIRSPACE -> state.overlayState.copy(airspaces = action.config)
            OverlayType.PG_SPOTS -> state.overlayState.copy(pgSpots = action.config)
        }
        state.copy(overlayState = newOverlayState)
    }


    // Loading state actions
    is MapAction.SetLoadingAirspaces -> {
        state.copy(isLoadingAirspaces = action.loading)
    }
    is MapAction.SetLoadingPGSpots -> {
        state.copy(isLoadingPGSpots = action.loading)
    }

    // Cache actions
    is MapAction.UpdateCurrentCountryCode -> {
        state.copy(currentCountryCode = action.countryCode)
    }
    is MapAction.AddAirspaceCountry -> {
        state.copy(airspaceCountries = state.airspaceCountries + action.countryCode)
    }
    is MapAction.AddPGSpotCountry -> {
        state.copy(pgSpotCountries = state.pgSpotCountries + action.countryCode)
    }
    is MapAction.ClearAirspaceCache -> {
        state.copy(airspaceCountries = emptySet())
    }
    is MapAction.ClearPGSpotCache -> {
        state.copy(pgSpotCountries = emptySet())
    }

    // Configuration actions
    is MapAction.UpdateMapStyle -> {
        state.copy(mapStyle = action.style)
    }

    // UI actions
    is MapAction.SetCompassVisible -> {
        state.copy(compassVisible = action.visible)
    }

    // Settings actions - Update overlay state through Redux
    is MapAction.SetSettingsOverlayEnabled -> {
        val newOverlayState = when (action.overlayType) {
            "airspaces" -> state.overlayState.copy(airspaces = state.overlayState.airspaces.copy(enabled = action.enabled))
            "hotspots" -> state.overlayState.copy(pgSpots = state.overlayState.pgSpots.copy(enabled = action.enabled)) // hotspots -> pgSpots
            "pgspots" -> state.overlayState.copy(pgSpots = state.overlayState.pgSpots.copy(enabled = action.enabled))
            else -> state.overlayState
        }
        state.copy(overlayState = newOverlayState)
    }
    is MapAction.SetUnitPreference -> {
        val newSettingsState = when (action.unitType) {
            "temperature" -> state.settingsState.copy(temperatureUnit = action.unit)
            "distance" -> state.settingsState.copy(distanceUnit = action.unit)
            "speed" -> state.settingsState.copy(speedUnit = action.unit)
            "altitude" -> state.settingsState.copy(altitudeUnit = action.unit)
            else -> state.settingsState // Fallback for unknown unit types
        }
        state.copy(settingsState = newSettingsState)
    }

    // Sensor actions - real-time flight data integration
    is MapAction.UpdateSensorState -> {
        state.copy(sensorState = action.sensorState)
    }
    is MapAction.UpdateFlightData -> {
        state.copy(currentFlightData = action.flightData)
    }
    is MapAction.UpdateFlightComputerData -> {
        state.copy(flightComputerData = action.flightComputerData)
    }
    is MapAction.UpdateFlightMetrics -> {
        state.copy(flightMetrics = action.flightMetrics)
    }

    // Sensor control actions
    is MapAction.StartSensors -> {
        val newSensorState = state.sensorState.copy(
            isActive = true,
            flightMode = action.flightMode
        )
        state.copy(sensorState = newSensorState)
    }
    MapAction.StopSensors -> {
        val newSensorState = state.sensorState.copy(isActive = false)
        state.copy(sensorState = newSensorState)
    }
    is MapAction.SetSensorConfig -> {
        // Sensor configuration is managed internally by SensorOverlayManager
        state
    }

    // Flight session actions
    is MapAction.StartFlightSession -> {
        val newMetrics = FlightMetrics(
            startTime = System.currentTimeMillis(),
            duration = 0L,
            distance = 0.0,
            maxAltitude = 0.0,
            altitudeGain = 0.0,
            maxGroundSpeed = 0.0,
            averageGroundSpeed = 0.0,
            maxVerticalSpeed = 0.0,
            maxSinkRate = 0.0,
            thermalCount = 0,
            averageGlideRatio = 0.0
        )
        state.copy(flightMetrics = newMetrics)
    }
    is MapAction.EndFlightSession -> {
        // Keep the final metrics for analysis
        state
    }
    is MapAction.UpdateFlightPath -> {
        // Flight path tracking is managed by SensorOverlayManager
        state
    }

    else -> state
}

/**
 * Weather reducers for aviation weather functionality
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
        android.util.Log.d("WeatherReducers", "Clearing weather cache from Redux state")
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
        android.util.Log.d("WeatherReducers", "Weather cache cleared - ${action.freedEntries} entries freed")
        val newWeatherState = state.weatherState.copy(
            cacheSize = state.weatherState.cacheSize - action.freedEntries,
            lastCacheCleanup = System.currentTimeMillis()
        )
        state.copy(weatherState = newWeatherState)
    }

    // UI controls for weather details
    is WeatherActions.ShowWeatherDetails -> {
        val newWeatherState = state.weatherState.copy(
            showingWeatherDialog = action.forecast?.let { Pair(action.pgSpotId, it) }
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
}

/**
 * Combined reducer for all map-related state (Map + Weather)
 * Aviation-grade state management for comprehensive feature integration
 */
fun combinedMapReducer(state: MapState, action: Any): MapState = when (action) {
    is MapAction -> mapReducer(state, action)
    is WeatherActions -> weatherReducer(state, action)
    else -> state // Unknown actions pass through unchanged
}
