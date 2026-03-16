package com.madanala.tern.ui.overlays

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import com.madanala.tern.R
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.redux.WeatherActions
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import com.madanala.tern.utils.OpenMeteoWeatherAPI
import com.madanala.tern.utils.WeatherCache
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
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import com.madanala.tern.redux.RouteConstants
import com.madanala.tern.utils.PGSpotCache

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
    mapStore: MapStore?,
    private val pgSpotCache: PGSpotCache = CacheManager.pgSpotCache,
    private val weatherAPI: WeatherAPI = OpenMeteoWeatherAPI(),
    private val weatherCache: WeatherCache = CacheManager.weatherCache
) : BaseOverlayManager(OverlayType.PG_SPOTS, mapStore) {

    init {
        Log.d(TAG, "PGSpotOverlayManager INITIALIZED")
    }

    companion object {
        private const val TAG = "PGSpotOverlayManager"
        private const val WEATHER_VISIBLE_DISTANCE_KM = 0.5  // Show weather within 500m
        private const val WEATHER_UPDATE_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes between updates
        private const val WEATHER_FETCH_DEBOUNCE_MS = 1000L // Debounce rapid viewport changes
    }

    // Aviation-grade weather infrastructure - specialized caches for each data type
    // Injected via constructor for testability
    // private val pgSpotCache = CacheManager.pgSpotCache // Use singleton to prevent duplicate downloads
    // private val weatherCache = CacheManager.weatherCache
    // private val weatherAPI: WeatherAPI = OpenMeteoWeatherAPI() // Extensible to multiple APIs

    // State management - aviation safety through tracking
    private var currentCountryCode: String? = null
    private var lastCheckLocation: GeoPoint? = null
    private var lastWeatherUpdate: Long = 0L
    private var lastViewportChange: Long = 0L
    private var loadingJob: Job? = null
    private var weatherFetchJob: Job? = null

    // Reference to country cache manager
    private var countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager? = null

    // Track the country loaded listener for cleanup
    private var countryLoadedListener: ((String) -> Unit)? = null

    /**
     * Set the universal country cache manager (called by OverlayCoordinator)
     */
    fun setCountryCacheManager(countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager) {
        this.countryCacheManager = countryCacheManager
        
        // Remove old listener if it exists to prevent leaks/accumulation
        countryLoadedListener?.let { 
            this.countryCacheManager?.onCountryLoadedListeners?.remove(it) 
        }

        val listener: (String) -> Unit = { countryCode ->
             coroutineScope.launch {
                 mapView?.mapCenter?.let { center ->
                     // Refresh PG spots for current location now that data is available
                     checkAndLoadPGSpots(center as GeoPoint, force = true)
                 }
             }
        }
        
        this.countryLoadedListener = listener
        this.countryCacheManager?.onCountryLoadedListeners?.add(listener)

        // PROACTIVE REFRESH: If we already have cached countries, trigger a load immediately
        if (countryCacheManager.getCachedCountries().isNotEmpty()) {
            coroutineScope.launch {
                mapView?.mapCenter?.let { center ->
                    checkAndLoadPGSpots(center as GeoPoint)
                }
            }
        }
    }

    private var lastLoadPosition: GeoPoint? = null

    /**
     * RESET state for test stability
     */
    override fun reset() {
        Log.d("PGSpotOverlayManager", "Resetting PGSpotOverlayManager state")
        lastLoadPosition = null
        lastCheckLocation = null
        lastWeatherUpdate = 0L
        lastViewportChange = 0L
        // Force immediate clear of existing markers if needed
        currentlyRenderedPGSpots.clear()
        visiblePGSpots.clear()
        Log.d("PGSpotOverlayManager", "PGSpotOverlayManager reset complete")
    }

    // Track rendered PG spots and weather status for efficient updates
    private val currentlyRenderedPGSpots = mutableMapOf<String, PGSpotMarker>()
    private val visiblePGSpots = mutableSetOf<String>() // Currently visible in viewport

    // Aviation safety thresholds (inherited from airspace system with PG spot optimizations)
    private val checkDistanceKm = 2.0  // Smaller than airspace - PG spots are denser

    // PERFORMANCE CACHES: Drastically reduces GC pressure during map interaction
    private var cachedStaticIcon: Bitmap? = null
    private val windGaugeCache = LruCache<Int, Bitmap>(50) // Cache last 50 unique wind gauge bitmaps

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

    override fun getRenderedCount(): Int {
        return currentlyRenderedPGSpots.size
    }

    override fun onOverlayAttached() {
        // Clean up any corrupted cache files from previous sessions
        pgSpotCache.cleanupCorruptedCache()

        initiateWeatherSystem()

        // Load PG spots for initial broader area to ensure they're visible on first load
        // loadPGSpotsForInitialArea()
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

        // Remove listener to prevent memory leaks
        countryLoadedListener?.let { 
            this.countryCacheManager?.onCountryLoadedListeners?.remove(it) 
        }
        countryLoadedListener = null

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



        // Additional coordinate validation for safety
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



        checkAndLoadPGSpots(center)
    }

    /**
     * Abstract method implementation from BaseOverlayManager
     */
    override fun onViewportChangedInternal(viewport: BoundingBox) {
        // No viewport optimization needed for PG Spots currently
    }

    override fun onViewportChanged(viewport: BoundingBox) {
        super.onViewportChanged(viewport)

        if (!isEnabled()) return



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
                        
                        // PERFORMANCE: Create unique key based on wind conditions for caching
                        val cacheKey = arrayOf(
                            wind.speed.roundToInt(),
                            wind.direction.roundToInt(),
                            wind.gust.roundToInt(),
                            forecast.isStale()
                        ).contentHashCode()

                        var bitmap = windGaugeCache.get(cacheKey)
                        
                        if (bitmap == null) {
                            // THROTTLING: Slightly stagger the generation if many updates happen simultaneously
                            delay(30) 
                            
                            bitmap = com.madanala.tern.utils.ViewToBitmap.createBitmapFromComposable(
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
                            windGaugeCache.put(cacheKey, bitmap)
                        }
                        
                        // 🚀 PERFORMANCE: reuse Bitmap instance and reset anchor
                        marker.icon = android.graphics.drawable.BitmapDrawable(applicationContext.resources, bitmap)
                        marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
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
    fun checkAndLoadPGSpots(center: GeoPoint, force: Boolean = false) {
        if (!force) {
            val distance = lastLoadPosition?.distanceToAsDouble(center) ?: Double.MAX_VALUE
            if (distance < 0.5) { // 500m threshold for PG spots
                return
            }
        }
        
        if (mapView == null) {
            Log.w(TAG, "No map view available for weather-aware PG spot loading")
            return
        }

        loadingJob?.cancel()

        loadingJob = coroutineScope.launch {
            loadPGSpotsForLocation(applicationContext, center)
        }
        lastLoadPosition = center
    }

    /**
     * CORE PG SPOTS LOADING WITH WEATHER ORCHESTRATION
     */
    private suspend fun loadPGSpotsForLocation(context: Context, center: GeoPoint) {
        try {
            // Geographic determination (same as airspace system)
            // Ignore 0,0 coordinates (default/uninitialized state)
            if (Math.abs(center.latitude) < 0.01 && Math.abs(center.longitude) < 0.01) {
                return
            }

            // Refactor: Use UniversalCountryCacheManager for intelligent multi-country loading
            // This matches the robust implementation in AirspaceOverlayManager
            countryCacheManager?.let { countryCache ->
                // Notify cache manager of location change to trigger downloads if needed
                countryCache.onLocationChanged(center)
                
                // Query multiple countries intelligently using the universal cache manager
                val nearbyFeatures = withContext(Dispatchers.IO) {
                    val allFeatures = countryCache.queryMultiCountryArea(center, 200.0) // 200km radius
                    
                    // Filter for PG spots only (case-insensitive for robustness)
                    allFeatures.filter { it.overlayType.equals("pgspot", ignoreCase = true) }
                }

                Log.d(TAG, "loadPGSpotsForLocation: Query result - PG Spots: ${nearbyFeatures.size}")

                if (nearbyFeatures.isNotEmpty()) {
                    renderPGSpotFeaturesWithWeather(nearbyFeatures, center)
                    launchWeatherFetchingForVisiblePGSpots()
                    lastCheckLocation = center
                } else {
                    Log.i(TAG, String.format("PG spot sync: Desired: 0, Current: %d @ %.4f,%.4f", 
                        currentlyRenderedPGSpots.size, center.latitude, center.longitude))
                }
            } ?: run {
                Log.e(TAG, "Universal country cache manager not available - fallback to legacy loading")
                // Legacy fallback (as a safety measure)
                val countryCode = com.madanala.tern.utils.CountryUtils.getCountryCodeFromGeoPoint(context, center)
                if (countryCode != null) {
                    performLegacyLoad(center, countryCode)
                }
            }




        } catch (e: CancellationException) {
        } catch (e: Exception) {
            Log.e(TAG, "Error loading PG spots data: ${e.message}", e)
        }
    }

    /**
     * Legacy loading fallback for PG spots (single-country only)
     */
    private suspend fun performLegacyLoad(center: GeoPoint, countryCode: String) {
        val nearbyFeatures = withContext(Dispatchers.IO) {
            val cacheKey = countryCode.uppercase()
            if (pgSpotCache.isCached(cacheKey)) {
                pgSpotCache.queryNearbyPGSpots(cacheKey, center, 200.0)
            } else {
                pgSpotCache.getCachedPGSpots(cacheKey)?.filter { feature ->
                    val distance = center.distanceToAsDouble(feature.centroid) / 1000.0
                    distance <= 200.0
                } ?: emptyList()
            }
        }

        if (nearbyFeatures.isNotEmpty()) {
            renderPGSpotFeaturesWithWeather(nearbyFeatures, center)
            launchWeatherFetchingForVisiblePGSpots()
            lastCheckLocation = center
        }
    }

    /**
       * RENDER PG SPOTS WITH WEATHER CAPABILITY
       * Initial static display with future dynamic weather integration
       */
    private suspend fun renderPGSpotFeaturesWithWeather(features: List<OverlayFeature>, center: GeoPoint) {
        Log.d(TAG, "renderPGSpotFeaturesWithWeather called with ${features.size} features")

        // Using passed center directly for consistency
        Log.d(TAG, "renderPGSpotFeaturesWithWeather: center=$center")

        // 🎯 STEP 0: Prioritize features (Zone-based budgeting)
        val prioritizedFeatures = prioritizeFeaturesByZone(
            features,
            center
        ) { it.centroid }

        // 🎯 STEP 1: Determine desired state
        val desiredIds = prioritizedFeatures.map { generatePGSpotId(it) }.toSet()
        val currentIds = currentlyRenderedPGSpots.keys

        val toRemoveIds = currentIds - desiredIds
        val toAddFeatures = prioritizedFeatures.filter { !currentIds.contains(generatePGSpotId(it)) }

        // 🎯 STEP 2: Remove old spots (Main thread)
        withContext(Dispatchers.Main) {
            val sortedRemovals = sortForRemoval(toRemoveIds.toList(), center) { id ->
                currentlyRenderedPGSpots[id]?.center ?: center
            }
            if (sortedRemovals.isNotEmpty()) {
                removePGSpots(sortedRemovals)
            }
        }

        // 🎯 STEP 3: Add new spots (IO thread for creation)
        val sortedAdditions = sortForAddition(toAddFeatures, center) { it.centroid }
        
        if (sortedAdditions.isNotEmpty()) {
            val markersToRender = withContext(Dispatchers.IO) {
                sortedAdditions.mapNotNull { feature ->
                    val spotId = generatePGSpotId(feature)
                    val marker = createPGSpotMarker(feature)
                    val distanceKm = calculateDistanceFromUser(feature.centroid)
                    
                    val pgSpotMarker = PGSpotMarker(
                        marker = marker,
                        feature = feature,
                        weatherLoaded = false,
                        weatherLastUpdated = 0L,
                        center = feature.centroid,
                        distanceFromUser = distanceKm
                    )
                    spotId to pgSpotMarker
                }
            }
            
            // 🎯 STEP 4: Render on Main thread
            withContext(Dispatchers.Main) {
                markersToRender.forEach { pair ->
                    val (spotId, pgSpotMarker) = pair
                    currentlyRenderedPGSpots[spotId] = pgSpotMarker
                    val coordinator = mOverlayCoordinator
                    if (coordinator != null) {
                        coordinator.getAnimationManager().animateOverlayAddition(
                            overlay = pgSpotMarker.marker,
                            overlayId = spotId,
                            mapView = mapView!!,
                            staggerDelay = 0L,
                            type = OverlayType.PG_SPOTS
                        )
                    } else {
                        mapView?.overlays?.add(pgSpotMarker.marker)
                    }
                }
                mapView?.invalidate()
            }
        }

        val centerStr = String.format("@ %.4f,%.4f", center.latitude, center.longitude)



        Log.i(TAG, String.format(
            "PG spots rendered: %d total, %d visible %s",
            prioritizedFeatures.size,
            prioritizedFeatures.size, // Assumed visible

            centerStr
        ))
        
        // Log names for test verification
        if (prioritizedFeatures.isNotEmpty()) {
             val name = prioritizedFeatures.first().feature["properties"]?.let { (it as? Map<*, *>)?.get("name") } ?: "Unknown"
             Log.d(TAG, "Rendered ${prioritizedFeatures.size} PG Spots. First: $name")
        }
    }

    /**
     * Remove PG spots with animation and release to pool
     */
    private fun removePGSpots(ids: List<String>) {
        val coordinator = mOverlayCoordinator
        if (coordinator != null) {
             ids.forEach { id ->
                 currentlyRenderedPGSpots[id]?.let { markerData ->
                     coordinator.removeOverlayFromBatch(markerData.marker, id, markerData.center, OverlayType.PG_SPOTS)
                     // Memory Safety: The actual removal from map happens in coordinator's batch processor,
                     // but we can mark for release or let the coordinator handle release in its batch processor.
                     // For markers, we'll release them when they are actually removed from map in OverlayCoordinator.
                 }
             }
             coordinator.flushPendingRemovals() // Use coordinator directly
             ids.forEach { currentlyRenderedPGSpots.remove(it) }
        } else {
            // Fallback
            ids.forEach { id ->
                currentlyRenderedPGSpots[id]?.let { markerData ->
                    animationManager?.animateOverlayRemoval(markerData.marker, id, mapView!!, OverlayType.PG_SPOTS) {
                        currentlyRenderedPGSpots.remove(id)
                        // No pool available in fallback
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
            val coordinator = mOverlayCoordinator
            if (coordinator != null) {
                // Use coordinator's animation manager for smooth transitions
                coordinator.getAnimationManager().animateOverlayAddition(
                    overlay = marker,
                    overlayId = spotId,
                    mapView = mapView!!,
                    staggerDelay = 0L, // No stagger for immediate processing
                    type = OverlayType.PG_SPOTS
                ) {
                    // Animation completed - overlay is now visible
                }
            } else {
                // Fallback to direct animation manager if coordinator not available
                animationManager?.animateOverlayAddition(
                    overlay = marker,
                    overlayId = spotId,
                    mapView = mapView!!,
                    staggerDelay = 0L,
                    type = OverlayType.PG_SPOTS
                ) {
                    // Animation completed
                } ?: run {
                    Log.w(TAG, "Animation manager missing - adding overlay directly without animation")
                    mapView!!.overlays.add(marker)
                    mapView!!.invalidate()
                }
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
        val map = mapView ?: return Marker(org.osmdroid.views.MapView(applicationContext))

        // 🚀 PERFORMANCE: Acquire marker from universal pool
        return (mOverlayCoordinator?.acquireMarker(map) ?: Marker(map)).apply {
            position = center
            title = "PG Launch Site" // Will be updated with weather info
            snippet = "Tap for weather details"

            // Set custom icon to replace default hand symbol
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            alpha = 1.0f // Ensure reset from pooling

            // PERFORMANCE: Use class-level cache for the static icon to avoid redundant scaling
            if (cachedStaticIcon == null) {
                try {
                    val drawable = ContextCompat.getDrawable(applicationContext, R.mipmap.ic_launcher)
                    drawable?.let {
                        val originalBitmap = it.toBitmap()
                        val scaledWidth = Math.max(1, originalBitmap.width / 2)
                        val scaledHeight = Math.max(1, originalBitmap.height / 2)
                        cachedStaticIcon = originalBitmap.scale(scaledWidth, scaledHeight, true)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load/scale PG spot icon", e)
                }
            }

            cachedStaticIcon?.let {
                icon = BitmapDrawable(applicationContext.resources, it)
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
            launchWeatherFetchingForPGSpots(newlyVisible)
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
        super.onReduxStateChanged(state)
        // Handle weather-related state changes
        if (!isEnabled()) {
            clearOverlays()
            currentCountryCode = null
            lastCheckLocation = null
            visiblePGSpots.clear()
        } else if (mapView != null) {
            // Postpone Redux-triggered overlay operations until GPS fix is available


            val center = mapView?.mapCenter as? GeoPoint
            if (center != null) {
                checkAndLoadPGSpots(center)
            }
        }
    }
    override fun onFocusModeChanged(enabled: Boolean) {
        val targetAlpha = if (enabled) 0.3f else 1.0f
        
        currentlyRenderedPGSpots.values.forEach { pgSpotMarker ->
            pgSpotMarker.marker.alpha = targetAlpha
        }
        mapView?.invalidate()
    }

    /**
     * Handle overlay budget changes with generalized logging
     */
    override fun onOverlayBudgetChanged(budget: com.madanala.tern.utils.OverlayBudget) {
        super.onOverlayBudgetChanged(budget)

        // Get actually visible PG spots (added to map vs just created)
        val actuallyVisibleSpots = currentlyRenderedPGSpots.count { (_, markerData) ->
            mapView?.overlays?.contains(markerData.marker) == true &&
            isPointInBoundingBox(markerData.center, mapView?.boundingBox ?: return@count false)
        }

        // Generic logging for "Overlay Budget" as requested (SSOT)
        Log.i(TAG, "Overlay Budget [PG_SPOT]: ${budget.totalOverlays} total capacity (Created: ${currentlyRenderedPGSpots.size}, Visible: $actuallyVisibleSpots)")
        
        // Let coordinator handle global summary reporting
        mOverlayCoordinator?.reportGlobalOverlayUsage()
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
        val coordinator = mOverlayCoordinator
        var removedCount = 0

        if (coordinator != null) {
            invisibleNonCriticalSpots.forEach { (spotId, pgSpotMarker) ->
                coordinator.removeOverlayFromBatch(
                    overlay = pgSpotMarker.marker,
                    overlayId = spotId,
                    centroid = pgSpotMarker.center,
                    type = OverlayType.PG_SPOTS
                )
                removedCount++
                currentlyRenderedPGSpots.remove(spotId)
            }
            coordinator.flushPendingRemovals() // Use animation manager directly if needed
        } else {
            invisibleNonCriticalSpots.forEach { (spotId, pgSpotMarker) ->
                animationManager?.animateOverlayRemoval(
                    overlay = pgSpotMarker.marker,
                    overlayId = spotId,
                    mapView = mapView!!,
                    type = OverlayType.PG_SPOTS
                ) {
                    currentlyRenderedPGSpots.remove(spotId)
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
        val coordinator = mOverlayCoordinator
        var removedCount = 0

        if (coordinator != null) {
            spotsInZone.forEach { (spotId, pgSpotMarker) ->
                coordinator.removeOverlayFromBatch(
                    overlay = pgSpotMarker.marker,
                    overlayId = spotId,
                    centroid = pgSpotMarker.center,
                    type = OverlayType.PG_SPOTS
                )
                removedCount++
            }
            coordinator.flushPendingRemovals()
        } else {
            spotsInZone.forEach { (spotId, pgSpotMarker) ->
                animationManager?.animateOverlayRemoval(
                    overlay = pgSpotMarker.marker,
                    overlayId = spotId,
                    mapView = mapView!!,
                    type = OverlayType.PG_SPOTS
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
            val coordinator = mOverlayCoordinator
            if (coordinator != null) {
                // Add all overlays to batch for ordered removal (outside to center)
                spotIds.forEach { spotId ->
                    val pgSpotMarker = currentlyRenderedPGSpots[spotId]
                    if (pgSpotMarker != null) {
                        coordinator.removeOverlayFromBatch(
                            overlay = pgSpotMarker.marker,
                            overlayId = spotId,
                            centroid = pgSpotMarker.center,
                            type = OverlayType.PG_SPOTS
                        )
                    }
                }

                // Process the batch for Hilbert-ordered removal
                coordinator.flushPendingRemovals()
            } else {
                // Fallback to direct animation manager if coordinator not available
                spotIds.forEachIndexed { index, spotId ->
                    val pgSpotMarker = currentlyRenderedPGSpots[spotId]
                    if (pgSpotMarker != null) {
                        animationManager?.animateOverlayRemoval(
                            overlay = pgSpotMarker.marker,
                            overlayId = spotId,
                            mapView = map,
                            type = OverlayType.PG_SPOTS
                        ) {
                            // Animation manager handles removal - just update tracking
                        } ?: Log.w(TAG, 
                            "Animation manager is required for PG spot overlay removal. " +
                            "Ensure OverlayCoordinator is properly initialized.")
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
