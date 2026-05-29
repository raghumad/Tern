package com.ternparagliding.ui.components

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore

// M1: OSMDroid overlay managers are disabled. The MapView is no longer
// rendered — MapLibre handles it — so none of the OSMDroid overlay
// machinery runs.  The overlay manager *classes* still compile (they're
// needed until M2-M5 replace them one by one).
//
// What was removed from init:
//   - mapView creation (MapView(application))
//   - addMapOverlays() (CopyrightOverlay, ScaleBarOverlay, RotationGestureOverlay)
//   - setupMapListeners() (MapListener for scroll/zoom → Redux dispatch)
//   - initializeOverlaySystem() (OverlayCoordinator.initialize)
//   - setupReduxBridgeCallbacks() (bidirectional camera sync via ReduxMapBridge)
//
// What remains:
//   - Smart suggestion logic (used by the waypoint dialog in MapViewContainer)
//   - setMapStore() plumbing so the Compose tree can still hand the store in
//   - onCleared() for lifecycle cleanup

private const val TAG = "MapViewModel"

class MapViewModel(application: Application) : AndroidViewModel(application) {

    // Redux store reference — set by MapViewContainer via setMapStore().
    private var mapStore: MapStore? = null

    init {
        Log.i(TAG, "MapViewModel created (M1: OSMDroid disabled, MapLibre active)")
    }

    /**
     * Accept the Redux store from the Compose tree.
     *
     * M1: We no longer initialize overlay managers here. M2-M5 will
     * reintroduce them one at a time, backed by MapLibre layers instead
     * of OSMDroid FolderOverlays.
     */
    fun setMapStore(store: MapStore?) {
        if (store == mapStore && store != null) {
            Log.d(TAG, "MapStore already connected — skipping")
            return
        }
        mapStore = store
        Log.d(TAG, "MapStore connected")

        // M2: airspace overlay manager initialization will go here
        // M3: PG spot overlay manager initialization will go here
        // M4: route overlay manager initialization will go here
        // M5: Mezulla overlay manager initialization will go here
    }

    // ── Smart suggestion (unchanged) ────────────────────────────────────

    fun checkForSmartSuggestion(geoPoint: org.osmdroid.util.GeoPoint) {
        mapStore?.dispatch(MapAction.CheckSmartSuggestion(geoPoint))
    }

    fun clearSmartSuggestionState() {
        mapStore?.dispatch(MapAction.ClearSmartSuggestion)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override fun onCleared() {
        Log.i(TAG, "MapViewModel cleared")
        mapStore = null
        super.onCleared()
    }
}
