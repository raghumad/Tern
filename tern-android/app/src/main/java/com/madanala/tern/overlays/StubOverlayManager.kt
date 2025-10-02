package com.madanala.tern.overlays

import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint

/**
 * Stub implementation of OverlayManager to test that the interface compiles and can be implemented
 */
class StubOverlayManager(
    overlayType: OverlayType,
    mapStore: MapStore
) : BaseOverlayManager(overlayType, mapStore) {

    override fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "Stub setEnabled: $enabled")
    }

    override fun updateConfig(config: com.madanala.tern.redux.OverlayConfig) {
        Log.d(TAG, "Stub updateConfig: $config")
    }

    override fun onOverlayAttached() {
        Log.d(TAG, "Stub overlay attached")
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "Stub overlay detached")
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        Log.d(TAG, "Stub map move: $center, zoom: $zoom")
    }

    override fun onViewportChangedInternal(viewport: BoundingBox) {
        Log.d(TAG, "Stub viewport changed: $viewport")
    }

    override fun onReduxStateChanged(state: MapState) {
        Log.d(TAG, "Stub Redux state changed: enabled=${isEnabled()}")
    }

    override fun clearOverlays() {
        Log.d(TAG, "Stub clearing overlays")
    }
}
