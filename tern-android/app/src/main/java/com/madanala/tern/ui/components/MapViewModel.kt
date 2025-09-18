package com.madanala.tern.ui.components

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.drawable.Drawable
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
private const val AIRSPACE_CHECK_DISTANCE_KM = 50.0 // Minimum distance to trigger airspace reload
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

    init {
        mapView = MapView(application).apply {
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            setTileSource(TileSourceFactory.OpenTopo) // A sensible default
        }
        addMapOverlays()
        setupMapListeners()

        // Load initial airspace data for default map center
        viewModelScope.launch {
            val defaultCenter = GeoPoint(37.7749, -122.4194) // San Francisco as default
            loadAirspaceForCurrentLocation(application.applicationContext, defaultCenter)
        }

        // Also trigger airspace loading when map is first displayed
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000) // Small delay to ensure map is ready
            forceLoadAirspaceForCurrentLocation()
        }
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
     * Load and display GeoJSON data on the map
     * @param geoJsonString The GeoJSON data as a string
     * @return Number of features loaded, or -1 if parsing failed
     */
    fun loadGeoJson(geoJsonString: String): Int {
        return try {
            // Clear existing GeoJSON overlays first
            clearGeoJsonOverlays()

            // Add new GeoJSON data
            val overlays = GeoJsonUtils.addGeoJsonToMap(mapView, geoJsonString)
            overlays.size
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Clear all GeoJSON overlays from the map
     */
    fun clearGeoJsonOverlays() {
        GeoJsonUtils.clearGeoJsonOverlays(mapView)
    }

    /**
     * Load airspace data from OpenAIP URL or sample data
     * @param countryCode Two-letter country code (e.g., "us", "de", "fr") or "sample" for test data
     * @return Number of airspace polygons loaded, or -1 if failed
     */
    suspend fun loadAirspaceData(countryCode: String): Int {
        return try {
            val ndGeoJsonString = if (countryCode == "sample") {
                // Load sample data from assets
                val assetManager = getApplication<Application>().assets
                val inputStream = assetManager.open("sample_airspace.ndgeojson")
                inputStream.bufferedReader().use { it.readText() }
            } else {
                // Download from OpenAIP
                val url = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/${countryCode}_asp.ndgeojson"
                GeoJsonUtils.downloadGeoJson(url)
            }

            // Clear existing airspace overlays
            clearGeoJsonOverlays()

            // Add new airspace data
            val polygons = GeoJsonUtils.addAirspaceToMap(mapView, ndGeoJsonString)
            polygons.size
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Load airport data from OpenAIP URL
     * @param countryCode Two-letter country code (e.g., "us", "de", "fr")
     * @return Number of airport features loaded, or -1 if failed
     */
    suspend fun loadAirportData(countryCode: String): Int {
        return try {
            val url = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/${countryCode}_apt.ndgeojson"
            val ndGeoJsonString = GeoJsonUtils.downloadGeoJson(url)

            // Add airport data (these will be points)
            val overlays = GeoJsonUtils.addGeoJsonToMap(mapView, ndGeoJsonString)
            overlays.size
        } catch (e: Exception) {
            -1
        }
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
     * Check if airspace data needs to be reloaded based on map center
     */
    private fun checkAirspaceReloadNeeded() {
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

        // Update last check location
        lastAirspaceCheckLocation = center

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
                Log.d(TAG, "Airspaces are disabled in settings")
                return
            }

            // Get country code for current location
            val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, center)

            if (countryCode == null) {
                Log.d(TAG, "Could not determine country code for location: ${center.latitude}, ${center.longitude}")
                return
            }

            // Check if we already have this country's data loaded
            if (countryCode == currentCountryCode) {
                Log.d(TAG, "Already have airspace data for $countryCode")
                return
            }

            // Try to load from cache first
            var ndGeoJsonString = airspaceCache.getCachedData(countryCode)

            if (ndGeoJsonString == null) {
                // Download from OpenAIP
                Log.d(TAG, "Downloading airspace data for $countryCode")
                val url = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/${countryCode}_asp.ndgeojson"
                ndGeoJsonString = GeoJsonUtils.downloadGeoJson(url)

                if (ndGeoJsonString != null) {
                    // Cache the downloaded data
                    airspaceCache.cacheData(countryCode, ndGeoJsonString)
                }
            } else {
                Log.d(TAG, "Loading airspace data for $countryCode from cache")
            }

            if (ndGeoJsonString != null) {
                // Clear existing airspace overlays
                clearGeoJsonOverlays()

                // Add new airspace data
                val polygons = GeoJsonUtils.addAirspaceToMap(mapView, ndGeoJsonString)

                // Update current country
                currentCountryCode = countryCode

                Log.d(TAG, "Loaded ${polygons.size} airspace polygons for $countryCode")
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

        lastAirspaceCheckLocation = null // Force reload

        airspaceLoadingJob?.cancel()
        airspaceLoadingJob = viewModelScope.launch {
            loadAirspaceForCurrentLocation(context, center)
        }
    }

    /**
     * Force load airspace for current map center (ignores distance check)
     */
    fun forceLoadAirspaceForCurrentLocation() {
        val center = mapView.mapCenter as GeoPoint
        val context = getApplication<Application>().applicationContext

        airspaceLoadingJob?.cancel()
        airspaceLoadingJob = viewModelScope.launch {
            loadAirspaceForCurrentLocation(context, center)
        }
    }

    /**
     * Clear airspace cache
     */
    fun clearAirspaceCache() {
        airspaceCache.clearCache()
        currentCountryCode = null
        clearGeoJsonOverlays()
        Log.d(TAG, "Airspace cache cleared")
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
            Log.d(TAG, "Airspaces disabled, cleared overlays")
        } else {
            // Reload airspace for current location if enabled
            reloadAirspaceForCurrentLocation()
            Log.d(TAG, "Airspaces enabled, reloading for current location")
        }
    }

    /**
     * Get airspace cache statistics
     */
    fun getAirspaceCacheStats(): Map<String, Any> {
        return airspaceCache.getCacheStats()
    }

    override fun onCleared() {
        // Cancel any ongoing loading job
        airspaceLoadingJob?.cancel()
        // Important to release map resources
        mapView.onDetach()
        super.onCleared()
    }
}
