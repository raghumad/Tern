package com.madanala.tern.overlays

import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView

/**
 * OverlayCoordinator: Unified management system for all map overlay types.
 *
 * Coordinates lifecycle and state management for airspace, PG spots, sensors, and terrain overlays.
 * Provides clean API for adding overlay types and handles Redux state synchronization.
 *
 * Architecture: One coordinator manages many overlay managers through clean interfaces.
 */
class OverlayCoordinator {
    private val TAG = "OverlayCoordinator"

    // Registry of active overlay managers by type
    private val activeManagers = mutableMapOf<OverlayType, OverlayManager>()

    // Redux store for state management
    private var mapStore: MapStore? = null

    // Store reference for cleanup
    private var mapView: MapView? = null

    /**
     * Initialize coordinator with Redux store
     */
    fun initialize(mapStore: MapStore, mapView: MapView) {
        this.mapStore = mapStore
        this.mapView = mapView
        Log.d(TAG, "OverlayCoordinator initialized")

        // Start observing Redux state
        startStateObservation()
    }

    /**
     * Add an overlay manager to the coordinator
     * Managers are registered by their overlay type
     */
    fun addOverlayManager(manager: OverlayManager) {
        val overlayType = manager.overlayType

        if (activeManagers.containsKey(overlayType)) {
            removeOverlayManager(overlayType)
        }

        activeManagers[overlayType] = manager
        manager.initialize(mapView!!)

        Log.d(TAG, "Added overlay manager: $overlayType, total managers: ${activeManagers.size}")
    }

    /**
     * Remove overlay manager by type
     */
    fun removeOverlayManager(overlayType: OverlayType) {
        activeManagers[overlayType]?.let { manager ->
            manager.onDetach()
            activeManagers.remove(overlayType)
            Log.d(TAG, "Removed overlay manager: $overlayType")
        }
    }

    /**
     * Get overlay manager by type
     */
    fun getOverlayManager(overlayType: OverlayType): OverlayManager? {
        return activeManagers[overlayType]
    }

    /**
     * Check if overlay type is currently managed
     */
    fun hasOverlayType(overlayType: OverlayType): Boolean {
        return activeManagers.containsKey(overlayType)
    }

    /**
     * Get all currently active overlay types
     */
    fun getActiveOverlayTypes(): Set<OverlayType> {
        return activeManagers.keys.toSet()
    }

    /**
     * Notify all overlays of map movement for data loading
     */
    fun onMapMoved(centerLat: Double, centerLng: Double, zoom: Double) {
        activeManagers.values.forEach { manager ->
            manager.performMapMove(
                org.osmdroid.util.GeoPoint(centerLat, centerLng),
                zoom
            )
        }
    }

    /**
     * Notify all overlays of viewport changes for view management
     */
    fun onViewportChanged(viewport: BoundingBox) {
        activeManagers.values.forEach { manager ->
            manager.onViewportChanged(viewport)
        }
    }

    /**
     * Configure overlay settings through Redux actions
     */
    fun updateOverlayConfig(overlayType: OverlayType, config: OverlayConfig) {
        getOverlayManager(overlayType)?.let { manager ->
            manager.updateConfig(config)
            Log.d(TAG, "Updated config for $overlayType: $config")
        } ?: Log.w(TAG, "No manager found for $overlayType to update config")
    }

    /**
     * Get current config for overlay type
     */
    fun getOverlayConfig(overlayType: OverlayType): OverlayConfig? {
        return getOverlayManager(overlayType)?.getCurrentConfig()
    }

    /**
     * Force refresh data for all overlays
     */
    fun refreshAllOverlays() {
        activeManagers.values.forEach { manager ->
            manager.clearOverlays()
        }
        Log.d(TAG, "Refreshed all overlays (${activeManagers.size} managers)")
    }

    /**
     * Get performance statistics from all overlays
     */
    fun getPerformanceStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        activeManagers.forEach { (type, manager) ->
            stats[type.name] = manager.getPerformanceStats()
        }
        stats["total_active_managers"] = activeManagers.size
        return stats
    }

    /**
     * Start observing Redux state for overlay configuration changes
     */
    private fun startStateObservation() {
        // Note: In real implementation, you might use collectAsState() in a Composable
        // or set up observers in the coordinator lifecycle
        Log.d(TAG, "Started Redux state observation")
    }

    /**
     * Handle Redux state updates for all managed overlays
     */
    fun onReduxStateChanged(state: MapState) {
        activeManagers.values.forEach { manager ->
            manager.onReduxStateChanged(state)
        }
    }

    /**
     * Clean shutdown of all overlays
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down OverlayCoordinator (${activeManagers.size} managers)")

        // Shutdown all managers
        activeManagers.values.forEach { manager ->
            try {
                manager.onDetach()
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down manager ${manager.overlayType}", e)
            }
        }

        activeManagers.clear()
        mapStore = null
        mapView = null

        Log.d(TAG, "OverlayCoordinator shutdown complete")
    }
}
