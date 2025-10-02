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

    // Overlay control actions
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
