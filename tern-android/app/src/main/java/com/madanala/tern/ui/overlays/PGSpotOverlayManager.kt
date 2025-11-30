package com.madanala.tern.ui.overlays

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.madanala.tern.R
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.redux.WeatherActions
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import com.madanala.tern.utils.OpenMeteoWeatherAPI
import com.madanala.tern.utils.PGSpotWeatherCache
import com.madanala.tern.utils.WeatherAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import kotlin.math.roundToInt

/**
 * ADVANCED AVIATION PG SPOTS MANAGER
 * Dynamic weather-aware overlay system with Redux integration
 *
 * Features:
 * ✅ Weather-aware PG spot display (wind gauges + static icons)
 * ✅ Redux weather state integration (MVVM → Redux migration)
 * ✅ Viewport-zone intelligence (inherits from airspace system)
 * ✅ Event-driven weather gathering (visible spots auto-weather-fetch)
 * ✅ Aviation resilience (graceful degradation, flight safety first)
 */
class PGSpotOverlayManager(
    private val applicationContext: Context,
    mapStore: MapStore?
) : BaseOverlayManager(OverlayType.PG_SPOTS, mapStore) {

    companion object {
        private const val TAG = "PGSpotOverlayManager"
        private const val WEATHER_VISIBLE_DISTANCE_KM = 0.5  // Show weather within 500m
        private const val WEATHER_UPDATE_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes between updates
        private const val WEATHER_FETCH_DEBOUNCE_MS = 1000L // Debounce rapid viewport changes
    }

    // Aviation-grade weather infrastructure - specialized caches for each data type
    private val pgSpotCache = CacheManager.pgSpotCache // Use singleton to prevent duplicate downloads
    private val weatherCache = PGSpotWeatherCache(applicationContext)
    private val weatherAPI: WeatherAPI = OpenMeteoWeatherAPI() // Extensible to multiple APIs

    // State management - aviation safety through tracking
    private var currentCountryCode: String? = null
    private var lastCheckLocation: GeoPoint? = null
    private var lastWeatherUpdate: Long = 0L
    private var lastViewportChange: Long = 0L
    private var loadingJob: Job? = null
    private var weatherFetchJob: Job? = null

    // Universal country cache manager for intelligent country management (Priority 0 fix)
    private var countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager? = null

    // Reference to overlay coordinator for Hilbert batch operations
    private var overlayCoordinator: com.madanala.tern.ui.overlays.OverlayCoordinator? = null

    /**
     * Set the universal country cache manager (called by OverlayCoordinator)
     */
    fun setCountryCacheManager(countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager) {
        this.countryCacheManager = countryCacheManager
    }

    /**
     * Set the overlay coordinator for Hilbert batch operations (called by OverlayCoordinator)
     */
    override fun setOverlayCoordinator(coordinator: com.madanala.tern.ui.overlays.OverlayCoordinator) {
        this.overlayCoordinator = coordinator
    }

    /**
     * Get the overlay coordinator for Hilbert batch operations
     */
    private fun getOverlayCoordinator(): com.madanala.tern.ui.overlays.OverlayCoordinator? {
        return overlayCoordinator
    }

    // Track rendered PG spots and weather status for efficient updates
    private val currentlyRenderedPGSpots = mutableMapOf<String, PGSpotMarker>()
    private val visiblePGSpots = mutableSetOf<String>() // Currently visible in viewport

    // Aviation safety thresholds (inherited from airspace system with PG spot optimizations)
    private val checkDistanceKm = 2.0  // Smaller than airspace - PG spots are denser
    // private var maxPGSpots = 50         // ❌ REMOVED - now managed by BaseOverlayManager

    // ✅ REMOVED: Clustering configuration - may be obsolete with adaptive overlay system
    // The adaptive system now handles overlay density through memory-based allocation
    // and zone-based prioritization, potentially eliminating need for manual clustering

    data class PGSpotMarker(
         val marker: Marker,
         val feature: OverlayFeature,
         val weatherLoaded: Boolean = false,
         val weatherLastUpdated: Long = 0L,
         val center: GeoPoint,
         val distanceFromUser: Double = Double.MAX_VALUE
     )

    // === OVERLAY MANAGER LIFECYCLE ===

    override fun setEnabled(enabled: Boolean) {
        if (enabled) {
            initiateWeatherSystem()
        } else {
            shutdownWeatherSystem()
        }
    }

    override fun updateConfig(config: OverlayConfig) {
        // Log.d(TAG, "PG spots overlay manager config updated: $config")
        // Future: Handle PG spot specific configurations
    }

    override fun onOverlayAttached() {

        // Clean up any corrupted cache files from previous sessions
        pgSpotCache.cleanupCorruptedCache()

        initiateWeatherSystem()

        // Load PG spots for initial broader area to ensure they're visible on first load
        loadPGSpotsForInitialArea()
    }

    /**
      * LOAD PG SPOTS FOR INITIAL AREA
      * Ensures PG spots are visible immediately when overlay manager is attached
      */
    private fun loadPGSpotsForInitialArea() {
        if (mapView == null) {
            Log.w(TAG, "No map view available for initial PG spot loading")
            return
        }

        // Use a default center point (will be updated when GPS is available)
        val defaultCenter = GeoPoint(40.0, -100.0) // Central USA as reasonable default

        loadingJob?.cancel()
        loadingJob = coroutineScope.launch {
            loadPGSpotsForLocation(applicationContext, defaultCenter)
        }
    }

    override fun onOverlayDetached() {
        // Log.d(TAG, "PG spots overlay manager detached")
        loadingJob?.cancel()
        weatherFetchJob?.cancel()
        loadingJob = null
        weatherFetchJob = null
        currentlyRenderedPGSpots.clear()
        visiblePGSpots.clear()
    }

    // === MAP INTERACTIONS - WEATHER-AWARE ===

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        if (!isEnabled()) {
            return
        }

        // Postpone overlay operations until GPS fix is available
        if (!hasValidGPSFix) {
            return
        }

        // Additional coordinate validation for safety
        if (center.latitude < -90.0 || center.latitude > 90.0 || center.longitude < -180.0 || center.longitude > 180.0) {
            Log.w(TAG, "Coordinates out of valid range: lat=${center.latitude}, lon=${center.longitude}")
            return
        }

        // Additional validation for reasonable coordinate ranges - simplified for compatibility
        if (center.latitude < -90.0 || center.latitude > 90.0 || center.longitude < -180.0 || center.longitude > 180.0) {
            Log.w(TAG, "Coordinates out of valid range: lat=${center.latitude}, lon=${center.longitude}")
            return
        }

        // Check if significant enough movement to warrant reload - simplified distance check
        lastCheckLocation?.let { lastLocation ->
            val latDiffKm = Math.abs(center.latitude - lastLocation.latitude) * 111.1 // Rough km per degree
            val lonDiffKm = Math.abs(center.longitude - lastLocation.longitude) * 111.1 * Math.cos(Math.toRadians(center.latitude))
            val distanceKm = Math.sqrt(latDiffKm * latDiffKm + lonDiffKm * lonDiffKm)

            if (distanceKm < checkDistanceKm) {
                return
            }
        }

        // Check zoom level (LOD)
        if (!isZoomLevelSufficient(zoom)) {
            if (currentlyRenderedPGSpots.isNotEmpty()) {
                clearOverlays()
            }
            return
        }

        checkAndLoadPGSpots(center)
    }

    /**
     * Abstract method implementation from BaseOverlayManager
     */
    override fun onViewportChangedInternal(viewport: BoundingBox) {
        // Check zoom level (LOD)
        if (mapView != null && !isZoomLevelSufficient(mapView!!.zoomLevelDouble)) {
            if (currentlyRenderedPGSpots.isNotEmpty()) {
                clearOverlays()
            }
            return
        }
    }

    override fun onViewportChanged(viewport: BoundingBox) {
        super.onViewportChanged(viewport)

        if (!isEnabled()) return

        // Check zoom level (LOD)
        if (mapView != null && !isZoomLevelSufficient(mapView!!.zoomLevelDouble)) {
            return
        }

        // Aviation intelligence: Weather orchestration triggered by viewport changes
        updateViewportWeatherIntelligence(viewport)
    }

    // === WEATHER SYSTEM ORCHESTRATION ===

    /**
      * MASTER WEATHER COORDINATION
      * Viewport-aware orchestration of weather fetching and display with performance optimization
      */
    private fun updateViewportWeatherIntelligence(viewport: BoundingBox) {
        val currentTime = System.currentTimeMillis()

        // PERFORMANCE: Enhanced debouncing to prevent excessive weather API calls
        if (currentTime - lastViewportChange < WEATHER_FETCH_DEBOUNCE_MS) {
            return // Too frequent, skip
        }

        lastViewportChange = currentTime

        // PERFORMANCE: Only process if we have a significant viewport change
        val centerLat = (viewport.latNorth + viewport.latSouth) / 2.0
        val centerLon = (viewport.lonEast + viewport.lonWest) / 2.0
        val center = GeoPoint(centerLat, centerLon)

        lastCheckLocation?.let { lastLocation ->
            val latDiffKm = Math.abs(center.latitude - lastLocation.latitude) * 111.1
            val lonDiffKm = Math.abs(center.longitude - lastLocation.longitude) * 111.1 * Math.cos(Math.toRadians(center.latitude))
            val distanceKm = Math.sqrt(latDiffKm * latDiffKm + lonDiffKm * lonDiffKm)

            // Skip if movement is less than 100m (micro-movements)
            if (distanceKm < 0.1) {
                return
            }
        }

        // Perform viewport analysis
        val visiblePGSpotsNew = determineVisiblePGSpots(viewport)

        // PERFORMANCE: Only update if there are significant visibility changes
        if (visiblePGSpotsNew.size == visiblePGSpots.size && visiblePGSpots.containsAll(visiblePGSpotsNew)) {
            return // No significant change, skip processing
        }

        // Calculate weather intelligence updates needed
        val spotsNeedingWeather = visiblePGSpotsNew.filter { pgSpotId ->
            val marker = currentlyRenderedPGSpots[pgSpotId]
            marker != null && !marker.weatherLoaded &&
            isWithinWeatherRange(marker.center, viewport)
        }

        val spotsNotVisible = visiblePGSpots - visiblePGSpotsNew
        updateVisiblePGSpots(visiblePGSpotsNew)

        // PERFORMANCE: Only trigger weather gathering if we have new spots that actually need weather
        if (spotsNeedingWeather.isNotEmpty()) {
            launchWeatherFetchingForPGSpots(spotsNeedingWeather.toSet())

            // Also trigger occasional background refresh for current weather (less frequent)
            val shouldRefreshCurrent = currentTime - lastWeatherUpdate > WEATHER_UPDATE_INTERVAL_MS
            if (shouldRefreshCurrent && visiblePGSpotsNew.isNotEmpty()) {
                launchBackgroundWeatherRefresh(visiblePGSpotsNew)
            }
        }

        // Clean up weather data for spots no longer visible
        cleanupNonVisiblePGSpotWeather(spotsNotVisible)

    }

    /**
      * LAUNCH WEATHER FETCHING FOR PG SPOTS
      * Event-driven weather acquisition for aviation safety with performance optimization
      */
    private fun launchWeatherFetchingForPGSpots(pgSpotIds: Set<String>) {
        if (pgSpotIds.isEmpty()) return

        weatherFetchJob?.cancel() // Cancel any previous weather fetching

        weatherFetchJob = coroutineScope.launch {
            try {

                // BATCH: Dispatch Redux actions in batches to prevent state update storms
                val batchSize = 5 // Process 5 spots at a time
                val spotList = pgSpotIds.toList()

                for (i in spotList.indices step batchSize) {
                    val batch = spotList.subList(i, minOf(i + batchSize, spotList.size))

                    // Dispatch Redux actions for this batch
                    batch.forEach { pgSpotId ->
                        val marker = currentlyRenderedPGSpots[pgSpotId]
                        if (marker != null) {
                            mapStore?.dispatch(WeatherActions.FetchWeatherForPGSpot(
                                pgSpotId, marker.center.latitude, marker.center.longitude
                            ))
                        }
                    }

                    // Small delay between batches to prevent overwhelming Redux
                    delay(100)
                }

                // PERFORMANCE OPTIMIZATION: Only fetch weather for spots that truly need it
                val spotsNeedingWeather = pgSpotIds.filter { pgSpotId ->
                    val marker = currentlyRenderedPGSpots[pgSpotId]
                    marker != null && needsWeatherRefresh(pgSpotId) &&
                    isWithinWeatherRange(marker.center, mapView?.boundingBox ?: return@launch)
                }

                if (spotsNeedingWeather.isNotEmpty()) {

                    // Fetch weather data sequentially to respect API rate limits
                    for (pgSpotId in spotsNeedingWeather) {
                        val marker = currentlyRenderedPGSpots[pgSpotId] ?: continue

                        try {
                            // Check cache first (aviation performance optimization)
                            var weatherForecast = weatherCache.queryNearbyWeather(
                                marker.center.latitude,
                                marker.center.longitude
                            )

                            // Fetch fresh data if cache miss or stale
                            if (weatherForecast == null) {
                                weatherForecast = weatherAPI.fetchForecast(
                                    marker.center.latitude,
                                    marker.center.longitude
                                )

                                // Cache the fresh data for future requests
                                weatherForecast?.let { forecast ->
                                    weatherCache.cacheWeatherData(
                                        marker.center.latitude,
                                        marker.center.longitude,
                                        forecast
                                    )
                                }
                            }

                            // Update Redux state with fetched weather
                            if (weatherForecast != null) {
                                  mapStore?.dispatch(WeatherActions.WeatherFetched(pgSpotId, weatherForecast))
                                  updatePGSpotWithWindGauge(pgSpotId, weatherForecast)
                                  updateWeatherTimestamp(pgSpotId) // Mark weather as fresh
                              } else {
                                 mapStore?.dispatch(WeatherActions.WeatherFetchError(
                                     pgSpotId,
                                     Exception("Weather fetch returned null")
                                 ))
                                 Log.w(TAG, "Weather fetch returned null for PG spot: $pgSpotId")
                             }

                        } catch (e: Exception) {
                            // Aviation resilience - single PG spot weather failure doesn't break system
                            Log.w(TAG, "Failed to fetch weather for PG spot $pgSpotId", e)
                            mapStore?.dispatch(WeatherActions.WeatherFetchError(pgSpotId, e))
                        }

                        // Respect API rate limits between requests (aviation professional behavior)
                        delay(500) // 2 requests per second maximum
                    }
                }

                lastWeatherUpdate = System.currentTimeMillis()

            } catch (e: CancellationException) {
                // Log.d(TAG, "Weather fetching cancelled due to user interaction")
            } catch (e: Exception) {
                Log.e(TAG, "Critical error in weather fetching orchestration", e)
            }
        }
    }

    /**
     * UPDATE PG SPOT WITH DYNAMIC WIND GAUGE
     * Smooth transition from static icon to weather-aware gauge
     */
    private fun updatePGSpotWithWindGauge(pgSpotId: String, forecast: com.madanala.tern.utils.WeatherForecast) {
        val marker = currentlyRenderedPGSpots[pgSpotId]?.marker ?: return

        // Extract current wind conditions for the gauge
        forecast.current?.wind?.let { wind ->
            try {
                // Update marker title for accessibility/fallback
                val windTitle = "PG Spot ${pgSpotId}\n" +
                    "Wind: ${wind.speed.roundToInt()} kt @ ${wind.direction.roundToInt()}°"
                marker.title = windTitle

                // Generate dynamic bitmap from Composable
                // Must be done on Main thread
                coroutineScope.launch(Dispatchers.Main) {
                    try {
                        val mapView = mapView ?: return@launch
                        val bitmap = com.madanala.tern.utils.ViewToBitmap.createBitmapFromComposable(
                            parentView = mapView,
                            width = 150, 
                            height = 150
                        ) {
                            com.madanala.tern.ui.components.WindGaugeMarker(
                                speed = wind.speed,
                                direction = wind.direction,
                                gust = wind.gust,
                                isStale = forecast.isStale()
                            )
                        }
                        
                        marker.icon = android.graphics.drawable.BitmapDrawable(applicationContext.resources, bitmap)
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) // Center the gauge
                        mapView.invalidate()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to generate bitmap for PG spot $pgSpotId", e)
                    }
                }

            } catch (e: Exception) {
                Log.w(TAG, "Failed to update wind gauge for PG spot $pgSpotId", e)
            }
        }
    }

    // === PG SPOTS MANAGEMENT (INTEGRATED WITH WEATHER) ===

    /**
     * LOAD & DISPLAY PG SPOTS WITH WEATHER INTEGRATION
     */
    private fun checkAndLoadPGSpots(center: GeoPoint) {
        if (mapView == null) {
            Log.w(TAG, "No map view available for weather-aware PG spot loading")
            return
        }

        loadingJob?.cancel()

        loadingJob = coroutineScope.launch {
            loadPGSpotsForLocation(applicationContext, center)
        }
    }

    /**
     * CORE PG SPOTS LOADING WITH WEATHER ORCHESTRATION
     */
    private suspend fun loadPGSpotsForLocation(context: Context, center: GeoPoint) {
        try {

            // Geographic determination (same as airspace system)
            val countryCode = com.madanala.tern.utils.CountryUtils.getCountryCodeFromGeoPoint(context, center)
            if (countryCode == null) {
                Log.w(TAG, "Could not determine country code for PG spots at: $center")
                return
            }

            // Major movement detection for efficient caching - proper distance-based check
            val isMajorMove = lastCheckLocation?.let { lastLocation ->
                val distance = center.distanceToAsDouble(lastLocation) / 1000.0
                distance > 50.0 // Only major move if >50km
            } ?: true // First load is always "major"

            if (isMajorMove) {
                clearOverlays()
            }

            // 🗺️ FlexBuffers + Hilbert Caching Architecture for PG Spots
            var nearbyFeatures: List<OverlayFeature>

            if (!pgSpotCache.isCached(countryCode)) {
                // DOWNLOAD + PARSE + CACHE as FlexBuffers with Hilbert spatial index

                val downloadedFeatures = try {
                    withContext(Dispatchers.IO) {
                        pgSpotCache.cachePGSpotsData(countryCode)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    return // Exit gracefully on cancellation
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading PG spots for $countryCode: ${e.message}", e)
                    // Try to use stale cache as fallback
                    pgSpotCache.getCachedPGSpots(countryCode)
                }

                if (downloadedFeatures != null && downloadedFeatures.isNotEmpty()) {
                    // Now perform spatial query on the newly cached data
                    nearbyFeatures = pgSpotCache.queryNearbyPGSpots(countryCode, center, 200.0)
                } else {
                    Log.w(TAG, "Failed to download PG spots data for $countryCode, trying fallback")

                    // Try to use existing cache even if stale (better than no data)
                    val fallbackFeatures = pgSpotCache.getCachedPGSpots(countryCode)
                    if (fallbackFeatures != null && fallbackFeatures.isNotEmpty()) {
                        // Log.d(TAG, "Using stale cache as fallback: ${fallbackFeatures.size} PG spots for $countryCode")
                        nearbyFeatures = fallbackFeatures.filter { feature ->
                            val distance = center.distanceToAsDouble(feature.centroid) / 1000.0 // Convert to km
                            distance <= 200.0 // Same 200km radius as fresh data
                        }
                    } else {
                        Log.w(TAG, "No cached or downloaded PG spots available for $countryCode")
                        return // Graceful degradation - weather works without PG spots
                    }
                }
            } else {
                // CACHED: Use Hilbert spatial query for nearby features only
                nearbyFeatures = pgSpotCache.queryNearbyPGSpots(countryCode, center, 200.0)
            }


            if (nearbyFeatures.isNotEmpty()) {
                renderPGSpotFeaturesWithWeather(nearbyFeatures)

                // Initial weather orchestration for loaded PG spots
                launchWeatherFetchingForVisiblePGSpots()

                currentCountryCode = countryCode
                lastCheckLocation = center
            } else {
                // Log.d(TAG, "No PG spots found within 200 km of $center")
            }

        } catch (e: CancellationException) {
        } catch (e: Exception) {
            Log.e(TAG, "Error loading PG spots data: ${e.message}", e)
        }
    }

    /**
       * RENDER PG SPOTS WITH WEATHER CAPABILITY
       * Initial static display with future dynamic weather integration
       */
    /**
       * RENDER PG SPOTS WITH WEATHER CAPABILITY
       * Initial static display with future dynamic weather integration
       */
    private fun renderPGSpotFeaturesWithWeather(features: List<OverlayFeature>) {
        val center = mapView?.mapCenter as? GeoPoint ?: return

        // 🎯 STEP 0: Prioritize features (Distance-based sorting + Limit)
        val prioritizedFeatures = prioritizeFeatures(
            features,
            center,
            getMaxOverlaysForCurrentConditions()
        ) { it.centroid }

        // 🎯 STEP 1: Determine desired state
        val desiredIds = prioritizedFeatures.map { generatePGSpotId(it) }.toSet()
        val currentIds = currentlyRenderedPGSpots.keys

        val toRemoveIds = currentIds - desiredIds
        val toAddFeatures = prioritizedFeatures.filter { !currentIds.contains(generatePGSpotId(it)) }

        // 🎯 STEP 2: Remove old spots (Outside -> Center)
        val sortedRemovals = sortForRemoval(toRemoveIds.toList(), center) { id ->
            currentlyRenderedPGSpots[id]?.center ?: center
        }
        removePGSpots(sortedRemovals)

        // 🎯 STEP 3: Add new spots (Center -> Outside)
        val sortedAdditions = sortForAddition(toAddFeatures, center) { it.centroid }
        sortedAdditions.forEach { feature ->
            addPGSpotIfNotExists(feature)
        }

        val centerStr = String.format("@ %.4f,%.4f", center.latitude, center.longitude)

        // Check visibility after rendering
        val actuallyVisibleAfterRender = currentlyRenderedPGSpots.count { (_, markerData) ->
            mapView?.overlays?.contains(markerData.marker) == true &&
            isPointInBoundingBox(markerData.center, mapView?.boundingBox ?: return@count false)
        }

        Log.d(TAG, String.format(
            "PG spots rendered: %d total, %d visible %s",
            prioritizedFeatures.size,
            actuallyVisibleAfterRender,
            centerStr
        ))
        
        // Log names for test verification
        prioritizedFeatures.take(50).forEach { feature ->
             val name = feature.feature["properties"]?.let { (it as? Map<*, *>)?.get("name") } ?: "Unknown"
             Log.d(TAG, "Rendered PG Spot: $name")
        }
    }

    /**
     * Remove PG spots with animation
     */
    private fun removePGSpots(ids: List<String>) {
        val coordinator = getOverlayCoordinator()
        if (coordinator != null) {
             ids.forEach { id ->
                 currentlyRenderedPGSpots[id]?.let { markerData ->
                     coordinator.removeOverlayFromBatch(markerData.marker, id, markerData.center)
                 }
             }
             coordinator.removeOverlayFromBatch()
             ids.forEach { currentlyRenderedPGSpots.remove(it) }
        } else {
            // Fallback
            ids.forEach { id ->
                currentlyRenderedPGSpots[id]?.let { markerData ->
                    animationManager?.animateOverlayRemoval(markerData.marker, id, mapView!!) {
                        currentlyRenderedPGSpots.remove(id)
                    }
                }
            }
        }
    }

    /**
        * ADD PG SPOT ONLY IF NOT EXISTS (prevents duplicates)
        */
    private fun addPGSpotIfNotExists(feature: OverlayFeature) {
        val spotId = generatePGSpotId(feature)

        // Check if already exists to prevent duplicates
        if (currentlyRenderedPGSpots.containsKey(spotId)) {
            return
        }

        try {
            // Create standard PG spot marker initially
            val marker = createPGSpotMarker(feature)

            // Calculate distance from user for prioritization
            val center = feature.centroid
            val distanceKm = calculateDistanceFromUser(center)

            // Store marker with weather capability and initial timestamp
            val pgSpotMarker = PGSpotMarker(
                marker = marker,
                feature = feature,
                weatherLoaded = false,
                weatherLastUpdated = 0L, // Never updated initially
                center = center,
                distanceFromUser = distanceKm
            )

            // Add to tracking immediately
            currentlyRenderedPGSpots[spotId] = pgSpotMarker

            // ✅ FIXED: Process each overlay immediately with animation (no batching)
            val coordinator = getOverlayCoordinator()
            if (coordinator != null) {
                // Use coordinator's animation manager for smooth transitions
                coordinator.getAnimationManager().animateOverlayAddition(
                    overlay = marker,
                    overlayId = spotId,
                    mapView = mapView!!,
                    staggerDelay = 0L // No stagger for immediate processing
                ) {
                    // Animation completed - overlay is now visible
                }
            } else {
                // Fallback to direct animation manager if coordinator not available
                animationManager?.animateOverlayAddition(
                    overlay = marker,
                    overlayId = spotId,
                    mapView = mapView!!,
                    staggerDelay = 0L
                ) {
                    // Animation completed
                } ?: throw IllegalStateException(
                    "Animation manager is required for PG spot overlay addition. " +
                    "Ensure OverlayCoordinator is properly initialized."
                )
            }

            // Add click handler for weather details
            marker.setOnMarkerClickListener { clickedMarker, _ ->
                showPGSpotWeatherDetails(feature)
                true
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to render PG spot feature $spotId", e)
        }
    }

    // === WEATHER TIMESTAMP MANAGEMENT ===

    /**
     * Check if weather data needs refreshing based on timestamp
     */
    private fun needsWeatherRefresh(pgSpotId: String): Boolean {
         val marker = currentlyRenderedPGSpots[pgSpotId] ?: return true
         val currentTime = System.currentTimeMillis()

         // Refresh if never loaded OR if older than update interval
         return !marker.weatherLoaded ||
                (marker.weatherLastUpdated > 0 && currentTime - marker.weatherLastUpdated > WEATHER_UPDATE_INTERVAL_MS)
     }

    /**
     * Update weather timestamp for a PG spot after successful fetch
     */
    private fun updateWeatherTimestamp(pgSpotId: String) {
         currentlyRenderedPGSpots[pgSpotId]?.let { existingMarker ->
             val updatedMarker = existingMarker.copy(
                 weatherLoaded = true,
                 weatherLastUpdated = System.currentTimeMillis()
             )
             currentlyRenderedPGSpots[pgSpotId] = updatedMarker
         }
     }


    // === UTILITY METHODS ===

    private fun createPGSpotMarker(feature: OverlayFeature): Marker {
        val center = feature.centroid

        return Marker(mapView).apply {
            position = center
            title = "PG Launch Site" // Will be updated with weather info
            snippet = "Tap for weather details"

            // Set custom icon to replace default hand symbol
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            // Use app launcher icon from mipmap resources (same as used in MapViewModel)
            try {
                val drawable = ContextCompat.getDrawable(applicationContext, R.mipmap.ic_launcher)
                drawable?.let {
                    val originalBitmap = it.toBitmap()
                    // Scale to 1/2 size for better touch interaction while maintaining visibility
                    val scaledWidth = originalBitmap.width / 2
                    val scaledHeight = originalBitmap.height / 2
                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(
                        originalBitmap,
                        scaledWidth,
                        scaledHeight,
                        true // Use bilinear filtering
                    )
                    icon = android.graphics.drawable.BitmapDrawable(applicationContext.resources, scaledBitmap)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set custom PG spot icon, using default", e)
            }
        }
    }

    // Implementation of utility methods

    private fun calculateDistanceFromUser(center: GeoPoint): Double {
        val userLocation = mapView?.mapCenter ?: return Double.MAX_VALUE
        // Simple distance calculation for compatibility - km
        val latDiffKm = Math.abs(center.latitude - userLocation.latitude) * 111.1
        val lonDiffKm = Math.abs(center.longitude - userLocation.longitude) * 111.1 * Math.cos(Math.toRadians(center.latitude))
        return Math.sqrt(latDiffKm * latDiffKm + lonDiffKm * lonDiffKm)
    }

    private fun determineVisiblePGSpots(viewport: BoundingBox): Set<String> {
        return currentlyRenderedPGSpots.mapNotNull { (id, marker) ->
            val position = marker.center
            if (isPointInBoundingBox(position, viewport)) id else null
        }.toSet()
    }

    private fun isWithinWeatherRange(center: GeoPoint, viewport: BoundingBox): Boolean {
        return isPointInBoundingBox(center, viewport)
    }

    private fun isPointInBoundingBox(point: GeoPoint, boundingBox: BoundingBox): Boolean {
        return point.latitude >= boundingBox.latSouth && point.latitude <= boundingBox.latNorth &&
               point.longitude >= boundingBox.lonWest && point.longitude <= boundingBox.lonEast
    }

    private fun updateVisiblePGSpots(visibleSpots: Set<String>) {
        val newlyVisible = visibleSpots - visiblePGSpots
        val noLongerVisible = visiblePGSpots - visibleSpots

        visiblePGSpots.clear()
        visiblePGSpots.addAll(visibleSpots)


        // Launch weather fetching for newly visible spots
        if (newlyVisible.isNotEmpty()) {
            launchWeatherFetchingForPGSpots(newlyVisible.map { id ->
                currentlyRenderedPGSpots[id]?.center?.let { geoPoint ->
                    "placeholder_$id" // Use actual ID if needed
                } ?: "unknown_$id"
            }.toSet())
        }

        // Cleanup weather for spots no longer visible
        cleanupNonVisiblePGSpotWeather(noLongerVisible)
    }

    // Weather cleanup and orchestration methods
    private fun cleanupNonVisiblePGSpotWeather(nonVisible: Set<String>) {
        nonVisible.forEach { pgSpotId ->
            // Optionally dispatch action to clear weather state if memory is concern
            mapStore?.dispatch(WeatherActions.WeatherFetchError(pgSpotId, Exception("Spot no longer visible")))
        }
    }

    private fun launchBackgroundWeatherRefresh(visibleSpots: Set<String>) {
        if (visibleSpots.isEmpty()) return

        weatherFetchJob?.cancel()
        weatherFetchJob = coroutineScope.launch {
            try {
                delay(WEATHER_UPDATE_INTERVAL_MS) // Wait before refreshing

                val refreshedIds = visibleSpots.filter { id ->
                     needsWeatherRefresh(id)
                 }.toSet()

                if (refreshedIds.isNotEmpty()) {
                    // Note: This is simplified - in production, would fetch fresh data
                    // Log.d(TAG, "Refreshing weather for ${refreshedIds.size} PG spots")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in background weather refresh", e)
            }
        }
    }

    private fun launchWeatherFetchingForVisiblePGSpots() {
        val visibleIds = visiblePGSpots.toSet()
        if (visibleIds.isNotEmpty()) {
            launchWeatherFetchingForPGSpots(visibleIds)
        }
    }

    private fun showPGSpotWeatherDetails(feature: OverlayFeature) {
        // Generate ID from feature
        val pgSpotId = generatePGSpotId(feature)
        val forecast = weatherCache.queryNearbyWeather(feature.centroid.latitude, feature.centroid.longitude)

        // Dispatch action to show weather details dialog
        mapStore?.dispatch(WeatherActions.ShowWeatherDetails(pgSpotId, forecast))

        // If weather is missing, trigger immediate fetch
        if (forecast == null) {
            // Log.d(TAG, "Weather missing for clicked spot $pgSpotId - triggering immediate fetch")
            launchWeatherFetchingForPGSpots(setOf(pgSpotId))
        }
    }

    private fun initiateWeatherSystem() {
        // System startup - check API availability, preload cache, etc.
        coroutineScope.launch {
            try {
                val available = weatherAPI.isAvailable()
                mapStore?.dispatch(WeatherActions.WeatherAPIStatus(available))
                // Log.d(TAG, "Weather API availability: $available")
            } catch (e: Exception) {
                Log.w(TAG, "Error checking weather API availability", e)
            }
        }
    }

    private fun shutdownWeatherSystem() {
        // Log.d(TAG, "Shutting down weather system")
        weatherFetchJob?.cancel()
        // Clear cache if needed, but preserve for offline capability
    }



    private fun generatePGSpotId(feature: OverlayFeature): String {
        // Generate unique ID for PG spot tracking
        return "pg_${feature.centroid.latitude}_${feature.centroid.longitude}".replace(".", "_")
    }

    override fun onReduxStateChanged(state: MapState) {
        // Handle weather-related state changes
        if (!isEnabled()) {
            clearOverlays()
            currentCountryCode = null
            lastCheckLocation = null
            visiblePGSpots.clear()
        } else if (mapView != null) {
            // Postpone Redux-triggered overlay operations until GPS fix is available
            if (!hasValidGPSFix) {
                return
            }

            val center = mapView!!.mapCenter as GeoPoint
            checkAndLoadPGSpots(center)
        }
    }

    /**
     * Handle overlay budget changes with PG spot-specific logging
     */
    override fun onOverlayBudgetChanged(budget: com.madanala.tern.utils.OverlayBudget) {
        super.onOverlayBudgetChanged(budget)

        // Get current map center for geographic context
        val center = mapView?.mapCenter
        val centerStr = if (center != null) {
            String.format("@ %.4f,%.4f", center.latitude, center.longitude)
        } else {
            "@ unknown location"
        }

        // Get actually visible PG spots (added to map vs just created)
        val actuallyVisibleSpots = currentlyRenderedPGSpots.count { (_, markerData) ->
            mapView?.overlays?.contains(markerData.marker) == true &&
            isPointInBoundingBox(markerData.center, mapView?.boundingBox ?: return@count false)
        }


        Log.d(TAG, String.format(
            "PG Spot Budget: %d total (Created: %d, Visible: %d %s)",
            budget.totalOverlays,
            currentlyRenderedPGSpots.size,
            actuallyVisibleSpots,
            centerStr
        ))
    }

    /**
     * Handle memory state changes for PG spot-specific optimizations
     */
    override fun onMemoryStateChanged(memoryState: com.madanala.tern.utils.ApplicationMemoryState) {
        super.onMemoryStateChanged(memoryState)

        // If memory pressure is high, trigger enhanced cleanup and reduce weather updates
        if (memoryState.calculatedPressure == com.madanala.tern.utils.MemoryPressureLevel.LOW_MEMORY ||
            memoryState.calculatedPressure == com.madanala.tern.utils.MemoryPressureLevel.CRITICAL_MEMORY) {
            Log.w(TAG, "Memory pressure detected - triggering enhanced PG spot cleanup")
            triggerEmergencyCleanup()
        }
    }

    /**
     * Remove PG spots that are not visible in the current viewport (most efficient cleanup)
     */
    override fun removeInvisibleOverlays(): Int {
        if (currentlyRenderedPGSpots.isEmpty()) return 0

        val viewport = mapView?.boundingBox ?: return 0
        val mapCenter = mapView?.mapCenter as? GeoPoint ?: return 0

        // Find PG spots that are BOTH not visible AND not safety-critical
        val invisibleNonCriticalSpots = currentlyRenderedPGSpots.entries.filter { (spotId, pgSpotMarker) ->
            val isInvisible = !isPointInBoundingBox(pgSpotMarker.center, viewport)
            val distance = calculateDistanceFromUser(pgSpotMarker.center)
            val isFar = distance > com.madanala.tern.utils.DistanceZone.MID.maxKm

            // Remove if invisible AND far from center (but not safety-critical)
            isInvisible && isFar && !isSafetyCriticalPGSpot(spotId, distance)
        }

        if (invisibleNonCriticalSpots.isEmpty()) return 0

        // Remove invisible non-critical PG spots
        val coordinator = getOverlayCoordinator()
        var removedCount = 0

        if (coordinator != null) {
            invisibleNonCriticalSpots.forEach { (spotId, pgSpotMarker) ->
                coordinator.removeOverlayFromBatch(
                    overlay = pgSpotMarker.marker,
                    overlayId = spotId,
                    centroid = pgSpotMarker.center
                )
                removedCount++
            }
            coordinator.removeOverlayFromBatch()
        } else {
            invisibleNonCriticalSpots.forEach { (spotId, pgSpotMarker) ->
                animationManager?.animateOverlayRemoval(
                    overlay = pgSpotMarker.marker,
                    overlayId = spotId,
                    mapView = mapView!!
                ) {
                    // Remove from tracking
                }
                removedCount++
            }
        }

        // Update tracking
        invisibleNonCriticalSpots.forEach { (spotId, _) ->
            currentlyRenderedPGSpots.remove(spotId)
        }

        return removedCount
    }

    /**
     * Check if PG spot is safety-critical (should never be removed for memory reasons)
     */
    private fun isSafetyCriticalPGSpot(spotId: String, distanceFromCenter: Double): Boolean {
        // Safety-critical PG spots should be in CORE zone (closest landing options)
        val coreThreshold = com.madanala.tern.utils.DistanceZone.CORE.maxKm

        // Consider PG spots within CORE zone as safety-critical for immediate landing
        return distanceFromCenter <= coreThreshold
    }

    /**
     * Clear PG spot overlays in a specific distance zone for emergency cleanup
     */
    override fun clearOverlaysInZone(zone: com.madanala.tern.utils.DistanceZone): Int {
        if (currentlyRenderedPGSpots.isEmpty()) return 0

        val mapCenter = mapView?.mapCenter ?: return 0
        val zoneThreshold = zone.maxKm

        // Find PG spots in the specified zone (farthest from center)
        val spotsInZone = currentlyRenderedPGSpots.entries.filter { (_, pgSpotMarker) ->
            val distance = calculateDistanceFromUser(pgSpotMarker.center)
            distance > zoneThreshold
        }

        if (spotsInZone.isEmpty()) return 0

        // Remove PG spots in this zone
        val coordinator = getOverlayCoordinator()
        var removedCount = 0

        if (coordinator != null) {
            spotsInZone.forEach { (spotId, pgSpotMarker) ->
                coordinator.removeOverlayFromBatch(
                    overlay = pgSpotMarker.marker,
                    overlayId = spotId,
                    centroid = pgSpotMarker.center
                )
                removedCount++
            }
            coordinator.removeOverlayFromBatch()
        } else {
            spotsInZone.forEach { (spotId, pgSpotMarker) ->
                animationManager?.animateOverlayRemoval(
                    overlay = pgSpotMarker.marker,
                    overlayId = spotId,
                    mapView = mapView!!
                ) {
                    // Remove from tracking
                }
                removedCount++
            }
        }

        // Update tracking
        spotsInZone.forEach { (spotId, _) ->
            currentlyRenderedPGSpots.remove(spotId)
        }

        return removedCount
    }

    /**
     * Preserve safety-critical PG spot overlays during emergency cleanup
     * PG spots in CORE zone are considered safety-critical for immediate landing options
     */
    override fun preserveSafetyCriticalOverlays(): Int {
        if (currentlyRenderedPGSpots.isEmpty()) return 0

        val mapCenter = mapView?.mapCenter ?: return 0
        val coreZoneThreshold = com.madanala.tern.utils.DistanceZone.CORE.maxKm

        // Find safety-critical PG spots in CORE zone (closest landing options)
        val safetyCriticalSpots = currentlyRenderedPGSpots.entries.filter { (_, pgSpotMarker) ->
            val distance = calculateDistanceFromUser(pgSpotMarker.center)
            distance <= coreZoneThreshold
        }

        return safetyCriticalSpots.size
    }

    override fun clearOverlays() {
        mapView?.let { map ->
            // 🎨 Clear all PG spots through animation manager (fade-out all)
            val markersToRemove = currentlyRenderedPGSpots.values.toList()
            val spotIds = currentlyRenderedPGSpots.keys.toList()

            // Use Hilbert-ordered batch removal for smooth outside-to-center removal
            val coordinator = getOverlayCoordinator()
            if (coordinator != null) {
                // Add all overlays to batch for ordered removal (outside to center)
                spotIds.forEach { spotId ->
                    val pgSpotMarker = currentlyRenderedPGSpots[spotId]
                    if (pgSpotMarker != null) {
                        coordinator.removeOverlayFromBatch(
                            overlay = pgSpotMarker.marker,
                            overlayId = spotId,
                            centroid = pgSpotMarker.center
                        )
                    }
                }

                // Process the batch for Hilbert-ordered removal
                coordinator.removeOverlayFromBatch()
            } else {
                // Fallback to direct animation manager if coordinator not available
                spotIds.forEachIndexed { index, spotId ->
                    val pgSpotMarker = currentlyRenderedPGSpots[spotId]
                    if (pgSpotMarker != null) {
                        animationManager?.animateOverlayRemoval(
                            overlay = pgSpotMarker.marker,
                            overlayId = spotId,
                            mapView = map
                        ) {
                            // Animation manager handles removal - just update tracking
                        } ?: throw IllegalStateException(
                            "Animation manager is required for PG spot overlay removal. " +
                            "Ensure OverlayCoordinator is properly initialized."
                        )
                    }
                }
            }

            // Clear tracking (animation manager handles actual removal)
            currentlyRenderedPGSpots.clear()
            visiblePGSpots.clear()
            // Log.d(TAG, "Scheduled clear of ${markersToRemove.size} PG spot overlays via animation manager")
        } ?: run {
            // No map view available - just clear tracking
            currentlyRenderedPGSpots.clear()
            visiblePGSpots.clear()
            // Log.d(TAG, "Cleared PG spot tracking (no map view)")
        }
    }
}
