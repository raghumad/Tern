package com.madanala.tern.ui.components

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.madanala.tern.R
import com.madanala.tern.ui.overlays.AirspaceOverlayManager
import com.madanala.tern.ui.overlays.OverlayCoordinator
import com.madanala.tern.ui.overlays.PGSpotOverlayManager
import com.madanala.tern.ui.overlays.RouteOverlayManager
import com.madanala.tern.ui.screens.MAP_VIEW_SATELLITE
import com.madanala.tern.ui.screens.MAP_VIEW_TERRAIN
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private const val USER_LOCATION_ZOOM = 10.0
// Map move debounce moved to companion object for testability
private const val MAP_MIN_ZOOM = 3.0
private const val MAP_MAX_ZOOM = 20.0
private const val SCALE_BAR_OFFSET_Y = 10
private const val TAG = "MapViewModel"

class MapViewModel(application: Application) : AndroidViewModel(application) {

    // Redux-first architecture: MapViewModel serves as a Redux-compliant service
    // for map lifecycle management and overlay coordination, not traditional MVVM state

    // This is a deliberate architectural choice.
    // The MapView is a complex, stateful UI component. To prevent it from being destroyed and
    // recreated on configuration changes (like screen rotation), we hold its instance here in the
    // ViewModel. This is considered a "leak" by the linter because the ViewModel should not
    // traditionally hold a reference to a View.
    //
    // However, this is safe in our case for two reasons:
    // 1. We are using the Application context, not an Activity context. The Application context
    //    is a singleton that lives for the entire app lifecycle, so we are not leaking an Activity.
    //  2. The alternative (saving and restoring all map state) is far more complex and could
    //    lead to UI stutters on rotation. This approach provides a much smoother user experience.
    @SuppressLint("StaticFieldLeak")
    val mapView: MapView

    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var initialZoomDone = false
    var mapStyle by mutableStateOf(MAP_VIEW_TERRAIN)
        private set

    // Redux integration bridge
    private val reduxBridge = ReduxMapBridge(viewModelScope)

    // Redux store accessor for external components
    val mapStore: com.madanala.tern.redux.MapStore?
        get() = reduxBridge.mapStore

    val isLocationReady = reduxBridge.isLocationReady
    val mapRotation = reduxBridge.mapRotation

    // Smart Suggestion State - Migrated to Redux
    // private val _nearbyPGSpot = MutableStateFlow<com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature?>(null)
    // val nearbyPGSpot = _nearbyPGSpot.asStateFlow()
    // private val _pendingWaypointCreation = MutableStateFlow<GeoPoint?>(null)
    // val pendingWaypointCreation = _pendingWaypointCreation.asStateFlow()

    // Overlay Coordinator - Our new advanced overlay system with memory limits
    private val overlayCoordinator = OverlayCoordinator()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReduxUpdate: Runnable? = null

    init {
        mapView = MapView(application).apply {
            setMultiTouchControls(true)
            minZoomLevel = MAP_MIN_ZOOM
            maxZoomLevel = MAP_MAX_ZOOM
            setTileSource(TileSourceFactory.OpenTopo) // A sensible default
        }
        addMapOverlays()
        setupMapListeners()
        initializeOverlaySystem()
        setupReduxBridgeCallbacks()

        // Initialize route editing system immediately (Redux store connected later)
        initializeRouteEditingSystem()
        Log.i(TAG, "MapViewModel Created: $this")
    }

    fun applyMapStyle(style: Int) {
        mapStyle = style
        // Redux dispatch removed - this function is now a reaction to Redux state change
        // reduxBridge.dispatchMapStyleChange(style)

        val tileSource = if (style == MAP_VIEW_SATELLITE) {
            TileSourceFactory.USGS_SAT
        } else {
            TileSourceFactory.OpenTopo
        }
        mapView.setTileSource(tileSource)
    }

    private fun addMapOverlays() {
        val context = getApplication<Application>().applicationContext
        with(mapView.overlays) {
            add(CopyrightOverlay(context))
            add(ScaleBarOverlay(mapView).apply {
                setCentred(true)
                setScaleBarOffset(context.resources.displayMetrics.widthPixels / 2, SCALE_BAR_OFFSET_Y)
            })
            add(RotationGestureOverlay(mapView).apply { isEnabled = true })

            // Location overlay will be added later when permissions are granted
            // See initializeLocationOverlay() method
        }
    }

    /**
     * Initialize location overlay only after location permissions are granted
     * This prevents the "Operation not started" errors
     */
    fun initializeLocationOverlay() {
        if (myLocationOverlay != null) {
            return
        }

        val context = getApplication<Application>().applicationContext

        try {
            Log.d(TAG, "Initializing Location Overlay using factory: $locationProviderFactory")
            val provider = locationProviderFactory(context)
            Log.d(TAG, "Created provider: $provider")
            
            myLocationOverlay = MyLocationNewOverlay(provider, mapView).apply {
                isDrawAccuracyEnabled = true

                // This is the key: Set the center INSTANTLY on first fix
                runOnFirstFix {
                    Log.e(TAG, "runOnFirstFix triggered. initialZoomDone: $initialZoomDone")
                    if (!initialZoomDone) {
                        mapView.controller.setZoom(USER_LOCATION_ZOOM)
                        mapView.controller.setCenter(myLocation)
                        initialZoomDone = true

                        // Dispatch Redux actions for location state
                        reduxBridge.dispatchLocationReady(true)
                        reduxBridge.dispatchUserLocation(myLocation)

                        // Dispatch Redux actions for location state
                        reduxBridge.dispatchLocationReady(true)
                        reduxBridge.dispatchUserLocation(myLocation)
                        val provider = myLocationOverlay?.lastFix?.provider ?: "unknown"
                        Log.i(TAG, "Location Ready: Initial zoom and center set to $myLocation from provider: $provider")

                        // Note: Overlay managers handle data loading through Redux state
                    }
                }

                try {
                    val customIcon: Drawable? = AppCompatResources.getDrawable(context, R.drawable.paragliding_24)
                    customIcon?.let {
                        val bitmap = it.toBitmap()
                        setPersonIcon(bitmap)
                        setDirectionIcon(bitmap)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set custom location icon", e)
                }
            }

            mapView.overlays.add(myLocationOverlay)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize location overlay", e)
            // Don't throw - allow app to continue without location services
        }
    }

    fun startLocationUpdates() {
        myLocationOverlay?.enableMyLocation()
    }



    /**
      * Clear all overlays using the OverlayCoordinator (replaces legacy clearGeoJsonOverlays)
      */
    private fun clearAllOverlays() {
        overlayCoordinator.refreshAllOverlays()
    }











    /**
     * Initialize the new overlay system (Redux store connected later)
     */
    private fun initializeOverlaySystem() {
        // Log.d(TAG, "Initializing overlay system - Redux store will be connected later")

        // Initialize overlay coordinator without Redux store (will be connected later)
        overlayCoordinator.initialize(
            mapStore = null, // Will be set when Redux store is connected
            mapView = mapView,
            context = getApplication()
        )

        // Log.d(TAG, "Overlay coordinator initialized - waiting for Redux store connection")

        // Overlay managers will be created when Redux store is connected via setMapStore()
    }

    /**
     * Setup Redux bridge callbacks
     */
    private fun setupReduxBridgeCallbacks() {
        reduxBridge.onMapStyleChange = { style -> applyMapStyle(style) }
        reduxBridge.onLocationPermissionGranted = {
            initializeLocationOverlay()
            startLocationUpdates()
        }
    }

    /**
     * Setup map listeners for automatic airspace and PG spots loading
     */
    private fun setupMapListeners() {
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                val rotation = mapView.mapOrientation
                val center = mapView.mapCenter

                reduxBridge.updateMapRotation(rotation)

                // Schedule debounced Redux state updates to prevent excessive recompositions
                center?.let { scheduleReduxUpdate(rotation, it as GeoPoint, null) } ?: scheduleReduxUpdate(rotation, null, null)

                // Overlay managers handle their own map-based loading now
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                val rotation = mapView.mapOrientation
                val center = mapView.mapCenter
                val zoom = mapView.zoomLevelDouble

                reduxBridge.updateMapRotation(rotation)

                // Schedule debounced Redux state updates to prevent excessive recompositions
                center?.let { scheduleReduxUpdate(rotation, it as GeoPoint, zoom) } ?: scheduleReduxUpdate(rotation, null, zoom)

                // Overlay managers handle their own map-based loading now
                return true
            }
        })
    }


    /**
     * Schedule debounced Redux state updates to prevent excessive UI recompositions
     * Now uses combined MapMovement action for maximum performance (1 dispatch instead of 3)
     */
    private fun scheduleReduxUpdate(rotation: Float, center: GeoPoint?, zoom: Double?) {
        pendingReduxUpdate?.let { mainHandler.removeCallbacks(it) }
        pendingReduxUpdate = Runnable {
            // Use single combined action instead of 3 separate dispatches
            reduxBridge.dispatchMapMovement(rotation, center, zoom)
            
            // Notify overlay coordinator of map movement to trigger data loading
            if (center != null && zoom != null) {
                overlayCoordinator.onMapMoved(center.latitude, center.longitude, zoom)
            } else if (center != null && mapView != null) {
                // Fallback if zoom not provided (e.g. scroll event)
                overlayCoordinator.onMapMoved(center.latitude, center.longitude, mapView.zoomLevelDouble)
            }
        }
        mainHandler.postDelayed(pendingReduxUpdate!!, MAP_MOVE_DEBOUNCE_MS)
    }

    companion object {
        /**
         * Aviation-optimized: 2 second debounce for smooth flight experience
         * Mutable for testing (set to 0L to disable debounce)
         */
        @androidx.annotation.VisibleForTesting
        var MAP_MOVE_DEBOUNCE_MS = 2000L

        /**
         * Factory for creating the location provider.
         * Can be overridden in tests to inject a mock provider.
         */
        var locationProviderFactory: (Context) -> org.osmdroid.views.overlay.mylocation.IMyLocationProvider = { 
            org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(it) 
        }
    }



    /**
     * Set the Redux store for overlay managers (late initialization for ViewModel compatibility)
     */
    fun setMapStore(store: com.madanala.tern.redux.MapStore?) {
        reduxBridge.setReduxStore(store)
        reduxBridge.setOverlayCoordinator(overlayCoordinator)

        // Re-initialize overlay system with Redux store now that it's available
        if (store != null) {
            initializeOverlaySystemWithRedux(store)
        }

        // If we already have location permission when store is connected, initialize location overlay
        val reduxState = reduxBridge.getReduxState()
        if (reduxState.hasLocationPermission) {
            initializeLocationOverlay()
            startLocationUpdates()
        }
    }

    /**
     * Initialize route editing system immediately (Redux store can be connected later)
     */
    private fun initializeRouteEditingSystem() {
        // Route overlay manager is initialized later in initializeOverlaySystemWithRedux
        // when Redux store is available
    }

    /**
     * Initialize overlay system with Redux store after it's connected
     */
    private fun initializeOverlaySystemWithRedux(store: com.madanala.tern.redux.MapStore) {
        // Log.d(TAG, "Re-initializing overlay system with Redux store")

        // Initialize overlay coordinator with Redux store
        overlayCoordinator.initialize(
            mapStore = store,
            mapView = mapView,
            context = getApplication()
        )

        // Create and register overlay managers (ESSENTIAL for overlay functionality)
        val airspaceManager = AirspaceOverlayManager(getApplication<Application>().applicationContext, store)
        overlayCoordinator.addOverlayManager(airspaceManager)

        val pgSpotManager = PGSpotOverlayManager(getApplication<Application>().applicationContext, store)
        overlayCoordinator.addOverlayManager(pgSpotManager)

        val routeManager = RouteOverlayManager(getApplication<Application>().applicationContext, store)
        overlayCoordinator.addOverlayManager(routeManager)
    }

    /**
     * Update Redux state (called when state changes externally)
     */
    fun updateReduxState(newState: com.madanala.tern.redux.MapState) {
        reduxBridge.updateReduxState(newState)
    }

    /**
     * Debug method to check Redux integration status
     */
    fun logReduxStatus() {
        // Log.d(TAG, "Redux Status - Store connected: ${reduxStore != null}")
        // Log.d(TAG, "Redux Status - Location ready: ${reduxState.isLocationReady}")
        // Log.d(TAG, "Redux Status - Has location permission: ${reduxState.hasLocationPermission}")
    }

    /**
     * Check for smart suggestions (nearby PG spots)
     * Moved from MapViewContainer to allow unit testing and better separation of concerns
     */
    /**
     * Check for smart suggestions (nearby PG spots)
     * Updates state flows which drive the UI
     */
    fun checkForSmartSuggestion(geoPoint: GeoPoint) {
        reduxBridge.mapStore?.dispatch(com.madanala.tern.redux.MapAction.CheckSmartSuggestion(geoPoint))
    }

    fun clearSmartSuggestionState() {
        reduxBridge.mapStore?.dispatch(com.madanala.tern.redux.MapAction.ClearSmartSuggestion)
    }

    override fun onCleared() {
        // Route editing manager removed

        pendingReduxUpdate?.let { mainHandler.removeCallbacks(it) }

        // Important to release map resources
        mapView.onDetach()
        super.onCleared()
        Log.i(TAG, "MapViewModel Cleared: $this")
    }
}
