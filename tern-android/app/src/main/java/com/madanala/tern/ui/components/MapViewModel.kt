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
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import com.madanala.tern.utils.PGSpotsCache
import kotlinx.coroutines.CancellationException
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
private const val MAP_MOVE_DEBOUNCE_MS = 500L // Debounce map movement checks by 0.5 seconds (optimized for better responsiveness)
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

    // PG Spots management
    private val pgSpotsCache = PGSpotsCache(application)
    private var currentPGSpotsCountryCode: String? = null
    private var lastPGSpotsCheckLocation: GeoPoint? = null
    private var pgSpotsLoadingJob: Job? = null
    private var showPGSpotsEnabled = true // Default to enabled

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingAirspaceCheck: Runnable? = null
    private var pendingPGSpotsCheck: Runnable? = null
    private var pendingCleanup: Runnable? = null

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

                        // Load initial PG spots data when location is ready
                        viewModelScope.launch {
                            loadPGSpotsForCurrentLocation(
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
        clearPGSpotsOverlays()
    }

    /**
     * Clear all PG spots overlays from the map
     */
    fun clearPGSpotsOverlays() {
        // Remove PG spots markers (they have overlayType = "pgspot")
        mapView.overlays.removeAll { overlay ->
            overlay is org.osmdroid.views.overlay.Marker &&
            (overlay as? org.osmdroid.views.overlay.Marker)?.relatedObject is OverlayFeature &&
            ((overlay as? org.osmdroid.views.overlay.Marker)?.relatedObject as? OverlayFeature)?.overlayType == "pgspot"
        }
        mapView.invalidate()
    }







    /**
     * Setup map listeners for automatic airspace and PG spots loading
     */
    private fun setupMapListeners() {
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                checkAirspaceReloadNeeded()
                checkPGSpotsReloadNeeded()
                scheduleCleanupCheck()
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                checkAirspaceReloadNeeded()
                checkPGSpotsReloadNeeded()
                scheduleCleanupCheck()
                return true
            }
        })
    }

    /**
     * Schedule cleanup of out-of-view overlays (debounced, runs less frequently)
     */
    private fun scheduleCleanupCheck() {
        pendingCleanup?.let { mainHandler.removeCallbacks(it) }
        pendingCleanup = Runnable {
            performCleanup()
        }
        mainHandler.postDelayed(pendingCleanup!!, 3000L) // Cleanup every 3 seconds instead of constantly
    }

    /**
     * Perform cleanup of overlays outside viewport and radius
     */
    private fun performCleanup() {
        GeoJsonUtils.removeAirspacesOutsideViewport(mapView)
        val center = mapView.mapCenter as GeoPoint
        GeoJsonUtils.removeAirspacesOutsideRadius(mapView, center, AIRSPACE_FILTER_RADIUS_MILES)
        mapView.invalidate()
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
                            GeoJsonUtils.addAirspaceFeaturesToMap(mapView, nearbyFeatures)
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
                GeoJsonUtils.addAirspaceFeaturesToMap(mapView, nearbyFeatures)

                // Update current country and last location
                currentCountryCode = countryCode
                lastAirspaceCheckLocation = center

            } else {
                Log.w(TAG, "No airspace data available for $countryCode")
            }

        } catch (e: CancellationException) {
            // Expected behavior when cancelling old requests due to debounce - don't log as error
            Log.d(TAG, "Airspace loading cancelled due to user interaction")
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

    /**
     * Check if PG spots data needs to be reloaded based on map center (debounced)
     */
    private fun checkPGSpotsReloadNeeded() {
        // Cancel any pending check
        pendingPGSpotsCheck?.let { mainHandler.removeCallbacks(it) }

        // Schedule a new check after debounce delay
        pendingPGSpotsCheck = Runnable {
            performPGSpotsCheck()
        }
        mainHandler.postDelayed(pendingPGSpotsCheck!!, MAP_MOVE_DEBOUNCE_MS)
    }

    /**
     * Perform the actual PG spots check after debounce delay
     */
    private fun performPGSpotsCheck() {
        val center = mapView.mapCenter as GeoPoint
        val context = getApplication<Application>().applicationContext

        // Check if we've moved far enough to warrant a reload (use same distance as airspaces)
        lastPGSpotsCheckLocation?.let { lastLocation ->
            val distance = lastLocation.distanceToAsDouble(center)
            val distanceKm = distance / 1000.0

            if (distanceKm < AIRSPACE_CHECK_DISTANCE_KM) {
                return // Not moved far enough
            }
        }

        // Cancel any existing loading job
        pgSpotsLoadingJob?.cancel()

        // Start new loading job
        pgSpotsLoadingJob = viewModelScope.launch {
            loadPGSpotsForCurrentLocation(context, center)
        }
    }

    /**
     * Load PG spots data for the current map center location
     */
    private suspend fun loadPGSpotsForCurrentLocation(context: Context, center: GeoPoint) {
        try {
            // Check if PG spots are enabled in settings
            if (!showPGSpotsEnabled) {
                return
            }

            // Get country code for current location
            val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, center)

            if (countryCode == null) {
                return
            }

            // Check if we already have this country's data loaded
            if (countryCode == currentPGSpotsCountryCode) {
                // Even if we have the country's data, we need to check if we've moved far enough
                // to warrant re-filtering the PG spots around the new center
                lastPGSpotsCheckLocation?.let { lastLocation ->
                    val distance = lastLocation.distanceToAsDouble(center)
                    val distanceKm = distance / 1000.0

                    // If we've moved more than 50 miles (~80km), re-filter the PG spots
                    if (distanceKm > 80.0) {
                        // Clear existing PG spots and re-add with new filtering
                        clearPGSpotsOverlays()

                        // Query nearby PG spots
                        val nearbyFeatures = pgSpotsCache.queryNearbyFeatures(countryCode, center, AIRSPACE_FILTER_RADIUS_MILES)
                        if (nearbyFeatures.isNotEmpty()) {
                            addPGSpotsToMap(mapView, nearbyFeatures)
                        }

                        // Update the last check location
                        lastPGSpotsCheckLocation = center
                        return
                    } else {
                        return
                    }
                }

                // If we don't have a last location, something went wrong, so reload
            }

            // Try to load from cache first (PG spots cached for 7 days)
            var features = pgSpotsCache.getCachedFeatures(countryCode)

            if (features == null) {
                // Download from ParaglidingEarth API
                features = pgSpotsCache.downloadAndCacheData(countryCode)
            }

            if (features != null && features.isNotEmpty()) {
                // Clear existing PG spots overlays
                clearPGSpotsOverlays()

                // Query nearby features and add to map
                val nearbyFeatures = pgSpotsCache.queryNearbyFeatures(countryCode, center, AIRSPACE_FILTER_RADIUS_MILES)
                addPGSpotsToMap(mapView, nearbyFeatures)

                // Update current country and last location
                currentPGSpotsCountryCode = countryCode
                lastPGSpotsCheckLocation = center

            } else {
                Log.w(TAG, "No PG spots data available for $countryCode")
            }

        } catch (e: CancellationException) {
            // Expected behavior when cancelling old requests due to debounce - don't log as error
            Log.d(TAG, "PG spots loading cancelled due to user interaction")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading PG spots data", e)
        }
    }

    /**
     * Add PG spots to the map as markers
     * @param mapView The MapView to add the markers to
     * @param features List of PG spot features to add
     */
    private fun addPGSpotsToMap(mapView: MapView, features: List<OverlayFeature>) {
        val context = getApplication<Application>().applicationContext

        features.forEach { feature ->
            try {
                // Create marker for PG spot
                val marker = org.osmdroid.views.overlay.Marker(mapView)

                // Set position
                marker.position = feature.centroid

                // Try to get spot name from properties
                @Suppress("UNCHECKED_CAST")
                val properties = feature.feature["properties"] as? Map<String, Any>
                val spotName = properties?.get("name") as? String ?:
                              properties?.get("siteName") as? String ?:
                              properties?.get("loc_name") as? String ?: "PG Spot"

                marker.title = spotName

                // Add more details to snippet if available
                val snippetParts = mutableListOf<String>()

                // Try to get location/elevation info
                properties?.get("elevation")?.let { elevation ->
                    snippetParts.add("$elevation ft")
                }
                properties?.get("country")?.let { country ->
                    snippetParts.add(country as String)
                }

                marker.snippet = if (snippetParts.isNotEmpty()) snippetParts.joinToString(" • ") else "Paragliding Location"

                // Set custom icon (use app launcher icon like iOS version)
                try {
                    val customIcon = android.graphics.BitmapFactory.decodeResource(context.resources, android.R.mipmap.sym_def_app_icon)
                    if (customIcon != null) {
                        marker.icon = android.graphics.drawable.BitmapDrawable(context.resources, customIcon)
                    }
                } catch (_: Exception) {
                    try {
                        val launcherIcon = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                        if (launcherIcon != null) {
                            marker.icon = android.graphics.drawable.BitmapDrawable(context.resources, launcherIcon)
                        }
                    } catch (_: Exception) {
                        // Use default marker icon
                    }
                }

                // Store feature reference for identification
                marker.relatedObject = feature

                // Set anchor point for the icon
                marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)

                // Add click listener to show info
                marker.setOnMarkerClickListener { clickedMarker, mapView ->
                    clickedMarker.showInfoWindow()
                    true // Consume the click
                }

                // Add marker to map
                mapView.overlays.add(marker)

            } catch (e: Exception) {
                Log.w(TAG, "Error creating marker for PG spot", e)
            }
        }

        mapView.invalidate()
    }

    /**
     * Manually trigger PG spots reload for current map center
     */
    fun reloadPGSpotsForCurrentLocation() {
        val center = mapView.mapCenter as GeoPoint
        val context = getApplication<Application>().applicationContext

        // Clear existing cache to force re-download
        pgSpotsCache.clearCache()
        currentPGSpotsCountryCode = null
        lastPGSpotsCheckLocation = null // Force reload

        pgSpotsLoadingJob?.cancel()
        pgSpotsLoadingJob = viewModelScope.launch {
            loadPGSpotsForCurrentLocation(context, center)
        }
    }

    /**
     * Update PG spots enabled state
     * @param enabled Whether PG spots should be displayed
     */
    fun setPGSpotsEnabled(enabled: Boolean) {
        showPGSpotsEnabled = enabled
        if (!enabled) {
            // Clear existing PG spots overlays if disabled
            clearPGSpotsOverlays()
            currentPGSpotsCountryCode = null
        } else {
            // Reload PG spots for current location if enabled
            reloadPGSpotsForCurrentLocation()
        }
    }

    override fun onCleared() {
        // Cancel any ongoing loading jobs
        airspaceLoadingJob?.cancel()
        pgSpotsLoadingJob?.cancel()

        // Cancel any pending map movement checks
        pendingAirspaceCheck?.let { mainHandler.removeCallbacks(it) }
        pendingPGSpotsCheck?.let { mainHandler.removeCallbacks(it) }
        pendingCleanup?.let { mainHandler.removeCallbacks(it) }

        // Important to release map resources
        mapView.onDetach()
        super.onCleared()
    }
}
