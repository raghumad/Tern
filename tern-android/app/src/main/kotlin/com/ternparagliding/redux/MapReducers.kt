package com.ternparagliding.redux

/**
 * Redux reducers for map functionality - organized by functional groups.
 *
 * Route/waypoint/editing/selection/long-press/smart-suggestion handlers live
 * in RouteReducers.kt; weather handlers live in WeatherReducers.kt (Phase 0c
 * god-file split). All three share the `redux` package.
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
    is MapAction.UpdateMapMovement,
    is MapAction.UpdateBoundingBox -> handleMapViewportActions(state, action)

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
    is MapAction.SetCompassVisible,
    is MapAction.ToggleRoutePanelExpanded,
    is MapAction.SetRoutePanelExpanded -> handleConfigurationAndUIActions(state, action)

    // Settings & Preferences
    is MapAction.SetSettingsOverlayEnabled,
    is MapAction.SetUnitPreference -> handleSettingsActions(state, action)

    // User Preferences & Layout
    is MapAction.SetHandedness,
    is MapAction.UpdateHandednessSource,
    is MapAction.UpdateUserPreferences -> handleUserPreferencesActions(state, action)

    // Route Management (RouteReducers.kt)
    is MapAction.AddRoute,
    is MapAction.RemoveRoute,
    is MapAction.UpdateRoute,
    is MapAction.SurfaceNearbyRoutes,
    is MapAction.ClearAllRoutes -> handleRouteActions(state, action)

    // Waypoint Management (RouteReducers.kt)
    is MapAction.AddWaypointToRoute,
    is MapAction.RemoveWaypoint,
    is MapAction.UpdateWaypoint,
    is MapAction.UpdateWaypointType,
    is MapAction.UpdateWaypointRadius,
    is MapAction.UpdateWaypointAltitude,
    is MapAction.UpdateWaypointTimeGates,
    is MapAction.ReorderWaypoint -> handleWaypointActions(state, action)

    // Interactive Editing (RouteReducers.kt)
    is MapAction.SelectWaypoint,
    MapAction.DeselectWaypoint,
    is MapAction.StartWaypointDrag,
    is MapAction.UpdateWaypointDrag,
    MapAction.EndWaypointDrag,
    MapAction.CancelWaypointDrag -> handleInteractiveEditingActions(state, action)

    // Route Selection (RouteReducers.kt)
    is MapAction.SelectRoute,
    MapAction.DeselectRoute -> handleRouteSelectionActions(state, action)

    // Smart Suggestion (RouteReducers.kt)
    is MapAction.SetSmartSuggestion,
    is MapAction.CheckSmartSuggestion,
    MapAction.ClearSmartSuggestion -> handleSmartSuggestionActions(state, action)

    // Map Interaction (RouteReducers.kt)
    is MapAction.LongPressMap -> handleLongPressMap(state, action)

    // New: Airspace Collision
    is MapAction.SetAirspaceCollision -> state.copy(hasAirspaceCollision = action.hasCollision)

    // Zoom to Route (Signalling action for Middleware)
    is MapAction.ZoomToRoute -> state

    // Mezulla view mode
    is MapAction.CycleMezullaViewMode -> state.copy(
        mezullaViewMode = state.mezullaViewMode.next()
    )
    is MapAction.SetMezullaViewMode -> state.copy(
        mezullaViewMode = action.mode
    )

    // SOS dismiss
    is MapAction.DismissSosAlert -> state.copy(
        dismissedSosAlerts = state.dismissedSosAlerts + action.senderNodeNumber
    )

    // M3: PG spot GeoJSON for MapLibre rendering
    is MapAction.UpdatePgSpotGeoJson -> state.copy(pgSpotGeoJson = action.geoJson)
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
        zoom = action.zoom ?: state.zoom,
        pendingBoundingBox = null // Clear to prevent continuous fighting
    )
    is MapAction.UpdateBoundingBox -> state.copy(pendingBoundingBox = action.box)
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
            OverlayType.MEZULLA -> state.overlayState // Mezulla has no toggle in OverlayState; always on when peers exist
        }
        state.copy(overlayState = newOverlayState)
    }
    is MapAction.UpdateOverlayConfig -> {
        val newOverlayState = when (action.type) {
            OverlayType.AIRSPACE -> state.overlayState.copy(airspaces = action.config)
            OverlayType.PG_SPOTS -> state.overlayState.copy(pgSpots = action.config)
            OverlayType.ROUTES -> state.overlayState.copy(routes = action.config)
            OverlayType.MEZULLA -> state.overlayState // No per-type config for Mezulla
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
    MapAction.ToggleRoutePanelExpanded -> state.copy(isRoutePanelExpanded = !state.isRoutePanelExpanded)
    is MapAction.SetRoutePanelExpanded -> state.copy(isRoutePanelExpanded = action.expanded)
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
 * Combined reducer for all map-related state (Map + Weather)
 * Aviation-grade state management for comprehensive feature integration
 */
fun combinedMapReducer(state: MapState, action: Any): MapState = when (action) {
    is MapAction -> mapReducer(state, action)
    is WeatherActions -> weatherReducer(state, action)
    is com.ternparagliding.mezulla.redux.PeerAction -> state.copy(
        peerState = com.ternparagliding.mezulla.redux.peerReducer(state.peerState, action)
    )
    else -> state // Unknown actions pass through unchanged
}
