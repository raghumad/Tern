package com.madanala.tern.redux

import org.osmdroid.util.GeoPoint

/**
 * Redux actions for map functionality
 */
sealed class MapAction {

    // Permission actions
    object RequestLocationPermission : MapAction()
    data class UpdateLocationPermission(val granted: Boolean) : MapAction()

    // Location actions
    data class UpdateUserLocation(val location: GeoPoint?) : MapAction()
    data class SetLocationReady(val ready: Boolean) : MapAction()

    // Map viewport actions
    data class UpdateRotation(val rotation: Float) : MapAction()
    data class UpdateCenter(val center: GeoPoint) : MapAction()
    data class UpdateZoom(val zoom: Double) : MapAction()

    // Combined map movement action for performance optimization
    data class UpdateMapMovement(
        val rotation: Float? = null,
        val center: GeoPoint? = null,
        val zoom: Double? = null
    ) : MapAction()

    // Overlay control actions - new modular system
    data class SetOverlayEnabled(val type: OverlayType, val enabled: Boolean) : MapAction()
    data class UpdateOverlayConfig(val type: OverlayType, val config: OverlayConfig) : MapAction()


    // Loading state actions
    data class SetLoadingAirspaces(val loading: Boolean) : MapAction()
    data class SetLoadingPGSpots(val loading: Boolean) : MapAction()

    // Cache actions
    data class UpdateCurrentCountryCode(val countryCode: String?) : MapAction()
    data class AddAirspaceCountry(val countryCode: String) : MapAction()
    data class AddPGSpotCountry(val countryCode: String) : MapAction()
    object ClearAirspaceCache : MapAction()
    object ClearPGSpotCache : MapAction()

    // Configuration actions
    data class UpdateMapStyle(val style: String) : MapAction()

    // UI actions
    data class SetCompassVisible(val visible: Boolean) : MapAction()

    // Settings actions
    data class SetSettingsOverlayEnabled(val overlayType: String, val enabled: Boolean) : MapAction()
    data class SetUnitPreference(val unitType: String, val unit: String) : MapAction()
}
