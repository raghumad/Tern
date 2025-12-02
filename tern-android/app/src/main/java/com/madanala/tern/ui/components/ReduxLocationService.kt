package com.madanala.tern.ui.components

import android.util.Log
import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapStore
import org.osmdroid.util.GeoPoint
import com.madanala.tern.redux.GpsStatus

/**
 * Redux-based location service that handles GPS updates through Redux actions
 * Replaces direct MapViewModel location handling with Redux-compliant architecture
 */
class ReduxLocationService(private val store: MapStore) {

    /**
     * Start location updates through Redux actions
     * This method would be called when location permission is granted
     */
    fun startLocationUpdates() {
        Log.d("ReduxLocationService", "Starting Redux-based location updates")

        // ✅ CORRECT: Set GPS status to acquiring, not location ready
        store.updateGpsStatus(com.madanala.tern.redux.GpsStatus.ACQUIRING)

        // In a full implementation, this would:
        // 1. Initialize location manager
        // 2. Set up location callbacks that dispatch Redux actions
        // 3. Handle location updates through Redux state changes
        // 4. Only set location ready when valid GPS fix is acquired
    }

    /**
     * Stop location updates
     */
    fun stopLocationUpdates() {
        Log.d("ReduxLocationService", "Stopping Redux-based location updates")

        // Dispatch Redux action to indicate location service is stopping
        store.dispatch(MapAction.SetLocationReady(false))
    }

    /**
     * Update current location through Redux action
     * Set location ready when we receive the first valid GPS fix
     */
    fun updateLocation(location: org.osmdroid.util.GeoPoint?) {
        Log.d("DEBUG_LOC", "Received location: $location")
        store.dispatch(MapAction.UpdateUserLocation(location))

        // Set location ready when we receive the first valid location
        // This ensures welcome screen only disappears after GPS fix
        if (location != null && isValidAviationLocation(location) && !store.state.value.isLocationReady) {
            Log.d("ReduxLocationService", "Valid GPS fix received, setting location ready: $location")
            store.setLocationReady(true)
            store.updateGpsStatus(com.madanala.tern.redux.GpsStatus.ACTIVE)
        }
    }

    /**
     * Validate GPS location for aviation use
     */
    private fun isValidAviationLocation(location: org.osmdroid.util.GeoPoint): Boolean {
        // Basic validation for aviation coordinates
        return location.latitude.isFinite() &&
                location.longitude.isFinite() &&
                location.latitude in -90.0..90.0 &&
                location.longitude in -180.0..180.0
    }
}