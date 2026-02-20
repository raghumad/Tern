package com.madanala.tern.redux


import android.util.Log
import org.osmdroid.util.GeoPoint

// Route management constants imported from Constants.kt

/**
 * Redux reducers for map functionality - organized by functional groups
 */
fun mapReducer(state: MapState, action: MapAction): MapState = when (action) {
    // Permission & Location Management
    is MapAction.RequestLocationPermission,
    is MapAction.UpdateLocationPermission,
    is MapAction.UpdateUserLocation,
    is MapAction.SetLocationReady,
    is MapAction.UpdateGpsStatus,
    MapAction.RetryGpsAcquisition -> handlePermissionAndLocationActions(state, action)

    // Map Viewport & Display
    is MapAction.UpdateRotation,
    is MapAction.UpdateCenter,
    is MapAction.UpdateZoom,
    is MapAction.UpdateMapMovement -> handleMapViewportActions(state, action)

    // Overlay Management
    is MapAction.SetOverlayEnabled,
    is MapAction.UpdateOverlayConfig -> handleOverlayActions(state, action)

    // Loading States
    is MapAction.SetLoadingAirspaces,
    is MapAction.SetLoadingPGSpots -> handleLoadingStateActions(state, action)

    // Cache Management
    is MapAction.UpdateCurrentCountryCode,
    is MapAction.AddAirspaceCountry,
    is MapAction.AddPGSpotCountry,
    is MapAction.ClearAirspaceCache,
    is MapAction.ClearPGSpotCache -> handleCacheActions(state, action)

    // Configuration & UI
    is MapAction.UpdateMapStyle,
    is MapAction.SetCompassVisible -> handleConfigurationAndUIActions(state, action)

    // Settings & Preferences
    is MapAction.SetSettingsOverlayEnabled,
    is MapAction.SetUnitPreference -> handleSettingsActions(state, action)

    // Sensor & Flight Data


    // User Preferences & Layout
    is MapAction.SetHandedness,
    is MapAction.UpdateHandednessSource,
    is MapAction.UpdateUserPreferences -> handleUserPreferencesActions(state, action)

    // Route Management
    is MapAction.AddRoute,
    is MapAction.RemoveRoute,
    is MapAction.UpdateRoute,
    is MapAction.ClearAllRoutes -> handleRouteActions(state, action)

    // Waypoint Management
    is MapAction.AddWaypointToRoute,
    is MapAction.RemoveWaypoint,
    is MapAction.UpdateWaypoint,
    is MapAction.UpdateWaypointType,
    is MapAction.UpdateWaypointRadius,
    is MapAction.UpdateWaypointAltitude,
    is MapAction.UpdateWaypointTimeGates,
    is MapAction.ReorderWaypoint -> handleWaypointActions(state, action)

    // Interactive Editing
    is MapAction.SelectWaypoint,
    MapAction.DeselectWaypoint,
    is MapAction.StartWaypointDrag,
    is MapAction.UpdateWaypointDrag,
    MapAction.EndWaypointDrag,
    MapAction.CancelWaypointDrag -> handleInteractiveEditingActions(state, action)

    // Route Selection
    is MapAction.SelectRoute,
    MapAction.DeselectRoute -> handleRouteSelectionActions(state, action)

    // Smart Suggestion
    is MapAction.SetSmartSuggestion,
    is MapAction.CheckSmartSuggestion,
    MapAction.ClearSmartSuggestion -> handleSmartSuggestionActions(state, action)

    // Map Interaction
    is MapAction.LongPressMap -> handleLongPressMap(state, action)
}

// Route Planning Constants
private const val DEFAULT_ROUTE_NAME_PREFIX = "Route"
private const val WAYPOINT_LABEL_PREFIX = "WP"
private const val WAYPOINT_LABEL_SEPARATOR = "-"
private const val FIRST_ROUTE_INDEX = 1

/**
 * Handle long press map action (Waypoint Creation / Smart Select)
 */
private fun handleLongPressMap(state: MapState, action: MapAction.LongPressMap): MapState {
    // 1. Smart Select: Check for nearby waypoints
    val nearbyResult = findNearbyWaypoint(state.routes, action.geoPoint, 0.05)
    if (nearbyResult != null) {
        val (route, waypoint) = nearbyResult
        return state.copy(
            selectedRouteId = route.id,
            selectedWaypoint = WaypointSelection(route.id, waypoint.id)
        )
    }

    // 2. Create New Waypoint/Route
    if (state.routes.isEmpty()) {
        // Create first route
        val newRoute = createFirstRoute(action.geoPoint, 1, action.type, action.label)
        return state.copy(
            routes = state.routes + newRoute,
            selectedRouteId = newRoute.id,
            selectedWaypoint = WaypointSelection(newRoute.id, newRoute.waypoints.first().id)
        )
    } else {
        val selectedRouteId = state.selectedRouteId
        if (selectedRouteId != null) {
            // Add to selected route
            val selectedRoute = state.routes.find { it.id == selectedRouteId }
            if (selectedRoute != null) {
                val (updatedRoutes, newWaypointId) = addWaypointToRouteState(state.routes, selectedRoute, action.geoPoint, action.type, action.label)
                return state.copy(
                    routes = updatedRoutes,
                    selectedWaypoint = WaypointSelection(selectedRouteId, newWaypointId)
                )
            }
        }
        
        // No route selected or selected route not found -> Create new route
        val newRouteIndex = state.routes.size + 1
        val newRoute = createFirstRoute(action.geoPoint, newRouteIndex, action.type, action.label)
        return state.copy(
            routes = state.routes + newRoute,
            selectedRouteId = newRoute.id,
            selectedWaypoint = WaypointSelection(newRoute.id, newRoute.waypoints.first().id)
        )
    }
}

/**
 * Helper: Add waypoint to a route and return updated routes list + new waypoint ID
 */
private fun addWaypointToRouteState(
    routes: List<com.madanala.tern.model.Route>,
    targetRoute: com.madanala.tern.model.Route,
    geoPoint: GeoPoint,
    type: com.madanala.tern.model.Waypoint.Type = com.madanala.tern.model.Waypoint.Type.TURNPOINT,
    label: String? = null
): Pair<List<com.madanala.tern.model.Route>, String> {
    val waypointNumber = targetRoute.waypoints.size + 1
    val routeIndex = routes.indexOf(targetRoute) + 1
    val finalLabel = label ?: "$WAYPOINT_LABEL_PREFIX$routeIndex$WAYPOINT_LABEL_SEPARATOR$waypointNumber"
    val newWaypointId = java.util.UUID.randomUUID().toString()

    val updatedRoutes = routes.map { route ->
        if (route.id == targetRoute.id) {
            route.addWaypoint(
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = type,
                label = finalLabel,
                id = newWaypointId
            )
        } else route
    }
    return Pair(updatedRoutes, newWaypointId)
}

/**
 * Helper: Create the first route with a single waypoint
 */
private fun createFirstRoute(
    geoPoint: GeoPoint,
    routeIndex: Int,
    type: com.madanala.tern.model.Waypoint.Type = com.madanala.tern.model.Waypoint.Type.TURNPOINT,
    label: String? = null
): com.madanala.tern.model.Route {
    val waypointLabel = label ?: "$WAYPOINT_LABEL_PREFIX$routeIndex$WAYPOINT_LABEL_SEPARATOR$FIRST_ROUTE_INDEX"
    val routeName = "$DEFAULT_ROUTE_NAME_PREFIX $routeIndex"

    return com.madanala.tern.model.Route(
        name = routeName,
        waypoints = listOf(
            com.madanala.tern.model.Waypoint(
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = type,
                label = waypointLabel
            )
        )
    )
}

/**
 * Helper: Find existing waypoint within tolerance distance
 */
private fun findNearbyWaypoint(
    routes: List<com.madanala.tern.model.Route>,
    geoPoint: GeoPoint,
    toleranceDegrees: Double
): Pair<com.madanala.tern.model.Route, com.madanala.tern.model.Waypoint>? {
    val targetHilbertIndex = com.madanala.tern.utils.MapOverlayCacheUtils.computeHilbertIndex(geoPoint, 16)

    routes.forEach { route ->
        route.waypoints.forEach { waypoint ->
            val dLat = waypoint.lat - geoPoint.latitude
            val dLon = waypoint.lon - geoPoint.longitude
            val distanceDegrees = kotlin.math.sqrt(dLat * dLat + dLon * dLon)

            if (distanceDegrees <= toleranceDegrees) {
                return Pair(route, waypoint)
            }
        }
    }
    return null
}

/**
 * Handle smart suggestion actions
 */
private fun handleSmartSuggestionActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SetSmartSuggestion -> state.copy(smartSuggestionState = SmartSuggestionState(
        nearbyPGSpot = action.nearbyPGSpot,
        pendingWaypointCreation = action.pendingWaypointCreation
    ))
    MapAction.ClearSmartSuggestion -> state.copy(smartSuggestionState = SmartSuggestionState(
        nearbyPGSpot = null,
        pendingWaypointCreation = null
    ))
    is MapAction.CheckSmartSuggestion -> state
    else -> state
}

/**
 * Handle permission and location-related actions
 */
private fun handlePermissionAndLocationActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.RequestLocationPermission -> state.copy(permissionRequested = true)
    is MapAction.UpdateLocationPermission -> state.copy(hasLocationPermission = action.granted, permissionRequested = true)
    is MapAction.UpdateUserLocation -> state.copy(userLocation = action.location)
    is MapAction.SetLocationReady -> state.copy(isLocationReady = action.ready)
    is MapAction.UpdateGpsStatus -> state.copy(gpsStatus = action.status)
    MapAction.RetryGpsAcquisition -> state.copy(
        gpsStatus = GpsStatus.ACQUIRING,
        isLocationReady = false,
        userLocation = null
    )
    else -> state
}

/**
 * Handle map viewport and display actions
 */
private fun handleMapViewportActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.UpdateRotation -> state.copy(rotation = action.rotation)
    is MapAction.UpdateCenter -> state.copy(center = action.center)
    is MapAction.UpdateZoom -> state.copy(zoom = action.zoom)
    is MapAction.UpdateMapMovement -> state.copy(
        rotation = action.rotation ?: state.rotation,
        center = action.center ?: state.center,
        zoom = action.zoom ?: state.zoom
    )
    else -> state
}

/**
 * Handle overlay management actions
 */
private fun handleOverlayActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SetOverlayEnabled -> {
        val newOverlayState = when (action.type) {
            OverlayType.AIRSPACE -> state.overlayState.copy(airspaces = state.overlayState.airspaces.copy(enabled = action.enabled))
            OverlayType.PG_SPOTS -> state.overlayState.copy(pgSpots = state.overlayState.pgSpots.copy(enabled = action.enabled))
            OverlayType.ROUTES -> state.overlayState.copy(routes = state.overlayState.routes.copy(enabled = action.enabled))
        }
        state.copy(overlayState = newOverlayState)
    }
    is MapAction.UpdateOverlayConfig -> {
        val newOverlayState = when (action.type) {
            OverlayType.AIRSPACE -> state.overlayState.copy(airspaces = action.config)
            OverlayType.PG_SPOTS -> state.overlayState.copy(pgSpots = action.config)
            OverlayType.ROUTES -> state.overlayState.copy(routes = action.config)
        }
        state.copy(overlayState = newOverlayState)
    }
    else -> state
}

/**
 * Handle loading state actions
 */
private fun handleLoadingStateActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SetLoadingAirspaces -> state.copy(isLoadingAirspaces = action.loading)
    is MapAction.SetLoadingPGSpots -> state.copy(isLoadingPGSpots = action.loading)
    else -> state
}

/**
 * Handle cache management actions
 */
private fun handleCacheActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.UpdateCurrentCountryCode -> state.copy(currentCountryCode = action.countryCode)
    is MapAction.AddAirspaceCountry -> state.copy(airspaceCountries = state.airspaceCountries + action.countryCode)
    is MapAction.AddPGSpotCountry -> state.copy(pgSpotCountries = state.pgSpotCountries + action.countryCode)
    is MapAction.ClearAirspaceCache -> state.copy(airspaceCountries = emptySet())
    is MapAction.ClearPGSpotCache -> state.copy(pgSpotCountries = emptySet())
    else -> state
}

/**
 * Handle configuration and UI actions
 */
private fun handleConfigurationAndUIActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.UpdateMapStyle -> state.copy(mapStyle = action.style)
    is MapAction.SetCompassVisible -> state.copy(compassVisible = action.visible)
    else -> state
}

/**
 * Handle settings actions
 */
private fun handleSettingsActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SetSettingsOverlayEnabled -> {
        val newOverlayState = when (action.overlayType) {
            "airspaces" -> state.overlayState.copy(airspaces = state.overlayState.airspaces.copy(enabled = action.enabled))
            "hotspots" -> state.overlayState.copy(pgSpots = state.overlayState.pgSpots.copy(enabled = action.enabled))
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
            else -> state.settingsState
        }
        state.copy(settingsState = newSettingsState)
    }
    else -> state
}

/**
 * Handle sensor and flight data actions
 */


/**
 * Handle flight session actions
 */


/**
 * Handle user preferences and layout actions
 */
private fun handleUserPreferencesActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SetHandedness -> state.copy(userPreferences = state.userPreferences.copy(
        handedness = action.handedness,
        lastUpdated = System.currentTimeMillis()
    ))
    is MapAction.UpdateHandednessSource -> state.copy(userPreferences = state.userPreferences.copy(
        handednessSource = action.source,
        lastUpdated = System.currentTimeMillis()
    ))
    is MapAction.UpdateUserPreferences -> state.copy(userPreferences = action.preferences)
    else -> state
}

/**
 * Handle route management actions
 */
private fun handleRouteActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.AddRoute -> {
        val newRoutes = state.routes + action.route
        val limitedRoutes = if (newRoutes.size > RouteConstants.MAX_ROUTES) {
            newRoutes.sortedByDescending { it.createdAt }.take(RouteConstants.MAX_ROUTES)
        } else newRoutes

        val updatedSelection = updateSelectionAfterRouteChange(state.selectedWaypoint, limitedRoutes, action.route.id)
        state.copy(routes = limitedRoutes, selectedWaypoint = updatedSelection)
    }
    is MapAction.RemoveRoute -> {
        val newRoutes = state.routes.filter { it.id != action.routeId }
        val updatedSelection = state.selectedWaypoint?.takeIf { it.routeId != action.routeId }
        val updatedSelectedRouteId = state.selectedRouteId?.takeIf { it != action.routeId }
        state.copy(routes = newRoutes, selectedWaypoint = updatedSelection, selectedRouteId = updatedSelectedRouteId)
    }
    is MapAction.UpdateRoute -> {
        val newRoutes = state.routes.map { if (it.id == action.route.id) action.route else it }
        val updatedSelection = updateSelectionAfterRouteChange(state.selectedWaypoint, newRoutes, action.route.id)
        state.copy(routes = newRoutes, selectedWaypoint = updatedSelection)
    }
    is MapAction.ClearAllRoutes -> state.copy(routes = emptyList(), selectedRouteId = null, selectedWaypoint = null)
    else -> state
}

/**
 * Handle waypoint management actions
 */
private fun handleWaypointActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.AddWaypointToRoute -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.addWaypoint(action.lat, action.lon, action.type, action.label, action.id)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.RemoveWaypoint -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) route.removeWaypoint(action.waypointId) else route
        }
        val updatedSelection = state.selectedWaypoint?.takeIf {
            !(it.routeId == action.routeId && it.waypointId == action.waypointId)
        }
        state.copy(routes = newRoutes, selectedWaypoint = updatedSelection)
    }
    is MapAction.UpdateWaypoint -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, action.lat, action.lon, action.type)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.UpdateWaypointType -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, null, null, action.type)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.UpdateWaypointRadius -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, radius = action.radius)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.UpdateWaypointAltitude -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, alt = action.alt)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.UpdateWaypointTimeGates -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, openTime = action.openTime, closeTime = action.closeTime)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.ReorderWaypoint -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.reorderWaypoint(action.fromIndex, action.toIndex)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    else -> state
}

/**
 * Handle interactive editing actions
 */
private fun handleInteractiveEditingActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SelectWaypoint -> state.copy(selectedWaypoint = WaypointSelection(
        routeId = action.routeId,
        waypointId = action.waypointId,
        isDragging = false
    ))
    MapAction.DeselectWaypoint -> state.copy(selectedWaypoint = null)
    is MapAction.StartWaypointDrag -> {
        val currentSelection = state.selectedWaypoint
        if (currentSelection?.routeId == action.routeId && currentSelection.waypointId == action.waypointId) {
            // Store original position when drag starts
            val route = state.routes.find { it.id == currentSelection.routeId }
            val waypoint = route?.waypoints?.find { it.id == currentSelection.waypointId }
            state.copy(selectedWaypoint = currentSelection.copy(
                isDragging = true,
                originalLat = waypoint?.lat,
                originalLon = waypoint?.lon
            ))
        } else state
    }
    is MapAction.UpdateWaypointDrag -> {
        val currentSelection = state.selectedWaypoint
        if (currentSelection?.isDragging == true) {
            val newRoutes = state.routes.map { route ->
                if (route.id == currentSelection.routeId) {
                    route.updateWaypoint(currentSelection.waypointId, action.lat, action.lon)
                } else route
            }
            state.copy(routes = newRoutes)
        } else state
    }
    MapAction.EndWaypointDrag -> {
        val currentSelection = state.selectedWaypoint
        if (currentSelection?.isDragging == true) {
            state.copy(selectedWaypoint = currentSelection.copy(isDragging = false))
        } else state
    }
    MapAction.CancelWaypointDrag -> {
        val currentSelection = state.selectedWaypoint
        if (currentSelection?.isDragging == true && currentSelection.originalLat != null && currentSelection.originalLon != null) {
            // Restore waypoint to original position
            val newRoutes = state.routes.map { route ->
                if (route.id == currentSelection.routeId) {
                    route.updateWaypoint(currentSelection.waypointId, currentSelection.originalLat, currentSelection.originalLon)
                } else route
            }
            state.copy(
                routes = newRoutes,
                selectedWaypoint = currentSelection.copy(isDragging = false)
            )
        } else state
    }
    else -> state
}

/**
 * Handle route selection actions
 */
private fun handleRouteSelectionActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SelectRoute -> state.copy(selectedRouteId = action.routeId)
    MapAction.DeselectRoute -> state.copy(selectedRouteId = null)
    else -> state
}

/**
 * Update waypoint selection after route changes
 */
private fun updateSelectionAfterRouteChange(
    currentSelection: WaypointSelection?,
    routes: List<com.madanala.tern.model.Route>,
    changedRouteId: String
): WaypointSelection? {
    if (currentSelection == null) return null

    val route = routes.find { it.id == changedRouteId } ?: return null
    val waypointExists = route.waypoints.any { it.id == currentSelection.waypointId }

    return if (waypointExists) currentSelection else null
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

    // Route Waypoint Weather
    is WeatherActions.FetchWeatherForRoute -> {
        // We could track fetching state per route here if needed, 
        // but for now, we'll just let the middleware handle it silently.
        state
    }

    is WeatherActions.RouteWeatherFetched -> {
        val newWaypointWeathers = state.weatherState.waypointWeathers + action.waypointForecasts
        val newWeatherState = state.weatherState.copy(
            waypointWeathers = newWaypointWeathers
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

    // Test actions
    is WeatherActions.RequestWeatherUpdate -> state // No-op for testing
    is WeatherActions.WeatherDataLoaded -> state // No-op for testing
    is WeatherActions.WeatherError -> state // No-op for testing
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
