package com.madanala.tern.ui.overlays

import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Interface for managing individual overlay types on the map
 */
interface OverlayManager {
    val overlayType: OverlayType

    /**
     * Initialize the overlay manager
     */
    fun initialize(mapView: MapView)

    /**
     * Called when the overlay is attached to a map view
     */
    fun onAttach(mapView: MapView)

    /**
     * Called when the overlay is detached from the map view
     */
    fun onDetach()

    /**
     * Called when map movement occurs - for data loading
     */
    fun performMapMove(center: GeoPoint, zoom: Double)

    /**
     * Called when viewport changes - for view management
     */
    fun onViewportChanged(viewport: BoundingBox)

    /**
     * Clear all overlays for this manager type
     */
    fun clearOverlays()

    /**
     * Update the enabled state of this overlay
     */
    fun setEnabled(enabled: Boolean)

    /**
     * Update the configuration of this overlay
     */
    fun updateConfig(config: OverlayConfig)

    /**
     * Get current configuration
     */
    fun getCurrentConfig(): OverlayConfig?

    /**
     * Set focus mode for the overlay (declutters map during interaction)
     */
    fun setFocusMode(enabled: Boolean)

    /**
     * Handle Redux state changes
     */
    fun onReduxStateChanged(state: MapState)

    /**
     * Set the Redux store reference (for late initialization)
     */
    fun setReduxStore(store: com.madanala.tern.redux.MapStore?)



    /**
     * Get performance statistics
     */
    fun getPerformanceStats(): Map<String, Any>

    /**
     * Get number of currently rendered overlays (for test synchronization)
     */
    fun getRenderedCount(): Int

    /**
     * Set the overlay coordinator reference
     */
    fun setOverlayCoordinator(coordinator: OverlayCoordinator?)
}
