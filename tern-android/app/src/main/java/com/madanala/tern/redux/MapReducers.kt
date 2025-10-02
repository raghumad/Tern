package com.madanala.tern.redux

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

    // Overlay control actions - new modular system
    is MapAction.SetOverlayEnabled -> {
        val newOverlayState = when (action.type) {
            OverlayType.AIRSPACE -> state.overlayState.copy(airspaces = state.overlayState.airspaces.copy(enabled = action.enabled))
            OverlayType.PG_SPOTS -> state.overlayState.copy(pgSpots = state.overlayState.pgSpots.copy(enabled = action.enabled))
            OverlayType.SENSORS -> state.overlayState.copy(sensors = state.overlayState.sensors.copy(enabled = action.enabled))
            OverlayType.TERRAIN -> state.overlayState.copy(terrain = state.overlayState.terrain.copy(enabled = action.enabled))
        }
        state.copy(overlayState = newOverlayState)
    }
    is MapAction.UpdateOverlayConfig -> {
        val newOverlayState = when (action.type) {
            OverlayType.AIRSPACE -> state.overlayState.copy(airspaces = action.config)
            OverlayType.PG_SPOTS -> state.overlayState.copy(pgSpots = action.config)
            OverlayType.SENSORS -> state.overlayState.copy(sensors = action.config)
            OverlayType.TERRAIN -> state.overlayState.copy(terrain = action.config)
        }
        state.copy(overlayState = newOverlayState)
    }

    // Legacy overlay actions (for migration - will be removed in later chunks)
    is MapAction.SetAirspacesEnabled -> {
        state.copy(airspacesEnabled = action.enabled)
    }
    is MapAction.SetPGSpotsEnabled -> {
        state.copy(pgSpotsEnabled = action.enabled)
    }
    is MapAction.SetOverlaysVisible -> {
        state.copy(showOverlays = action.visible)
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
    else -> state
}
