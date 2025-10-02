package com.madanala.tern.redux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * Redux store for map functionality
 */
class MapStore : ViewModel() {

    private val _state = MutableStateFlow(MapState())
    val state = _state.asStateFlow()

    fun dispatch(action: MapAction) {
        viewModelScope.launch {
            val newState = mapReducer(_state.value, action)
            _state.value = newState
        }
    }

    fun dispatch(actions: List<MapAction>) {
        actions.forEach(::dispatch)
    }

    // Helper methods for common actions
    fun updateRotation(rotation: Float) = dispatch(MapAction.UpdateRotation(rotation))
    fun updateUserLocation(location: org.osmdroid.util.GeoPoint?) = dispatch(MapAction.UpdateUserLocation(location))
    fun setLocationPermission(granted: Boolean) = dispatch(MapAction.UpdateLocationPermission(granted))

    // Overlay helper methods
    fun setOverlayEnabled(type: OverlayType, enabled: Boolean) = dispatch(MapAction.SetOverlayEnabled(type, enabled))
    fun updateOverlayConfig(type: OverlayType, config: OverlayConfig) = dispatch(MapAction.UpdateOverlayConfig(type, config))
    fun toggleOverlay(type: OverlayType) {
        val currentEnabled = when (type) {
            OverlayType.AIRSPACE -> _state.value.overlayState.airspaces.enabled
            OverlayType.PG_SPOTS -> _state.value.overlayState.pgSpots.enabled
            OverlayType.SENSORS -> _state.value.overlayState.sensors.enabled
            OverlayType.TERRAIN -> _state.value.overlayState.terrain.enabled
        }
        setOverlayEnabled(type, !currentEnabled)
    }
}
