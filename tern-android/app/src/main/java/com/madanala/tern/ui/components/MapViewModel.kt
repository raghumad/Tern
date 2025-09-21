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
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.madanala.tern.R
import com.madanala.tern.ui.screens.MAP_VIEW_SATELLITE
import com.madanala.tern.ui.screens.MAP_VIEW_TERRAIN
import com.madanala.tern.utils.AirspaceCache
import com.madanala.tern.utils.CountryUtils
import com.madanala.tern.utils.GeoJsonUtils
import kotlinx.coroutines.Job
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

private const val USER_LOCATION_ZOOM = 7.0
private const val AIRSPACE_CHECK_DISTANCE_KM = 5.0 // Minimum distance to trigger airspace reload (further reduced)
private const val AIRSPACE_FILTER_RADIUS_MILES = 300.0 // Configurable radius for airspace filtering
private const val MAP_MOVE_DEBOUNCE_MS = 1000L // Debounce map movement checks by 1 second
private const val TAG = "MapViewModel"

class MapViewModel(application: Application) : AndroidViewModel(application) {
    // This is a deliberate architectural choice.
    // The MapView is a complex, stateful UI component. To prevent it from being destroyed and
    // recreated on configuration changes (like screen rotation), we hold its instance here in the
    // ViewModel. This is considered a "leak" by the linter because the ViewModel should not
    // traditionally hold a reference to a View.
    //
    // However, this is safe in our case for two reasons:
    // 1. We are using the Application context, not an Activity context. The Application context
    //    is a singleton that lives for the entire app lifecycle, so we are not leaking an Activity.
    // 2. The alternative (saving and restoring all map state) is far more complex and could
    //    lead to UI stutters on rotation. This approach provides a much smoother user experience.
    @SuppressLint("StaticFieldLeak")
    val mapView: MapView

    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var initialZoomDone = false
    var mapStyle by mutableStateOf(MAP_VIEW_TERRAIN)
        private set

    private val _isLocationReady = MutableStateFlow(false)
    val isLocationReady = _isLocationReady.asStateFlow()

    // Airspace management
    private val airspaceCache = AirspaceCache(application)
    private var currentCountryCode: String? = null
    private var lastAirspaceCheckLocation: GeoPoint? = null
    private var airspaceLoadingJob: Job? = null
    private var showAirspacesEnabled = true // Default to enabled
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingAirspaceCheck: Runnable? = null

    init {
        mapView = MapView(application).apply {
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            setTileSource(TileSourceFactory.OpenTopo) // A sensible default
        }
        addMapOverlays()
        setupMapListeners()

        // Dynamic airspace loading will be handled by location-based triggers
    }

    fun updateMapStyle(style: Int) {
        mapStyle = style
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

            myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
                isDrawAccuracyEnabled = true

                // This is the key: Set the center INSTANTLY on first fix
                runOnFirstFix {
                    if (!initialZoomDone) {
                        mapView.controller.setZoom(USER_LOCATION_ZOOM)
                        mapView.controller.setCenter(myLocation)
                        initialZoomDone = true
                        _isLocationReady.value = true

                        // Load initial airspace data when location is ready
                        viewModelScope.launch {
                            loadAirspaceForCurrentLocation(
                                getApplication<Application>().applicationContext,
                                myLocation
                            )
                        }
                    }
                }

                try {
                    val customIcon: Drawable? = AppCompatResources.getDrawable(context, R.drawable.paragliding_24)
                    customIcon?.let {
                        val bitmap = it.toBitmap()
                        setPersonIcon(bitmap)
                        setDirectionIcon(bitmap)
                    }
                } catch (_: Exception) {
                    // Log error
                }
            }
            add(myLocationOverlay)
        }
    }

    fun startLocationUpdates() {
        myLocationOverlay?.enableMyLocation()
    }



    /**
     * Clear all GeoJSON overlays from the map
     */
    fun clearGeoJsonOverlays() {
        GeoJsonUtils.clearGeoJsonOverlays(mapView)
    }







    /**
     * Setup map listeners for automatic airspace loading
     */
    private fun setupMapListeners() {
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                checkAirspaceReloadNeeded()
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                checkAirspaceReloadNeeded()
                return true
            }
        })
    }

    /**
     * Check if airspace data needs to be reloaded based on map center (debounced)
     */
    private fun checkAirspaceReloadNeeded() {
        // Cancel any pending check
        pendingAirspaceCheck?.let { mainHandler.removeCallbacks(it) }

        // Schedule a new check after debounce delay
        pendingAirspaceCheck = Runnable {
            performAirspaceCheck()
            // Also clean up airspaces outside viewport and radius for better performance
            GeoJsonUtils.removeAirspacesOutsideViewport(mapView)
            // Remove airspaces outside the 300-mile radius from current center
            val center = mapView.mapCenter as GeoPoint
            GeoJsonUtils.removeAirspacesOutsideRadius(mapView, center, AIRSPACE_FILTER_RADIUS_MILES)
        }
        mainHandler.postDelayed(pendingAirspaceCheck!!, MAP_MOVE_DEBOUNCE_MS)
    }

    /**
     * Perform the actual airspace check after debounce delay
     */
    private fun performAirspaceCheck() {
        val center = mapView.mapCenter as GeoPoint
        val context = getApplication<Application>().applicationContext

        // Check if we've moved far enough to warrant a reload
        lastAirspaceCheckLocation?.let { lastLocation ->
            val distance = lastLocation.distanceToAsDouble(center)
            val distanceKm = distance / 1000.0

            if (distanceKm < AIRSPACE_CHECK_DISTANCE_KM) {
                return // Not moved far enough
            }
        }

        // Cancel any existing loading job
        airspaceLoadingJob?.cancel()

        // Start new loading job
        airspaceLoadingJob = viewModelScope.launch {
            loadAirspaceForCurrentLocation(context, center)
        }
    }

    /**
     * Load airspace data for the current map center location
     */
    private suspend fun loadAirspaceForCurrentLocation(context: Context, center: GeoPoint) {
        try {
            // Check if airspaces are enabled in settings
            if (!showAirspacesEnabled) {
                return
            }

            // Get country code for current location
            val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, center)

            if (countryCode == null) {
                return
            }

            // Check if we already have this country's data loaded
            if (countryCode == currentCountryCode) {
                // Even if we have the country's data, we need to check if we've moved far enough
                // to warrant re-filtering the airspaces around the new center
                lastAirspaceCheckLocation?.let { lastLocation ->
                    val distance = lastLocation.distanceToAsDouble(center)
                    val distanceKm = distance / 1000.0

                    // If we've moved more than 50 miles (~80km), re-filter the airspaces
                    // This ensures significant location changes trigger re-filtering
                    if (distanceKm > 80.0) {
                        // Clear existing overlays and re-add with new filtering
                        clearGeoJsonOverlays()

                        // Query nearby features using Hilbert index
                        val nearbyFeatures = airspaceCache.queryNearbyFeatures(countryCode, center, AIRSPACE_FILTER_RADIUS_MILES)
                        if (nearbyFeatures.isNotEmpty()) {
                            val polygons = GeoJsonUtils.addAirspaceFeaturesToMap(mapView, nearbyFeatures)
                        }

                        // Update the last check location
                        lastAirspaceCheckLocation = center
                        return
                    } else {
                        return
                    }
                }

                // If we don't have a last location, something went wrong, so reload
            }

            // Try to load from cache first (airspaces cached for 30 days)
            var features = airspaceCache.getCachedFeatures(countryCode)

            if (features == null) {
                // Download from OpenAIP
                val url = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/${countryCode}_asp.ndgeojson"
                val ndGeoJsonString = GeoJsonUtils.downloadGeoJson(url)

                if (ndGeoJsonString != null) {
                    // Cache the downloaded data
                    airspaceCache.cacheData(countryCode, ndGeoJsonString)
                    features = airspaceCache.getCachedFeatures(countryCode)
                } else {
                    Log.w(TAG, "Failed to download airspace data for $countryCode")
                }
            }

            if (features != null) {
                // Clear existing airspace overlays
                clearGeoJsonOverlays()

                // Query nearby features and add to map
                val nearbyFeatures = airspaceCache.queryNearbyFeatures(countryCode, center, AIRSPACE_FILTER_RADIUS_MILES)
                val polygons = GeoJsonUtils.addAirspaceFeaturesToMap(mapView, nearbyFeatures)

                // Update current country and last location
                currentCountryCode = countryCode
                lastAirspaceCheckLocation = center

            } else {
                Log.w(TAG, "No airspace data available for $countryCode")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading airspace data", e)
        }
    }

    /**
     * Manually trigger airspace reload for current map center
     */
    fun reloadAirspaceForCurrentLocation() {
        val center = mapView.mapCenter as GeoPoint
        val context = getApplication<Application>().applicationContext

        // Clear existing cache to force re-download with new filtering
        airspaceCache.clearCache()
        currentCountryCode = null
        lastAirspaceCheckLocation = null // Force reload

        airspaceLoadingJob?.cancel()
        airspaceLoadingJob = viewModelScope.launch {
            loadAirspaceForCurrentLocation(context, center)
        }
    }



    /**
     * Update airspace enabled state
     * @param enabled Whether airspaces should be displayed
     */
    fun setAirspacesEnabled(enabled: Boolean) {
        showAirspacesEnabled = enabled
        if (!enabled) {
            // Clear existing airspace overlays if disabled
            clearGeoJsonOverlays()
            currentCountryCode = null
        } else {
            // Reload airspace for current location if enabled
            reloadAirspaceForCurrentLocation()
        }
    }

    override fun onCleared() {
        // Cancel any ongoing loading job
        airspaceLoadingJob?.cancel()

        // Cancel any pending airspace check
        pendingAirspaceCheck?.let { mainHandler.removeCallbacks(it) }

        // Important to release map resources
        mapView.onDetach()
        super.onCleared()
    }
}
