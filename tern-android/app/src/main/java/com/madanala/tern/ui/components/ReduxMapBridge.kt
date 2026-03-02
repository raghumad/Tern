package com.madanala.tern.ui.components

import android.util.Log
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.ui.overlays.OverlayCoordinator
import com.madanala.tern.ui.screens.MAP_VIEW_SATELLITE
import com.madanala.tern.ui.screens.MAP_VIEW_TERRAIN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * Redux integration bridge for MapViewModel
 * Handles Redux state observation and action dispatching
 */
class ReduxMapBridge(private val coroutineScope: CoroutineScope) {

    private val TAG = "ReduxMapBridge"

    // Redux store - can be set after construction for ViewModel compatibility
    private var reduxStore: MapStore? = null

    // Redux state integration
    private var reduxState: MapState = MapState()

    // Redux store accessor for external components
    val mapStore: MapStore?
        get() = reduxStore

    private val _isLocationReady = MutableStateFlow(false)
    val isLocationReady = _isLocationReady.asStateFlow()

    private val _mapRotation = MutableStateFlow(0f)
    val mapRotation = _mapRotation.asStateFlow()

    // Callbacks for MapViewModel interactions
    var onMapStyleChange: ((Int) -> Unit)? = null
    var onLocationPermissionGranted: (() -> Unit)? = null

    // Overlay coordinator for forwarding state changes
    private var overlayCoordinator: OverlayCoordinator? = null

    /**
     * Set the Redux store and initialize state observation
     */
    fun setReduxStore(store: MapStore?) {
        reduxStore = store
        reduxState = store?.state?.value ?: MapState()

        // Log current Redux state for debugging
        logReduxStatus()

        // Set up Redux state observation
        setupReduxStateObservation()
    }

    /**
     * Set the overlay coordinator for forwarding state changes
     */
    fun setOverlayCoordinator(coordinator: OverlayCoordinator) {
        overlayCoordinator = coordinator
    }

    /**
     * Get current Redux state
     */
    fun getReduxState(): MapState = reduxState

    /**
     * Update Redux state (called when state changes externally)
     */
    fun updateReduxState(newState: MapState) {
        reduxState = newState
    }

    /**
     * Dispatch Redux action for map style changes
     */
    fun dispatchMapStyleChange(style: Int) {
        val styleString = if (style == MAP_VIEW_SATELLITE) "satellite" else "terrain"
        reduxStore?.dispatch(MapAction.UpdateMapStyle(styleString))
    }

    /**
     * Dispatch Redux actions for location state
     */
    fun dispatchLocationReady(isReady: Boolean) {
        reduxStore?.dispatch(MapAction.SetLocationReady(isReady))
    }

    fun dispatchUserLocation(location: GeoPoint) {
        reduxStore?.dispatch(MapAction.UpdateUserLocation(location))
    }

    /**
     * Dispatch combined map movement action
     */
    fun dispatchMapMovement(rotation: Float, center: GeoPoint?, zoom: Double?) {
        reduxStore?.dispatch(MapAction.UpdateMapMovement(
            rotation = rotation,
            center = center,
            zoom = zoom
        ))
    }

    fun updateMapRotation(rotation: Float) {
        reduxStore?.dispatch(MapAction.UpdateRotation(rotation))
    }

    /**
     * Set up Redux state observation for reactive updates
     */
    private fun setupReduxStateObservation() {
        val store = reduxStore ?: return

        coroutineScope.launch {
            store.state.collect { newState ->
                val oldState = reduxState
                reduxState = newState
                
                // Sync local flows to maintain Single Source of Truth
                _isLocationReady.value = newState.isLocationReady
                _mapRotation.value = newState.rotation

                // Forward state changes to overlay coordinator
                overlayCoordinator?.onReduxStateChanged(newState)

                // Handle map style changes
                if (oldState.mapStyle != newState.mapStyle) {
                    val styleInt = if (newState.mapStyle == "satellite") MAP_VIEW_SATELLITE else MAP_VIEW_TERRAIN
                    onMapStyleChange?.invoke(styleInt)
                }

                // Handle permission changes
                if (oldState.hasLocationPermission != newState.hasLocationPermission) {
                    if (newState.hasLocationPermission) {
                        onLocationPermissionGranted?.invoke()
                    }
                }
            }
        }
    }

    /**
     * Debug method to check Redux integration status
     */
    fun logReduxStatus() {
        Log.d(TAG, "Redux Bridge - Store connected: ${reduxStore != null}")
        Log.d(TAG, "Redux Bridge - Location ready: ${reduxState.isLocationReady}")
        Log.d(TAG, "Redux Bridge - Has location permission: ${reduxState.hasLocationPermission}")
    }
}
