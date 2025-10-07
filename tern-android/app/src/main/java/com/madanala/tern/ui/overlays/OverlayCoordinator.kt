package com.madanala.tern.ui.overlays

import android.content.Context
import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.utils.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import kotlinx.coroutines.*

/**
 * OverlayCoordinator: Unified management system for map overlay types.
 *
 * Coordinates lifecycle and state management for airspace and PG spot overlays.
 * Provides clean API for overlay management and handles Redux state synchronization.
 *
 * Architecture: One coordinator manages overlay managers through clean interfaces.
 */
class OverlayCoordinator {
    private val TAG = "OverlayCoordinator"

    // Registry of active overlay managers by type
    private val activeManagers = mutableMapOf<OverlayType, OverlayManager>()

    // Redux store for state management
    private var mapStore: MapStore? = null

    // Store reference for cleanup
    private var mapView: MapView? = null


    // Universal country cache manager for all overlay types (Priority 0 fix)
    private var countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager? = null

    // Universal overlay animation manager for smooth transitions
    private val overlayAnimationManager = OverlayAnimationManager()

    /**
     * Initialize coordinator with Redux store
     */
    fun initialize(mapStore: MapStore?, mapView: MapView, context: Context) {
        this.mapStore = mapStore
        this.mapView = mapView
        Log.d(TAG, "OverlayCoordinator initialized")


        // Initialize universal country cache manager for all overlay types (Priority 0 fix)
        countryCacheManager = com.madanala.tern.utils.UniversalCountryCacheManager(context)
        Log.d(TAG, "Universal country cache manager initialized for all overlay types")

        // Trigger performance debugger initialization for development monitoring
        try {
            com.madanala.tern.utils.PerformanceDebugger.triggerTestEvents()
        } catch (e: Exception) {
            Log.d(TAG, "PerformanceDebugger not available in release build")
        }

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

        // Connect overlay manager to animation manager for smooth transitions
        if (manager is com.madanala.tern.ui.overlays.BaseOverlayManager) {
            manager.animationManager = overlayAnimationManager
        }

        // Connect overlay managers to universal country cache manager (Priority 0 integration)
        countryCacheManager?.let { countryCache ->
            when (manager) {
                is com.madanala.tern.ui.overlays.AirspaceOverlayManager -> {
                    manager.setCountryCacheManager(countryCache)
                    Log.d(TAG, "✅ Connected AirspaceOverlayManager to universal country cache")
                }
                is com.madanala.tern.ui.overlays.PGSpotOverlayManager -> {
                    manager.setCountryCacheManager(countryCache)
                    Log.d(TAG, "✅ Connected PGSpotOverlayManager to universal country cache")
                }
            }
        } ?: Log.w(TAG, "Country cache manager not available")

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
        // Notify all overlay managers of viewport changes
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
      * Get the universal overlay animation manager for smooth transitions
      */
    fun getAnimationManager(): OverlayAnimationManager {
        return overlayAnimationManager
    }

    /**
     * Get current cached countries for debugging
     */
    fun getCachedCountries(): Set<String> {
        return countryCacheManager?.getCachedCountries() ?: emptySet()
    }

    /**
     * Trigger performance dashboard for testing (development only)
     */
    fun triggerPerformanceDashboard() {
        try {
            com.madanala.tern.utils.PerformanceDebugger.triggerTestEvents()
            Log.d(TAG, "Performance dashboard triggered for testing")
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering performance dashboard", e)
        }
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

        // Shutdown animation manager first
        overlayAnimationManager.shutdown()

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

    /**
      * Universal overlay animation manager that provides smooth transitions for all overlay types
      * Works with Polygons, Markers, and any other overlay type while preserving visual properties
      */
    inner class OverlayAnimationManager {
        private val animationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        /**
          * Animate overlay addition with smooth fade-in while preserving all visual properties
          */
        fun animateOverlayAddition(
            overlay: Any,
            overlayId: String,
            mapView: MapView,
            staggerDelay: Long = 0L,
            onComplete: (() -> Unit)? = null
        ) {
            animationScope.launch {
                try {
                    // Apply stagger delay for multiple overlays
                    if (staggerDelay > 0) {
                        delay(staggerDelay)
                    }

                    when (overlay) {
                        is org.osmdroid.views.overlay.Polygon -> animatePolygonAddition(overlay, mapView)
                        is org.osmdroid.views.overlay.Marker -> animateMarkerAddition(overlay, mapView)
                        else -> {
                            // Unknown overlay type - add immediately
                            if (overlay is org.osmdroid.views.overlay.Overlay) {
                                mapView.overlays.add(overlay)
                                mapView.invalidate()
                            }
                        }
                    }

                    onComplete?.invoke()

                } catch (e: Exception) {
                    // Animation failed - throw exception since animation manager is now mandatory
                    throw IllegalStateException(
                        "Animation manager failed for overlay $overlayId. " +
                        "Animation manager is required for overlay operations.", e
                    )
                }
            }
        }

        /**
          * Animate overlay removal with smooth fade-out while preserving all visual properties
          */
        fun animateOverlayRemoval(
            overlay: Any,
            overlayId: String,
            mapView: MapView,
            onComplete: (() -> Unit)? = null
        ) {
            animationScope.launch {
                try {
                    when (overlay) {
                        is org.osmdroid.views.overlay.Polygon -> animatePolygonRemoval(overlay, mapView)
                        is org.osmdroid.views.overlay.Marker -> animateMarkerRemoval(overlay, mapView)
                        else -> {
                            // Unknown overlay type - remove immediately
                            if (overlay is org.osmdroid.views.overlay.Overlay) {
                                mapView.overlays.remove(overlay)
                                mapView.invalidate()
                            }
                        }
                    }

                    onComplete?.invoke()

                } catch (e: Exception) {
                    // Animation failed - throw exception since animation manager is now mandatory
                    throw IllegalStateException(
                        "Animation manager failed for overlay $overlayId. " +
                        "Animation manager is required for overlay operations.", e
                    )
                }
            }
        }

        /**
           * Animate polygon addition with correct fade-in behavior
           * Fade-in: Add with 0 alpha → Animate to target alpha (preserving original alpha values)
           */
        private suspend fun animatePolygonAddition(polygon: org.osmdroid.views.overlay.Polygon, mapView: MapView) {
            // Store original colors to preserve intended final appearance
            val originalFillColor = polygon.fillPaint.color
            val originalOutlineColor = polygon.outlinePaint.color

            // Extract the intended target alphas from original colors (preserve designer's intent)
            val targetFillAlpha = android.graphics.Color.alpha(originalFillColor)   // ✅ Use original alpha
            val targetOutlineAlpha = android.graphics.Color.alpha(originalOutlineColor) // ✅ Use original alpha

            // Start with 0 alpha (invisible) - both paint alpha and color alpha
            polygon.fillPaint.alpha = 0
            polygon.outlinePaint.alpha = 0

            // Temporarily set colors to full opacity versions for animation
            // (We'll restore original colors at the end)
            polygon.fillPaint.color = android.graphics.Color.argb(targetFillAlpha,   // Use target alpha
                android.graphics.Color.red(originalFillColor),
                android.graphics.Color.green(originalFillColor),
                android.graphics.Color.blue(originalFillColor))
            polygon.outlinePaint.color = android.graphics.Color.argb(targetOutlineAlpha, // Use target alpha
                android.graphics.Color.red(originalOutlineColor),
                android.graphics.Color.green(originalOutlineColor),
                android.graphics.Color.blue(originalOutlineColor))

            // Add to map (overlay is invisible at this point)
            mapView.overlays.add(polygon)

            // Animate from 0 to target alpha
            val animationDuration = 500L
            val steps = 20
            val stepDelay = animationDuration / steps

            for (step in 1..steps) {
                val fadeAlpha = (targetFillAlpha * step) / steps
                polygon.fillPaint.alpha = fadeAlpha
                polygon.outlinePaint.alpha = fadeAlpha
                mapView.invalidate()
                delay(stepDelay)
            }

            // FINAL: Set to target alphas and restore original colors (preserves exact intended appearance)
            polygon.fillPaint.alpha = targetFillAlpha      // ✅ Use original target alpha
            polygon.outlinePaint.alpha = targetOutlineAlpha // ✅ Use original target alpha
            polygon.fillPaint.color = originalFillColor     // ✅ Restore exact original color
            polygon.outlinePaint.color = originalOutlineColor // ✅ Restore exact original color

        }

        /**
           * Animate polygon removal with correct fade-out behavior
           * Fade-out: Animate from current alpha to 0 → Remove overlay
           */
        private suspend fun animatePolygonRemoval(polygon: org.osmdroid.views.overlay.Polygon, mapView: MapView) {
            // Store current alphas (fade from actual current alpha, not hardcoded 255)
            val currentFillAlpha = polygon.fillPaint.alpha      // ✅ Use actual current alpha
            val currentOutlineAlpha = polygon.outlinePaint.alpha // ✅ Use actual current alpha

            // Animate paint alpha to 0 over 500ms
            val animationDuration = 500L
            val steps = 20
            val stepDelay = animationDuration / steps

            for (step in steps downTo 0) {
                val fadeAlpha = (currentFillAlpha * step) / steps  // ✅ Fade from current alpha to 0
                polygon.fillPaint.alpha = fadeAlpha
                polygon.outlinePaint.alpha = fadeAlpha
                mapView.invalidate()
                delay(stepDelay)
            }

            // Remove from map after animation completes
            mapView.overlays.remove(polygon)

        }

        /**
          * Animate marker addition with correct fade-in behavior
          * Fade-in: Add with 0 alpha → Animate to 1.0 alpha
          */
        private suspend fun animateMarkerAddition(marker: org.osmdroid.views.overlay.Marker, mapView: MapView) {
            // Start with 0 alpha (invisible)
            marker.alpha = 0.0f

            // Add to map (marker is invisible at this point)
            mapView.overlays.add(marker)

            // Animate from 0 to 1.0 alpha over 500ms
            val animationDuration = 500L
            val steps = 20
            val stepDelay = animationDuration / steps

            for (step in 1..steps) {
                val fadeAlpha = (1.0f * step) / steps
                marker.alpha = fadeAlpha
                mapView.invalidate()
                delay(stepDelay)
            }

            // Final: Full opacity (1.0) for markers
            marker.alpha = 1.0f
        }

        /**
          * Animate marker removal with correct fade-out behavior
          * Fade-out: Animate to 0 alpha → Remove overlay
          */
        private suspend fun animateMarkerRemoval(marker: org.osmdroid.views.overlay.Marker, mapView: MapView) {
            val currentAlpha = marker.alpha

            // Animate from current alpha to 0 over 500ms
            val animationDuration = 500L
            val steps = 20
            val stepDelay = animationDuration / steps

            for (step in steps downTo 0) {
                val fadeAlpha = (currentAlpha * step) / steps
                marker.alpha = fadeAlpha
                mapView.invalidate()
                delay(stepDelay)
            }

            // Remove from map after animation completes
            mapView.overlays.remove(marker)
        }

        /**
          * Shutdown animation manager and cancel all ongoing animations
          */
        fun shutdown() {
            animationScope.cancel()
        }
    }
}
