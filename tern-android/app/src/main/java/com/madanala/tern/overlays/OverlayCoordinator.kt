package com.madanala.tern.overlays

import android.content.Context
import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.utils.CacheManager
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

    // Viewport loading manager for intelligent loading zones
    private var viewportLoadingManager: ViewportLoadingManager? = null

    // Universal country cache manager for all overlay types (Priority 0 fix)
    private var countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager? = null

    /**
     * Initialize coordinator with Redux store
     */
    fun initialize(mapStore: MapStore?, mapView: MapView, context: Context) {
        this.mapStore = mapStore
        this.mapView = mapView
        Log.d(TAG, "OverlayCoordinator initialized")

        // Initialize viewport loading manager for intelligent data loading - use singleton cache
        viewportLoadingManager = ViewportLoadingManager(CacheManager.airspaceCache)

        // Initialize universal country cache manager for all overlay types (Priority 0 fix)
        countryCacheManager = com.madanala.tern.utils.UniversalCountryCacheManager(context)
        Log.d(TAG, "Universal country cache manager initialized for all overlay types")

        // Start observing Redux state if available
        if (mapStore == null) {
            Log.d(TAG, "No Redux store - overlay managers will use default configuration")
        } else {
            startStateObservation()
        }
    }

    /**
     * Add an overlay manager to the coordinator
     * Managers are registered by their overlay type and connected to universal country cache
     */
    fun addOverlayManager(manager: OverlayManager) {
        val overlayType = manager.overlayType

        if (activeManagers.containsKey(overlayType)) {
            removeOverlayManager(overlayType)
        }

        activeManagers[overlayType] = manager
        manager.initialize(mapView!!)

        // Connect overlay manager to universal country cache manager (Priority 0 integration)
        if (manager is com.madanala.tern.overlays.AirspaceOverlayManager) {
            countryCacheManager?.let { countryCache ->
                manager.setCountryCacheManager(countryCache)
                Log.d(TAG, "Connected AirspaceOverlayManager to universal country cache")
            }
        }

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
     * Now routes through UniversalCountryCacheManager for intelligent country management
     */
    fun onMapMoved(centerLat: Double, centerLng: Double, zoom: Double) {
        val centerPoint = org.osmdroid.util.GeoPoint(centerLat, centerLng)

        // Route through Universal Country Cache Manager for intelligent country management (Priority 0)
        countryCacheManager?.onLocationChanged(centerPoint)

        // Continue with existing overlay manager notifications
        activeManagers.values.forEach { manager ->
            manager.performMapMove(centerPoint, zoom)
        }
    }

    /**
     * Notify all overlays of viewport changes for view management
     */
    fun onViewportChanged(viewport: BoundingBox) {
        // Update viewport loading manager priorities first
        viewportLoadingManager?.updateViewport(viewport)

        // Then notify all overlay managers
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
     * Get universal country cache manager for overlay managers that need country data
     */
    fun getCountryCacheManager(): com.madanala.tern.utils.UniversalCountryCacheManager? {
        return countryCacheManager
    }

    /**
     * Get current cached countries for debugging
     */
    fun getCachedCountries(): Set<String> {
        return countryCacheManager?.getCachedCountries() ?: emptySet()
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
     * Get performance statistics from all overlays and viewport loading manager
     */
    fun getPerformanceStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        activeManagers.forEach { (type, manager) ->
            stats[type.name] = manager.getPerformanceStats()
        }
        stats["total_active_managers"] = activeManagers.size

        // Include viewport loading manager stats
        viewportLoadingManager?.getLoadingStats()?.let { viewportStats ->
            stats["viewport_loading"] = viewportStats
        }

        return stats
    }

    /**
     * Register a feature as loaded in the viewport loading system
     */
    fun registerLoadedFeature(featureId: String, centroid: org.osmdroid.util.GeoPoint,
                              loadingZone: ViewportLoadingManager.LoadingZone, loadCost: Int = 1) {
        viewportLoadingManager?.registerLoadedFeature(featureId, centroid, loadingZone, loadCost)
    }

    /**
     * Unregister features from the viewport loading system
     */
    fun unregisterLoadedFeatures(featureIds: List<String>) {
        viewportLoadingManager?.unregisterFeatures(featureIds)
    }

    /**
     * Get loading zone for a point (viewport intelligence)
     */
    fun getLoadingZone(point: org.osmdroid.util.GeoPoint, viewport: BoundingBox): ViewportLoadingManager.LoadingZone? {
        val center = mapView?.mapCenter as? org.osmdroid.util.GeoPoint ?: return null
        val buffer = BoundingBox(
            center.latitude + 0.1,
            center.longitude + 0.1,
            center.latitude - 0.1,
            center.longitude - 0.1
        )
        // Note: This is a simplified implementation - in practice we'd use the current viewport
        return viewportLoadingManager?.getLoadingZone(point, buffer)
    }

    /**
     * Force viewport loading manager to refresh its priorities
     */
    fun refreshViewportPriorities(viewport: BoundingBox) {
        viewportLoadingManager?.updateViewport(viewport)
        Log.d(TAG, "Refreshed viewport loading priorities")
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

        // Shutdown universal country cache manager (Priority 0)
        countryCacheManager?.shutdown()
        countryCacheManager = null

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
