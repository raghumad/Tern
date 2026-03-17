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

import com.madanala.tern.utils.UniversalCountryCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.madanala.tern.utils.normalizePrecision
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


        Log.i(TAG, "MapViewModel Created: $this")
        

    }

    /**
     * Map Style handling
     */
    private fun applyMapStyle(style: Int) {
        mapStyle = style
        // Redux dispatch call managed by ReduxMapBridge


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
                        val normalizedLocation = myLocation.normalizePrecision()
                        reduxBridge.dispatchLocationReady(true)
                        reduxBridge.dispatchUserLocation(normalizedLocation)
                        val provider = myLocationOverlay?.lastFix?.provider ?: "unknown"
                        Log.i(TAG, "Location Ready: Initial zoom and center set to $normalizedLocation from provider: $provider")

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
     * Initialize the new overlay system (Redux store connected later)
     */
    private fun initializeOverlaySystem() {


        // Initialize overlay coordinator without Redux store (will be connected later)
        overlayCoordinator.initialize(
            mapStore = null, // Will be set when Redux store is connected
            mapView = mapView,
            context = getApplication()
        )



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

                // reduxBridge.updateMapRotation(rotation) <-- REMOVED: Bypassed debounce!
                // Schedule debounced Redux state updates including rotation
                center?.let { scheduleReduxUpdate(rotation, it as GeoPoint, null) } ?: scheduleReduxUpdate(rotation, null, null)

                // Overlay managers handle their own map-based loading now
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                val rotation = mapView.mapOrientation
                val center = mapView.mapCenter
                val zoom = mapView.zoomLevelDouble

                // reduxBridge.updateMapRotation(rotation) <-- REMOVED: Bypassed debounce!
                // Schedule debounced Redux state updates including rotation
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
    // Track last dispatched state to prevent duplicate/spam updates
    private var lastDispatchedRotation: Float? = null
    private var lastDispatchedCenter: GeoPoint? = null
    private var lastDispatchedZoom: Double? = null

    /**
     * Schedule debounced Redux state updates to prevent excessive UI recompositions
     * Now uses combined MapMovement action for maximum performance (1 dispatch instead of 3)
     */
    private fun scheduleReduxUpdate(rotation: Float, center: GeoPoint?, zoom: Double?) {
        val normalizedCenter = center?.normalizePrecision()
        pendingReduxUpdate?.let { mainHandler.removeCallbacks(it) }
        pendingReduxUpdate = Runnable {
            // Diffing logic: Only dispatch if something significantly changed
            // This prevents "micro-jitter" updates from spamming the Redux store
            val rotationChanged = lastDispatchedRotation == null || kotlin.math.abs(rotation - lastDispatchedRotation!!) > 0.1f
            // Use simple distance check for center (approx 1 meter)
            val centerChanged = lastDispatchedCenter == null || (normalizedCenter != null && normalizedCenter.distanceToAsDouble(lastDispatchedCenter) > 1.0)
            val zoomChanged = lastDispatchedZoom == null || (zoom != null && kotlin.math.abs(zoom - lastDispatchedZoom!!) > 0.01)

            if (rotationChanged || centerChanged || zoomChanged) {
                // Update tracking variables
                lastDispatchedRotation = rotation
                lastDispatchedCenter = normalizedCenter
                lastDispatchedZoom = zoom

                // Combined MapMovement Action for efficiency
                // Use adaptive timing: Faster response if it's the first move, 
                // or keep it steady if we're in high-frequency update state.
                // For now, we apply the current constant, but we'll dynamicize it in the next step.
                reduxBridge.dispatchMapMovement(rotation, normalizedCenter, zoom)
                
                // Notify overlay coordinator of map movement to trigger data loading
                if (normalizedCenter != null) {
                    if (zoom != null) {
                        overlayCoordinator.onMapMoved(normalizedCenter.latitude, normalizedCenter.longitude, zoom)
                    } else {
                        mapView.let { mv ->
                            overlayCoordinator.onMapMoved(center.latitude, center.longitude, mv.zoomLevelDouble)
                        }
                    }
                }
            }
        }
        
        // Adaptive debouncing: Use 300ms if interacting, 2000ms for steady state
        val debounceTime = if (MAP_MOVE_DEBOUNCE_MS == 0L) 0L else INTERACTIVE_DEBOUNCE_MS
        mainHandler.postDelayed(pendingReduxUpdate!!, debounceTime)
    }

    companion object {
        /**
         * Aviation-optimized: 2 second debounce for smooth flight experience
         * Mutable for testing (set to 0L to disable debounce)
         */
        @androidx.annotation.VisibleForTesting
        var MAP_MOVE_DEBOUNCE_MS = 2000L

        const val INTERACTIVE_DEBOUNCE_MS = 300L
        const val FLIGHT_DEBOUNCE_MS = 2000L

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
        // [IDEMPOTENT GUARD] Only re-initialize if the store instance actually changed
        // This prevents lifecycle "stutters" from spawning multiple manager sessions
        if (store == reduxBridge.mapStore && store != null) {
            Log.d(TAG, "MapStore already connected - skipping redundant overlay initialization")
            return
        }

        // Re-initialize overlay system with Redux store FIRST so managers are ready
        // to receive updates from the bridge
        if (store != null) {
            initializeOverlaySystemWithRedux(store)
        }

        // Connect the bridge (triggers immediate state emission)
        reduxBridge.setOverlayCoordinator(overlayCoordinator)
        reduxBridge.setReduxStore(store)


        // If we already have location permission when store is connected, initialize location overlay
        val reduxState = reduxBridge.getReduxState()
        if (reduxState.hasLocationPermission) {
            initializeLocationOverlay()
            startLocationUpdates()
        }
    }



    /**
     * Initialize overlay system with Redux store after it's connected
     */
    private fun initializeOverlaySystemWithRedux(store: com.madanala.tern.redux.MapStore) {


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
        Log.i(TAG, "[$this] MapViewModel Cleared - shutting down overlay coordinator")

        pendingReduxUpdate?.let { mainHandler.removeCallbacks(it) }

        // [LIFECYCLE BOND] Explicitly shutdown coordinator to kill all "ghost" coroutines
        overlayCoordinator.shutdown()

        // Important to release map resources
        mapView.onDetach()
        super.onCleared()
    }
}
