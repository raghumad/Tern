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
import com.madanala.tern.ui.screens.MAP_VIEW_SATELLITE
import com.madanala.tern.ui.screens.MAP_VIEW_TERRAIN
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.CountryUtils
import com.madanala.tern.utils.GeoJsonUtils
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
private const val MAP_MOVE_DEBOUNCE_MS = 2000L // Aviation-optimized: 2 second debounce for smooth flight experience
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

    // Redux state integration
    private var reduxState: com.madanala.tern.redux.MapState = com.madanala.tern.redux.MapState()

    // Redux store - can be set after construction for ViewModel compatibility
    private var reduxStore: com.madanala.tern.redux.MapStore? = null

    // Redux store accessor for external components
    val mapStore: com.madanala.tern.redux.MapStore?
        get() = reduxStore

    private val _isLocationReady = MutableStateFlow(false)
    val isLocationReady = _isLocationReady.asStateFlow()

    private val _mapRotation = MutableStateFlow(0f)
    val mapRotation = _mapRotation.asStateFlow()

    // Overlay Coordinator - Our new advanced overlay system with memory limits
    private val overlayCoordinator = OverlayCoordinator()




    private val showPGSpotsEnabled: Boolean
        get() = reduxState.overlayState.pgSpots.enabled

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReduxUpdate: Runnable? = null

    init {
        mapView = MapView(application).apply {
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            setTileSource(TileSourceFactory.OpenTopo) // A sensible default
        }
        addMapOverlays()
        setupMapListeners()
        initializeOverlaySystem()

        // Set up Redux state observation when store is connected via setMapStore()
        // Dynamic airspace loading will be handled by location-based triggers
    }

    fun updateMapStyle(style: Int) {
        mapStyle = style

        // Dispatch Redux action for state management
        val styleString = if (style == MAP_VIEW_SATELLITE) "satellite" else "terrain"
        reduxStore?.dispatch(com.madanala.tern.redux.MapAction.UpdateMapStyle(styleString))

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
                setScaleBarOffset(context.resources.displayMetrics.widthPixels / 2, 10)
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
            Log.d(TAG, "Location overlay already initialized")
            return
        }

        val context = getApplication<Application>().applicationContext

        try {
            Log.d(TAG, "Initializing location overlay with proper permission check")

            myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
                isDrawAccuracyEnabled = true

                // This is the key: Set the center INSTANTLY on first fix
                runOnFirstFix {
                    if (!initialZoomDone) {
                        mapView.controller.setZoom(USER_LOCATION_ZOOM)
                        mapView.controller.setCenter(myLocation)
                        initialZoomDone = true
                        _isLocationReady.value = true

                        // Dispatch Redux actions for location state
                        reduxStore?.dispatch(com.madanala.tern.redux.MapAction.SetLocationReady(true))
                        reduxStore?.dispatch(com.madanala.tern.redux.MapAction.UpdateUserLocation(myLocation))

                        // Notify overlay managers that GPS fix is now available
                        overlayCoordinator.getOverlayManager(com.madanala.tern.redux.OverlayType.AIRSPACE)?.updateGPSFixStatus(true)
                        overlayCoordinator.getOverlayManager(com.madanala.tern.redux.OverlayType.PG_SPOTS)?.updateGPSFixStatus(true)

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
            Log.d(TAG, "Location overlay successfully initialized and added to map")

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
     * Set up Redux state observation for reactive updates
     */
    private fun setupReduxStateObservation() {
        val store = reduxStore ?: return

        // Launch coroutine to collect Redux state changes
        viewModelScope.launch {
            store.state.collect { newState ->
                val oldState = reduxState
                reduxState = newState

                // Handle overlay state changes

                // Handle map style changes
                if (oldState.mapStyle != newState.mapStyle) {
                    val styleInt = if (newState.mapStyle == "satellite") MAP_VIEW_SATELLITE else MAP_VIEW_TERRAIN
                    updateMapStyle(styleInt)
                }

                // Handle permission changes
                if (oldState.hasLocationPermission != newState.hasLocationPermission) {
                    if (newState.hasLocationPermission) {
                        // Initialize location overlay now that permissions are granted
                        initializeLocationOverlay()
                        startLocationUpdates()
                    } else {
                        myLocationOverlay?.disableMyLocation()
                    }
                }
            }
        }
    }

    /**
     * Initialize the new overlay system (Redux store connected later)
     */
    private fun initializeOverlaySystem() {
        Log.d(TAG, "Initializing overlay system - Redux store will be connected later")

        // Initialize overlay coordinator without Redux store (will be connected later)
        overlayCoordinator.initialize(
            mapStore = null, // Will be set when Redux store is connected
            mapView = mapView,
            context = getApplication()
        )

        Log.d(TAG, "Overlay coordinator initialized - waiting for Redux store connection")

        // Overlay managers will be created when Redux store is connected via setMapStore()
    }

    /**
     * Setup map listeners for automatic airspace and PG spots loading
     */
    private fun setupMapListeners() {
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                val rotation = mapView.mapOrientation
                val center = mapView.mapCenter

                _mapRotation.value = rotation

                // Schedule debounced Redux state updates to prevent excessive recompositions
                center?.let { scheduleReduxUpdate(rotation, it as GeoPoint, null) } ?: scheduleReduxUpdate(rotation, null, null)

                // Overlay managers handle their own map-based loading now
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                val rotation = mapView.mapOrientation
                val center = mapView.mapCenter
                val zoom = mapView.zoomLevelDouble

                _mapRotation.value = rotation

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
            reduxStore?.dispatch(com.madanala.tern.redux.MapAction.UpdateMapMovement(
                rotation = rotation,
                center = center,
                zoom = zoom
            ))
        }
        mainHandler.postDelayed(pendingReduxUpdate!!, MAP_MOVE_DEBOUNCE_MS)
    }

    companion object {

    }



    /**
     * Set the Redux store for overlay managers (late initialization for ViewModel compatibility)
     */
    fun setMapStore(store: com.madanala.tern.redux.MapStore?) {
        Log.d(TAG, "Setting Redux store: ${store != null}")
        reduxStore = store

        // Initialize Redux state when store is connected
        reduxState = store?.state?.value ?: com.madanala.tern.redux.MapState()

        // Log current Redux state for debugging
        logReduxStatus()

        // Re-initialize overlay system with Redux store now that it's available
        if (store != null) {
            initializeOverlaySystemWithRedux(store)
        }

        // Set up Redux state observation when store becomes available
        setupReduxStateObservation()

        // If we already have location permission when store is connected, initialize location overlay
        if (reduxState.hasLocationPermission) {
            initializeLocationOverlay()
        }
    }

    /**
     * Initialize overlay system with Redux store after it's connected
     */
    private fun initializeOverlaySystemWithRedux(store: com.madanala.tern.redux.MapStore) {
        Log.d(TAG, "Re-initializing overlay system with Redux store")

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

        Log.d(TAG, "Overlay managers registered with Redux store")
    }

    /**
     * Update Redux state (called when state changes externally)
     */
    fun updateReduxState(newState: com.madanala.tern.redux.MapState) {
        reduxState = newState
    }

    /**
     * Debug method to check Redux integration status
     */
    fun logReduxStatus() {
        Log.d(TAG, "Redux Status - Store connected: ${reduxStore != null}")
        Log.d(TAG, "Redux Status - Location ready: ${reduxState.isLocationReady}")
        Log.d(TAG, "Redux Status - Has location permission: ${reduxState.hasLocationPermission}")
    }

    override fun onCleared() {

        pendingReduxUpdate?.let { mainHandler.removeCallbacks(it) }

        // Important to release map resources
        mapView.onDetach()
        super.onCleared()
    }
}
