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
private const val AIRSPACE_MAJOR_MOVE_KM = 200.0 // Major move threshold - clear and reload everything
private const val AIRSPACE_FILTER_RADIUS_MILES = 300.0 // Configurable radius for airspace filtering
private const val MAP_MOVE_DEBOUNCE_MS = 500L // Debounce map movement checks by 0.5 seconds (optimized for better responsiveness)
private const val TAG = "MapViewModel"

class MapViewModel(application: Application) : AndroidViewModel(application) {
    // Callbacks for Redux updates (Phase 1 Redux migration)
    var rotationCallback: ((Float) -> Unit)? = null
    var locationReadyCallback: ((Boolean) -> Unit)? = null

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

    private val _isLocationReady = MutableStateFlow(false)
    val isLocationReady = _isLocationReady.asStateFlow()

    private val _mapRotation = MutableStateFlow(0f)
    val mapRotation = _mapRotation.asStateFlow()

    // Airspace management
    private val airspaceCache = AirspaceCache(application)
    private var currentCountryCode: String? = null
    private var lastAirspaceCheckLocation: GeoPoint? = null
    private var airspaceLoadingJob: Job? = null
    private var showAirspacesEnabled = true // Default to enabled
    private val currentlyRenderedAirspaceIds = mutableSetOf<String>() // Track rendered airspace IDs to prevent duplicates

    // PG Spots management - Commented out for Phase 1
    // private val pgSpotsCache = PGSpotsCache(application)
    private var pgSpotsCache: PGSpotsCache? = null
    private var currentPGSpotsCountryCode: String? = null
    private var lastPGSpotsCheckLocation: GeoPoint? = null
    private var pgSpotsLoadingJob: Job? = null
    private var showPGSpotsEnabled = false // Disabled for Phase 1

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
                        locationReadyCallback?.invoke(true) // Redux migration

                        // Load initial airspace data when location is ready
                        viewModelScope.launch {
                            loadAirspaceForCurrentLocation(
                                getApplication<Application>().applicationContext,
                                myLocation
                            )
                        }

                        // Load initial PG spots data when location is ready - Disabled for Phase 1
                        /*
                        viewModelScope.launch {
                            loadPGSpotsForCurrentLocation(
                                getApplication<Application>().applicationContext,
                                myLocation
                            )
                        }
                        */
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
        currentlyRenderedAirspaceIds.clear() // Clear tracking when all airspaces are removed
    }

    /**
     * Force reload airspaces for current map center
     * Used when viewport has changed significantly and cache needs refresh
     */
    fun forceAirspaceReload() {
        Log.d(TAG, "Forcing airspace reload for current location")
        val center = mapView.mapCenter as GeoPoint
        val context = getApplication<Application>().applicationContext

        airspaceLoadingJob?.cancel()
        airspaceLoadingJob = viewModelScope.launch {
            // Force a clean reload by temporarily clearing country
            val originalCountry = currentCountryCode
            currentCountryCode = null // Force fresh load
            loadAirspaceForCurrentLocation(context, center)
            // Note: country will be set again in loadAirspaceForCurrentLocation
        }
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
                val rotation = mapView.mapOrientation
                _mapRotation.value = rotation
                rotationCallback?.invoke(rotation) // Redux migration
                checkAirspaceReloadNeeded()
                checkPGSpotsReloadNeeded()
                scheduleCleanupCheck()
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                val rotation = mapView.mapOrientation
                _mapRotation.value = rotation
                rotationCallback?.invoke(rotation) // Redux migration
                checkAirspaceReloadNeeded()
                checkPGSpotsReloadNeeded()
                scheduleCleanupCheck()
                return true
            }
        })
    }

    /**
     * Schedule out-of-view cleanup (viewport-as-truth principle)
     * Airspaces are managed based on actual viewport visibility, not artificial radius limits
     */
    private fun scheduleCleanupCheck() {
        pendingCleanup?.let { mainHandler.removeCallbacks(it) }
        pendingCleanup = Runnable {
            performCleanup()
        }
        // Configurable delay - viewport cleanup triggers after map movement settles
        // This ensures airspaces are truly visible before cleanup happens
        mainHandler.postDelayed(pendingCleanup!!, VIEWPORT_CLEANUP_DELAY_MS)
    }

    companion object {
        // Configurable: Time to wait after map movement before viewport cleanup
        // Longer delays = more accurate but less responsive
        // Shorter delays = more responsive but may trigger cleanup during movement
        private const val VIEWPORT_CLEANUP_DELAY_MS = 1000L // 1 second delay - adjustable

        // Configurable: Maximum airspaces to keep rendered for resource management
        const val MAX_VISIBLE_AIRSPACES = 100 // Adjustable based on device performance
    }

    /**
     * Perform cleanup of overlays outside viewport
     * Note: We don't do radius-based cleanup anymore since airspaces should stay
     * loaded until scrolled out of view, not removed at artificial distance limits
     */
    private fun performCleanup() {
        GeoJsonUtils.removeAirspacesOutsideViewport(mapView)
        // Don't do radius-based cleanup - airspaces stay loaded until actually out of view
        mapView.invalidate()
        Log.d(TAG, "Performed viewport cleanup (removed out-of-view airspaces)")
    }

    /**
     * Perform viewport-only cleanup without radius filtering
     * Used for small map movements to preserve on-screen airspaces
     */
    private fun performViewportCleanupOnly() {
        // Only remove airspaces that are scrolled out of the current viewport
        // Keep airspaces that are still visible on screen
        GeoJsonUtils.removeAirspacesOutsideViewport(mapView)
        // Don't do radius-based cleanup - let airspaces stay until actually out of view
        mapView.invalidate()
        Log.d(TAG, "Performed viewport-only cleanup (small movement)")
    }

    /**
     * Generate a unique identifier for an airspace feature
     */
    private fun generateAirspaceId(feature: OverlayFeature): String {
        // Try to use airspace name first
        val properties = feature.feature["properties"] as? Map<*, *>
        val name = properties?.get("name") as? String ?: properties?.get("Name") as? String

        if (!name.isNullOrBlank()) {
            return "airspace_$name"
        }

        // Fallback: coordinate-based ID
        val geometry = feature.feature["geometry"] as? Map<*, *>
        val coordinates = geometry?.get("coordinates") as? List<*>
        val outerRing = coordinates?.get(0) as? List<List<Double>>

        return if (outerRing != null && outerRing.isNotEmpty()) {
            val firstCoord = outerRing[0]
            String.format("airspace_coord_%.6f_%.6f", firstCoord[1], firstCoord[0])
        } else {
            // Fallback to hash-based ID for edge cases
            "airspace_hash_${feature.hashCode()}"
        }
    }

    /**
     * Add airspace features to map, filtering out duplicates
     */
    private fun addAirspaceFeaturesDeduped(mapView: MapView, features: List<OverlayFeature>) {
        val newFeatures = features.filter { feature ->
            val airspaceId = generateAirspaceId(feature)
            if (currentlyRenderedAirspaceIds.contains(airspaceId)) {
                false // Already rendered, skip
            } else {
                currentlyRenderedAirspaceIds.add(airspaceId)
                true // Not rendered yet, add it
            }
        }

        if (newFeatures.isNotEmpty()) {
            Log.d(TAG, "Adding ${newFeatures.size} new airspaces (${features.size - newFeatures.size} duplicates skipped)")
            GeoJsonUtils.addAirspaceFeaturesToMap(mapView, newFeatures)
        } else {
            Log.d(TAG, "All ${features.size} airspaces already rendered")
        }
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

        // Check distance moved
        lastAirspaceCheckLocation?.let { lastLocation ->
            val distance = lastLocation.distanceToAsDouble(center)
            val distanceKm = distance / 1000.0

            if (distanceKm < AIRSPACE_CHECK_DISTANCE_KM) {
                return // Not moved far enough (<5km)
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

            // Check if we've moved very far (>200km) - clear and reload everything
            val isMajorMove = lastAirspaceCheckLocation?.let { lastLocation ->
                val distance = lastLocation.distanceToAsDouble(center)
                val distanceKm = distance / 1000.0
                distanceKm > AIRSPACE_MAJOR_MOVE_KM
            } ?: false

            if (isMajorMove) {
                Log.d(TAG, "Major move detected (>200km) - clearing all airspaces for fresh start")
                clearGeoJsonOverlays()
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
                // Query nearby features - will be handled incrementally if moving within same country
                val nearbyFeatures = airspaceCache.queryNearbyFeatures(countryCode, center, AIRSPACE_FILTER_RADIUS_MILES)

                // For same country moves, use incremental updates instead of clearing everything
                if (countryCode == currentCountryCode && !nearbyFeatures.isEmpty()) {
                    // Add new airspaces incrementally - don't clear existing ones
                    // The viewport cleanup will handle removing out-of-view airspaces over time
                    Log.d(TAG, "Same country move - adding ${nearbyFeatures.size} airspaces incrementally")
                    val existingCount = mapView.overlays.count { overlay ->
                        overlay is org.osmdroid.views.overlay.Polygon
                    }
                    addAirspaceFeaturesDeduped(mapView, nearbyFeatures)
                    Log.d(TAG, "Added airspaces: $existingCount → ${mapView.overlays.count { overlay ->
                        overlay is org.osmdroid.views.overlay.Polygon
                    }} total (${nearbyFeatures.size} features processed)")
                } else {
                    // Country change or initial load - clear and load fresh
                    clearGeoJsonOverlays()
                    GeoJsonUtils.addAirspaceFeaturesToMap(mapView, nearbyFeatures)

                    // After adding, update tracking for the new airspaces
                    nearbyFeatures.forEach { feature ->
                        val airspaceId = generateAirspaceId(feature)
                        currentlyRenderedAirspaceIds.add(airspaceId)
                    }
                }

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
                        val nearbyFeatures = pgSpotsCache?.queryNearbyFeatures(countryCode, center, AIRSPACE_FILTER_RADIUS_MILES) ?: emptyList()
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

            // Try to load from cache first (PG spots cached for 7 days) - Disabled for Phase 1
            var features: List<OverlayFeature>? = null

            if (features == null) {
                // Download from ParaglidingEarth API - Placeholder for Phase 1
                features = emptyList()
            }

            if (features.isNotEmpty()) {
                // Clear existing PG spots overlays
                clearPGSpotsOverlays()

                // Query nearby features and add to map - Placeholder for Phase 1
                val nearbyFeatures: List<OverlayFeature> = emptyList()
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

                // Set custom icon (use app launcher icon like iOS version, scaled to 1/4 size)
                try {
                    val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                    drawable?.let {
                        val originalBitmap = it.toBitmap()
                        // Scale to 1/4 size for better visual balance
                        val scaledWidth = originalBitmap.width / 4
                        val scaledHeight = originalBitmap.height / 4
                        val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(
                            originalBitmap,
                            scaledWidth,
                            scaledHeight,
                            true // Use bilinear filtering
                        )
                        marker.icon = android.graphics.drawable.BitmapDrawable(context.resources, scaledBitmap)
                    }
                } catch (_: Exception) {
                    // Use default marker icon if launcher icon fails
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

        // Clear existing cache to force re-download - Phase 1 placeholder
        pgSpotsCache?.clearCache()
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
