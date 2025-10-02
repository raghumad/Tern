package com.madanala.tern.overlays

import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType

/**
 * Interface for managing individual overlay types on the map
 */
interface OverlayManager {
    val overlayType: OverlayType

    /**
     * Called when the overlay is attached to a map view
     */
    fun onAttach(mapView: MapView)

    /**
     * Called when the overlay is detached from the map view
     */
    fun onDetach()

    /**
     * Called when the map is scrolled or zoomed
     */
    fun onMapMove(center: GeoPoint, zoom: Double)

    /**
     * Update the enabled state of this overlay
     */
    fun setEnabled(enabled: Boolean)

    /**
     * Update the configuration of this overlay
     */
    fun updateConfig(config: OverlayConfig)

    /**
     * Called when the map viewport changes
     */
    fun onViewportChanged(viewport: BoundingBox)
}
