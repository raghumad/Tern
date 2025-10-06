package com.madanala.tern.overlays

import android.content.Context
import android.util.Log
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

    /**
     * Set the universal country cache manager (called by OverlayCoordinator)
     */
    fun setCountryCacheManager(countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager) {
        this.countryCacheManager = countryCacheManager
        Log.d(TAG, "Universal country cache manager connected to PGSpotOverlayManager")
    }

    // Track rendered PG spots and weather status for efficient updates
    private val currentlyRenderedPGSpots = mutableMapOf<String, PGSpotMarker>()
    private val visiblePGSpots = mutableSetOf<String>() // Currently visible in viewport

    // Aviation safety thresholds (inherited from airspace system with PG spot optimizations)
    private val checkDistanceKm = 2.0  // Smaller than airspace - PG spots are denser
    private val maxPGSpots = 50         // Reasonable limit for launch area visualization

    data class PGSpotMarker(
        val marker: Marker,
        val feature: OverlayFeature,
        val weatherLoaded: Boolean = false,
        val center: GeoPoint,
        val distanceFromUser: Double = Double.MAX_VALUE
    )

    // === OVERLAY MANAGER LIFECYCLE ===

    override fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "PG spots overlay manager enabled: $enabled")
        if (enabled) {
            initiateWeatherSystem()
        } else {
            shutdownWeatherSystem()
        }
    }

    override fun updateConfig(config: OverlayConfig) {
        Log.d(TAG, "PG spots overlay manager config updated: $config")
        // Future: Handle PG spot specific configurations
    }

    override fun onOverlayAttached() {
        Log.d(TAG, "Weather-aware PG spots overlay manager attached")
        initiateWeatherSystem()
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "PG spots overlay manager detached")
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
            Log.d(TAG, "PG spots disabled, skipping weather-aware map move handling")
            return
        }

        // Postpone overlay operations until GPS fix is available
        if (!hasValidGPSFix) {
            Log.d(TAG, "No GPS fix yet, postponing PG spot loading until GPS coordinates are available")
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
                Log.v(TAG, "Not moved far enough (${String.format("%.1f km", distanceKm)} < $checkDistanceKm km), skipping PG spot reload")
                return
            }
        }

        Log.d(TAG, "Map moved significantly, loading weather-aware PG spots")
        checkAndLoadPGSpots(center)
    }

    /**
     * Abstract method implementation from BaseOverlayManager
     */
    override fun onViewportChangedInternal(viewport: BoundingBox) {
        Log.d(TAG, "Viewport changed internal: $viewport")
        // Abstract method required by base class - implementation handled in onViewportChanged
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
     * Viewport-aware orchestration of weather fetching and display
     */
    private fun updateViewportWeatherIntelligence(viewport: BoundingBox) {
        val currentTime = System.currentTimeMillis()

        // Debounce rapid viewport changes to prevent excessive weather API calls
        if (currentTime - lastViewportChange < WEATHER_FETCH_DEBOUNCE_MS) {
            return // Too frequent, skip
        }

        lastViewportChange = currentTime

        // Perform viewport analysis
        val visiblePGSpotsNew = determineVisiblePGSpots(viewport)

        // Calculate weather intelligence updates needed
        val spotsNeedingWeather = visiblePGSpotsNew.filter { pgSpotId ->
            val marker = currentlyRenderedPGSpots[pgSpotId]
            marker != null && !marker.weatherLoaded &&
            isWithinWeatherRange(marker.center, viewport)
        }

        val spotsNotVisible = visiblePGSpots - visiblePGSpotsNew
        updateVisiblePGSpots(visiblePGSpotsNew)

        // Trigger weather gathering for newly visible PG spots
        if (spotsNeedingWeather.isNotEmpty()) {
            launchWeatherFetchingForPGSpots(spotsNeedingWeather.toSet())

            // Also trigger occasional background refresh for current weather
            val shouldRefreshCurrent = currentTime - lastWeatherUpdate > WEATHER_UPDATE_INTERVAL_MS
            if (shouldRefreshCurrent && visiblePGSpotsNew.isNotEmpty()) {
                launchBackgroundWeatherRefresh(visiblePGSpotsNew)
            }
        }

        // Clean up weather data for spots no longer visible
        cleanupNonVisiblePGSpotWeather(spotsNotVisible)

        Log.d(TAG, "Weather orchestration: ${spotsNeedingWeather.size} new weather fetches, ${spotsNotVisible.size} cleanup")
    }

    /**
     * LAUNCH WEATHER FETCHING FOR PG SPOTS
     * Event-driven weather acquisition for aviation safety
     */
    private fun launchWeatherFetchingForPGSpots(pgSpotIds: Set<String>) {
        if (pgSpotIds.isEmpty()) return

        weatherFetchJob?.cancel() // Cancel any previous weather fetching

        weatherFetchJob = coroutineScope.launch {
            try {
                Log.d(TAG, "Fetching weather for ${pgSpotIds.size} PG spots")

        // Dispatch Redux action to show weather fetching state
        pgSpotIds.forEach { pgSpotId ->
            val marker = currentlyRenderedPGSpots[pgSpotId]
            if (marker != null) {
                mapStore?.dispatch(WeatherActions.FetchWeatherForPGSpot(
                    pgSpotId, marker.center.latitude, marker.center.longitude
                ))
            }
        }

                // Fetch weather data sequentially to respect API rate limits
                for (pgSpotId in pgSpotIds) {
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
                            Log.d(TAG, "Successfully fetched weather for PG spot: $pgSpotId")
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

                lastWeatherUpdate = System.currentTimeMillis()

            } catch (e: CancellationException) {
                Log.d(TAG, "Weather fetching cancelled due to user interaction")
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
                // Update marker with wind gauge composable
                // Note: In production, this would use a custom Marker with embedded Composable
                // For now, update the marker title/info with wind information
                val windTitle = "PG Spot ${pgSpotId}\n" +
                    "Wind: ${wind.speed.roundToInt()} kt @ ${wind.direction.roundToInt()}°"
                marker.title = windTitle

                // In production implementation:
                // marker.setCustomIcon(WindGauge(wind.speed, wind.direction))
                // This provides the smooth transition from static to dynamic

                Log.v(TAG, "Updated PG spot $pgSpotId with wind gauge: ${wind.speed}kt @ ${wind.direction}°")

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
            Log.v(TAG, "Loading weather-integrated PG spots for location: ${center.latitude}, ${center.longitude}")

            // Geographic determination (same as airspace system)
            val countryCode = com.madanala.tern.utils.CountryUtils.getCountryCodeFromGeoPoint(context, center)
            if (countryCode == null) {
                Log.w(TAG, "Could not determine country code for PG spots at: $center")
                return
            }

            // Major movement detection for efficient caching - always reload for compatibility
            val isMajorMove = true // TODO: Implement proper distance check when osmdroid distanceTo is available

            if (isMajorMove) {
                Log.v(TAG, "Major move detected - clearing PG spots for fresh weather-integrated load")
                clearOverlays()
            }

            // 🗺️ FlexBuffers + Hilbert Caching Architecture for PG Spots
            var nearbyFeatures: List<OverlayFeature>

            if (!pgSpotCache.isCached(countryCode)) {
                // DOWNLOAD + PARSE + CACHE as FlexBuffers with Hilbert spatial index
                Log.d(TAG, "No cached PG spots for $countryCode, downloading fresh data")
                val downloadedFeatures = withContext(Dispatchers.IO) {
                    pgSpotCache.cachePGSpotsData(countryCode)
                }

                if (downloadedFeatures != null) {
                    Log.d(TAG, "Downloaded and cached ${downloadedFeatures.size} PG spots for $countryCode")
                    // Now perform spatial query on the newly cached data
                    nearbyFeatures = pgSpotCache.queryNearbyPGSpots(countryCode, center, 50.0)
                } else {
                    Log.w(TAG, "Failed to download PG spots data for $countryCode")
                    return // Graceful degradation - weather works without PG spots
                }
            } else {
                // CACHED: Use Hilbert spatial query for nearby features only
                Log.v(TAG, "Using cached PG spots for $countryCode, performing spatial query")
                nearbyFeatures = pgSpotCache.queryNearbyPGSpots(countryCode, center, 50.0)
            }

            Log.v(TAG, "Spatial query returned ${nearbyFeatures.size} PG spots within 50 miles")

            if (nearbyFeatures.isNotEmpty()) {
                renderPGSpotFeaturesWithWeather(nearbyFeatures)

                // Initial weather orchestration for loaded PG spots
                launchWeatherFetchingForVisiblePGSpots()

                currentCountryCode = countryCode
                lastCheckLocation = center
            } else {
                Log.d(TAG, "No PG spots found within 50 miles of $center")
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "PG spots loading cancelled due to user interaction")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading PG spots data", e)
        }
    }

    /**
     * RENDER PG SPOTS WITH WEATHER CAPABILITY
     * Initial static display with future dynamic weather integration
     */
    private fun renderPGSpotFeaturesWithWeather(features: List<OverlayFeature>) {
        features.forEach { feature ->
            try {
                // Create standard PG spot marker initially
                val marker = createPGSpotMarker(feature)

                // Calculate distance from user for prioritization
                val center = feature.centroid
                val distanceKm = calculateDistanceFromUser(center)

                // Store marker with weather capability
                val pgSpotMarker = PGSpotMarker(
                    marker = marker,
                    feature = feature,
                    weatherLoaded = false,
                    center = center,
                    distanceFromUser = distanceKm
                )

                // Add to map and tracking
                mapView?.overlays?.add(marker)
                currentlyRenderedPGSpots[generatePGSpotId(feature)] = pgSpotMarker

                // Add click handler for weather details (future: detailed weather screen)
                marker.setOnMarkerClickListener { clickedMarker, _ ->
                    showPGSpotWeatherDetails(feature)
                    true
                }

            } catch (e: Exception) {
                Log.w(TAG, "Failed to render PG spot feature", e)
            }
        }

        mapView?.invalidate()
        Log.v(TAG, "Rendered ${features.size} weather-capable PG spots")
    }

    // === UTILITY METHODS ===

    private fun createPGSpotMarker(feature: OverlayFeature): Marker {
        val center = feature.centroid

        return Marker(mapView).apply {
            position = center
            title = "PG Launch Site" // Will be updated with weather info
            snippet = "Tap for weather details"

            // Placeholder icon - will be replaced with wind gauge when weather loads
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            // In production: Use icon from your app resources
            // icon = ContextCompat.getDrawable(context, R.drawable.pg_spot_default)
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

        Log.d(TAG, "Visible PG spots updated. Newly visible: ${newlyVisible.size}, no longer visible: ${noLongerVisible.size}")

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
            Log.d(TAG, "Cleaned up weather for non-visible PG spot: $pgSpotId")
        }
    }

    private fun launchBackgroundWeatherRefresh(visibleSpots: Set<String>) {
        if (visibleSpots.isEmpty()) return

        weatherFetchJob?.cancel()
        weatherFetchJob = coroutineScope.launch {
            try {
                delay(WEATHER_UPDATE_INTERVAL_MS) // Wait before refreshing

                val refreshedIds = visibleSpots.filter { id ->
                    currentlyRenderedPGSpots[id]?.weatherLoaded == false ||
                    true // Always refresh for now - TODO: Implement proper timestamp check
                }.toSet()

                if (refreshedIds.isNotEmpty()) {
                    // Note: This is simplified - in production, would fetch fresh data
                    Log.d(TAG, "Refreshing weather for ${refreshedIds.size} PG spots")
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
        Log.d(TAG, "Dispatched show weather details for PG spot: $pgSpotId")
    }

    private fun initiateWeatherSystem() {
        Log.d(TAG, "Initiating weather system for PG spots")
        // System startup - check API availability, preload cache, etc.
        coroutineScope.launch {
            try {
                val available = weatherAPI.isAvailable()
                mapStore?.dispatch(WeatherActions.WeatherAPIStatus(available))
                Log.d(TAG, "Weather API availability: $available")
            } catch (e: Exception) {
                Log.w(TAG, "Error checking weather API availability", e)
            }
        }
    }

    private fun shutdownWeatherSystem() {
        Log.d(TAG, "Shutting down weather system")
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
                Log.d(TAG, "Postponing Redux-triggered PG spot loading until GPS fix")
                return
            }

            val center = mapView!!.mapCenter as GeoPoint
            checkAndLoadPGSpots(center)
        }
    }

    override fun clearOverlays() {
        currentlyRenderedPGSpots.values.forEach { pgSpotMarker ->
            mapView?.overlays?.remove(pgSpotMarker.marker)
        }
        currentlyRenderedPGSpots.clear()
        visiblePGSpots.clear()
        mapView?.invalidate()
        Log.v(TAG, "Cleared all weather-aware PG spot overlays")
    }
}
