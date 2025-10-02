package com.madanala.tern.overlays

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Base implementation for overlay managers with common functionality
 */
abstract class BaseOverlayManager(
    final override val overlayType: OverlayType,
    protected val mapStore: MapStore
) : OverlayManager, LifecycleObserver {

    protected val TAG = "OverlayManager-${overlayType.name}"
    protected var mapView: MapView? = null
    protected var isAttached = false

    // Debouncing for map movements
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingMapMove: Runnable? = null
    private val mapMoveDebounceMs = 500L

    // Coroutine scope for async operations
    protected val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Redux state subscription
    private var stateJob: kotlinx.coroutines.Job? = null

    override fun onAttach(mapView: MapView) {
        if (isAttached) {
            Log.w(TAG, "Overlay already attached")
            return
        }

        this.mapView = mapView
        isAttached = true

        Log.d(TAG, "Attaching overlay manager")

        // Start listening to Redux state
        startReduxSubscription()

        // Perform initial overlay setup
        onOverlayAttached()
    }

    override fun onDetach() {
        if (!isAttached) {
            Log.w(TAG, "Overlay not attached")
            return
        }

        Log.d(TAG, "Detaching overlay manager")

        // Cancel pending operations
        pendingMapMove?.let { mainHandler.removeCallbacks(it) }
        pendingMapMove = null

        // Stop Redux subscription
        stopReduxSubscription()

        // Perform overlay cleanup
        onOverlayDetached()

        // Clear references
        mapView = null
        isAttached = false

        // Cancel coroutine scope
        coroutineScope.cancel()
    }

    override fun onMapMove(center: GeoPoint, zoom: Double) {
        if (!isAttached) return

        // Debounce map movement like the original ViewModel
        pendingMapMove?.let { mainHandler.removeCallbacks(it) }
        pendingMapMove = Runnable {
            performMapMove(center, zoom)
        }
        mainHandler.postDelayed(pendingMapMove!!, mapMoveDebounceMs)
    }

    override fun onViewportChanged(viewport: BoundingBox) {
        if (!isAttached) return
        onViewportChangedInternal(viewport)
    }

    /**
     * Get the current overlay configuration from Redux state
     */
    protected fun getCurrentConfig(): OverlayConfig {
        val state = mapStore.state.value
        return when (overlayType) {
            OverlayType.AIRSPACE -> state.overlayState.airspaces
            OverlayType.PG_SPOTS -> state.overlayState.pgSpots
            OverlayType.SENSORS -> state.overlayState.sensors
            OverlayType.TERRAIN -> state.overlayState.terrain
        }
    }

    /**
     * Check if this overlay is currently enabled
     */
    protected fun isEnabled(): Boolean = getCurrentConfig().enabled

    /**
     * Start subscribing to Redux state changes
     */
    private fun startReduxSubscription() {
        stateJob = coroutineScope.launch {
            mapStore.state.collectLatest { state ->
                onReduxStateChanged(state)
            }
        }
    }

    /**
     * Stop subscribing to Redux state changes
     */
    private fun stopReduxSubscription() {
        stateJob?.cancel()
        stateJob = null
    }

    /**
     * Called when the overlay is first attached to perform initialization
     */
    protected abstract fun onOverlayAttached()

    /**
     * Called when the overlay is detached to perform cleanup
     */
    protected abstract fun onOverlayDetached()

    /**
     * Perform the actual map move logic after debouncing
     */
    protected abstract fun performMapMove(center: GeoPoint, zoom: Double)

    /**
     * Handle viewport changes (for performance optimization)
     */
    protected abstract fun onViewportChangedInternal(viewport: BoundingBox)

    /**
     * Called when Redux state changes - override to react to state updates
     */
    protected abstract fun onReduxStateChanged(state: MapState)

    /**
     * Clear overlays specific to this manager
     */
    protected abstract fun clearOverlays()
}
