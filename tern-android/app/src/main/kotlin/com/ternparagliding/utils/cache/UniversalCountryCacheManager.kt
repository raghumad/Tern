package com.ternparagliding.utils.cache
import com.ternparagliding.utils.diagnostics.PerformanceDebugger
import com.ternparagliding.utils.geo.CountryUtils

import android.content.Context
import android.util.Log
import com.ternparagliding.utils.cache.CacheManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.osmdroid.util.GeoPoint

/**
 * Universal Country Cache Manager that serves ALL overlay types.
 *
 * Implements intelligent country management for aviation safety and smooth border transitions,
 * utilizing Geocoder for location detection and caching strategies for performance.
 */
class UniversalCountryCacheManager(
    private val applicationContext: Context,
    private val airspaceCache: AirspaceCache = CacheManager.airspaceCache,
    private val pgSpotCache: PGSpotCache = CacheManager.pgSpotCache
) {

    private val TAG = "UniversalCountryCache"
    private val mutex = Mutex()
    
    // Dedicated dispatcher for country detection to prevent Geocoder-induced 
    // Dispatchers.IO starvation. Geocoder calls are blocking and can be slow; 
    // limiting them to 1 concurrent thread ensures that even during rapid 
    // panning, we don't saturate the global IO pool and starve overlay queries.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val countryDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val managerScope = CoroutineScope(countryDispatcher + SupervisorJob())

    // Smart country tracking - relying on disk state for persistence
    private var lastLocation: GeoPoint? = null
    private var currentCountry: String? = null
    private var locationJob: kotlinx.coroutines.Job? = null
    // LRU access tracking + eviction bookkeeping (extracted, Phase 0c). The
    // owner keeps control of the disk-purge coroutine via the eviction callback.
    private val accessTracker = CountryAccessTracker(MAX_CACHED_COUNTRIES) { country ->
        managerScope.launch { removeCountry(country) }
    }
    private val downloadingCountries = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Aviation-optimized configuration
    companion object {
        const val MAX_CACHED_COUNTRIES = 4
        const val PRELOAD_DISTANCE_KM = 150.0
        const val RETAIN_DISTANCE_KM = 200.0
        private val MAX_ADJACENT_COUNTRIES = 3
        private val SIGNIFICANT_MOVE_KM = 0.5 // 500m
        const val COUNTRY_UNLOAD_DELAY_MS = 10000L
    }

    init {
        Log.d(TAG, "UniversalCountryCacheManager initialized")
    }

    // List of callbacks for when a country is fully loaded
    val onCountryLoadedListeners = mutableListOf<(String) -> Unit>()

    // ==================== CORE COUNTRY MANAGEMENT ====================

    /**
     * Handle location changes with intelligent country management
     * This is the main entry point for all overlay types
     */
    fun onLocationChanged(newLocation: GeoPoint) {
        val normalizedLocation = newLocation.normalizePrecision()
        
        if (!isSignificantLocationChange(normalizedLocation)) {
            return // Too close to last location
        }

        lastLocation = normalizedLocation

        locationJob?.cancel()
        locationJob = managerScope.launch {
            try {
                handleLocationChange(normalizedLocation)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error handling location change for $normalizedLocation", e)
                }
            }
        }
    }

    /**
     * Check if location change is significant enough to process (aviation-appropriate)
     */
    private fun isSignificantLocationChange(newLocation: GeoPoint): Boolean {
        val lastLoc = lastLocation
        if (lastLoc == null) {
            Log.d(TAG, "Significant move: First location detected")
            return true
        }
        
        val distance = lastLoc.distanceToAsDouble(newLocation) / 1000.0 // Convert to km
        val isSignificant = distance > SIGNIFICANT_MOVE_KM
        
        if (!isSignificant) {
            // Log.v(TAG, "Insignificant move: ${String.format("%.3f", distance)} km")
        } else {
            Log.d(TAG, "Significant move: ${String.format("%.3f", distance)} km")
        }
        
        return isSignificant
    }

    /**
     * Main location change handler with smart country management
     */
    private suspend fun handleLocationChange(location: GeoPoint) = kotlinx.coroutines.coroutineScope {
        val newCountry = try {
            getCurrentCountry(location)
        } catch (e: Exception) {
            Log.e(TAG, "Critical error determining country for location: $location", e)
            null
        }

        if (newCountry == null) {
            Log.w(TAG, "Could not determine country for location: $location (likely Geocoder failure)")
            return@coroutineScope
        }

        var countryCrossed = false
        mutex.withLock {
            if (newCountry != currentCountry) {
                Log.i(TAG, "Country change detected: ${currentCountry ?: "NONE"} -> $newCountry at $location")
                handleBorderCrossing(currentCountry, newCountry, location)
                currentCountry = newCountry
                countryCrossed = true
            } else {
                accessTracker.recordAccess(newCountry)
            }
        }

        // Check and preload OUTSIDE the main mutex lock to avoid deadlocks and blocking I/O
        if (!countryCrossed) {
             val needsPreload = withContext(Dispatchers.IO) {
                !airspaceCache.isCached(newCountry) || !pgSpotCache.isCached(newCountry)
            }
            if (needsPreload) {
                Log.w(TAG, "Country $newCountry missing from disk caches. Reloading.")
                preloadCountry(newCountry)
            }
            
            // Aviation Safety: Continuously check for border proximity
            // Launched as a child of this coroutine, so it's cancelled if the parent Job is cancelled.
            launch {
                preloadAdjacentCountries(newCountry, location)
            }
        }
    }

    /**
     * RESET state for test stability
     */
    fun reset() {
        Log.d(TAG, "Resetting UniversalCountryCacheManager state")
        
        // Cancel all ongoing background downloads and location monitoring jobs
        locationJob?.cancel()
        locationJob = null
        
        // Actively cancel children to prevent overlapping UI test suites from writing
        // to the same .flex and .idx files simultaneously, causing silent cache corruption.
        // We do not cancel the entire scope to allow the SupervisorJob to survive.
        managerScope.coroutineContext.cancelChildren()
        
        lastLocation = null
        currentCountry = null
        downloadingCountries.clear()
        accessTracker.clear()
        Log.d(TAG, "UniversalCountryCacheManager reset complete")
    }

    /**
     * Handle border crossing with smooth transition logic for all overlay types
     */
    private fun handleBorderCrossing(fromCountry: String?, toCountry: String, location: GeoPoint) {
        Log.d(TAG, "Border crossing: $fromCountry → $toCountry")

        // Record border crossing for performance monitoring
        recordBorderTransition(fromCountry ?: "UNKNOWN", toCountry, true)

        // For aviation safety: preload adjacent countries BEFORE clearing old ones
        managerScope.launch {
            // Ensure current country is cached first
            val isCached = withContext(Dispatchers.IO) {
                airspaceCache.isCached(toCountry) && pgSpotCache.isCached(toCountry)
            }
            if (!isCached) {
                preloadCountry(toCountry)
            }
            preloadAdjacentCountries(toCountry, location)
        }

        // Schedule old country cleanup with delay (allows smooth transition)
        fromCountry?.let { oldCountry ->
            scheduleCountryCleanup(oldCountry, location)
        }
    }

    /**
     * Preload countries adjacent to the current location for all overlay types
     */
    private suspend fun preloadAdjacentCountries(currentCountry: String, location: GeoPoint) {
        try {
            // Get adjacent countries based on current location and flight path
            val adjacentCountries = getAdjacentCountries(currentCountry, location)

            // Aviation safety: limit adjacent countries to prevent memory pressure
            val countriesToLoad = adjacentCountries.take(MAX_ADJACENT_COUNTRIES)
            
            countriesToLoad.forEach { countryCode ->
                val isCached = withContext(Dispatchers.IO) {
                    airspaceCache.isCached(countryCode) && pgSpotCache.isCached(countryCode)
                }
                if (!isCached) {
                    preloadCountry(countryCode)
                }
            }

            Log.d(TAG, "Preloaded adjacent countries for all overlay types: $countriesToLoad")
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading adjacent countries", e)
        }
    }

    /**
     * Schedule country cleanup with delay for smooth transitions
     */
    private fun scheduleCountryCleanup(country: String, currentLocation: GeoPoint) {
        managerScope.launch {
            // Wait before cleanup to allow smooth transition (10 seconds)
            delay(COUNTRY_UNLOAD_DELAY_MS)

            // Double-check if country is still not needed (distance-based retention)
            val distance = calculateDistanceFromCurrentLocation(country, currentLocation)
            if (distance > RETAIN_DISTANCE_KM) {
                removeCountry(country)
            }
        }
    }

    /**
     * Preload a single country (simplified for working demo)
     */
    internal suspend fun preloadCountry(country: String) {
        if (!downloadingCountries.add(country)) {
            Log.d(TAG, "Country $country is already being preloaded. Skipping.")
            return
        }
        try {
            Log.d(TAG, "Preloading country for all overlay types: $country")
            
            // No longer using in-memory 'cachedCountries' set - strictly relying on disk state
            // to prevent synchronization issues after cache wipes.

            // 1. Download PG Spots (delegated to cache)
            Log.d(TAG, "Triggering PG spot download for $country")
            pgSpotCache.downloadAndCache(country)

            // 2. Download Airspaces (delegated to cache)
            Log.d(TAG, "Triggering Airspace download for $country")
            airspaceCache.downloadAndCache(country)

            Log.d(TAG, "Country fully cached for all overlay types: $country")
            
            // Notify listeners that country data is ready
            withContext(Dispatchers.Main) {
                onCountryLoadedListeners.forEach { it.invoke(country) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading country: $country", e)
        } finally {
            downloadingCountries.remove(country)
        }
    }

    /**
     * Check if a country is currently being preloaded (downloading)
     */
    fun isCountryDownloading(country: String): Boolean {
        return downloadingCountries.contains(country)
    }

    /**
     * Remove country from cache (purges from disk)
     */
    private suspend fun removeCountry(country: String) {
        withContext(Dispatchers.IO) {
            airspaceCache.clearCacheForRegion(country)
            pgSpotCache.clearCacheForRegion(country)
        }
        Log.d(TAG, "Removed country from cache: $country")
    }

    // ==================== UTILITY FUNCTIONS ====================

    /**
     * Get current country code for location using CountryUtils (supports test overrides)
     */
    private fun getCurrentCountry(location: GeoPoint): String? {
        // Use CountryUtils to ensure we respect test overrides and share geocoding logic
        // Convert country code to uppercase for internal consistency if needed, 
        // though CountryUtils returns lowercase by default. 
        // Our cache usually uses uppercase (e.g. "US"), but let's check assumptions.
        // MockServer expects "us" (lowercase) in query param, but "US" (uppercase) might be used in cache keys.
        // Let's normalize to uppercase for cache keys, as 'adjacencyMap' uses uppercase.
        val code = CountryUtils.getCountryCodeFromGeoPoint(applicationContext, location)
        return code?.uppercase()
    }

    /**
     * Get adjacent countries for preloading using spatial geocoding scans
     */
    private suspend fun getAdjacentCountries(currentCountry: String, location: GeoPoint): List<String> {
        // Use the limited dispatcher for Geocoder scans to prevent IO starvation
        return withContext(countryDispatcher) {
            try {
                // Dynamically scan for nearby country codes within 50km
                val nearbyCodes = CountryUtils.getNearbyCountryCodes(
                    applicationContext,
                    location.latitude,
                    location.longitude,
                    50.0
                )
                
                // uppercase to match our cache consistency and filter out the current country
                nearbyCodes
                    .map { it.uppercase() }
                    .filter { it != currentCountry && it.isNotBlank() }
                    .distinct()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dynamically resolve adjacent countries", e)
                emptyList()
            }
        }
    }

    /**
     * Calculate distance from current location to country
     */
    private fun calculateDistanceFromCurrentLocation(country: String, location: GeoPoint): Double {
        // In a full implementation, this would calculate distance to the country's border polygon.
        // For now, we return a safe default to prevent aggressive unloading.
        return 0.0 
    }

    // ==================== PUBLIC API FOR ALL OVERLAY TYPES ====================

    /**
     * Get currently cached countries from disk index
     */
    fun getCachedCountries(): Set<String> {
        val result = mutableSetOf<String>()
        
        // Aggregate from all internal caches using their specific renamed keys
        try {
            val aStats = airspaceCache.getCacheStats()
            @Suppress("UNCHECKED_CAST")
            val aRegions = aStats["airspaceCachedRegions"] as? List<String> ?: emptyList()
            result.addAll(aRegions)
            
            val pStats = pgSpotCache.getCacheStats()
            @Suppress("UNCHECKED_CAST")
            val pRegions = pStats["pgSpotsCachedRegions"] as? List<String> ?: emptyList()
            result.addAll(pRegions)
            
            if (result.isNotEmpty()) {
                Log.d(TAG, "getCachedCountries: Found ${result.size} cached regions: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error aggregating cached countries", e)
        }
        
        return result
    }
    
    /**
     * Check if country is cached on disk
     */
    fun isCountryCached(country: String): Boolean = airspaceCache.isCached(country)

    /**
     * Query features from multiple countries using spatial indexing with Lazy Hydration
     * @param center Current map center
     * @param radiusKm Search radius
     * @param limit Maximum number of features to hydrate (Budget-aware)
     */
    suspend fun queryMultiCountryArea(
        center: GeoPoint,
        radiusKm: Double,
        limit: Int = 1000
    ): List<com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature> = kotlinx.coroutines.coroutineScope {
        val normalizedCenter = center.normalizePrecision()
        
        // Dynamically resolve nearby countries from coordinates (Stateless Principle)
        // Use the limited dispatcher for Geocoder scans to prevent IO starvation
        val countriesToQuery = withContext(countryDispatcher) {
            CountryUtils.getNearbyCountryCodes(applicationContext, center.latitude, center.longitude, radiusKm)
                .map { it.uppercase() }
                .distinct()
        }

        if (countriesToQuery.isEmpty()) {
            Log.w(TAG, "queryMultiCountryArea: No nearby countries resolved from Geocoder at $normalizedCenter")
            return@coroutineScope emptyList()
        }

        Log.d(TAG, "queryMultiCountryArea: Querying ${countriesToQuery.size} nearby countries ($countriesToQuery) at $normalizedCenter")

        val deferredResults = countriesToQuery.map { countryCode ->
            async(Dispatchers.IO) {
                try {
                    val features = queryCountryFeatures(normalizedCenter, countryCode, radiusKm, limit)
                    if (features.isEmpty()) {
                        Log.v(TAG, "queryMultiCountryArea: Country $countryCode returned 0 features at $normalizedCenter")
                    } else {
                        Log.d(TAG, "queryMultiCountryArea: Country $countryCode returned ${features.size} features")
                    }
                    features
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying country $countryCode", e)
                    emptyList()
                }
            }
        }

        val allFeatures = deferredResults.awaitAll().flatten()
        if (allFeatures.isNotEmpty()) {
            Log.d(TAG, "Total features from ${countriesToQuery.size} countries: ${allFeatures.size}")
        }
        
        // Final capping across all countries combined
        if (allFeatures.size > limit) allFeatures.take(limit) else allFeatures
    }

    /**
     * Query features from a specific country using existing cache infrastructure
     */
    private suspend fun queryCountryFeatures(
        center: GeoPoint,
        countryCode: String,
        radiusKm: Double,
        limit: Int
    ): List<com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature> {
        val features = mutableListOf<com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature>()
        
        try {
            // 1. Query Airspaces
            if (airspaceCache.isCached(countryCode)) {
                // queryNearbyFeatures takes miles. radiusKm is in km.
                val radiusMiles = radiusKm * 0.621371
                features.addAll(airspaceCache.queryNearbyFeatures(countryCode, center, radiusMiles, limit))
            }

            // 2. Query PG Spots
            if (pgSpotCache.isCached(countryCode)) {
                // Use 200.0 miles (approx 320km) or convert radiusKm to miles. 
                // queryNearbyPGSpots takes miles. radiusKm is in km.
                val radiusMiles = radiusKm * 0.621371
                features.addAll(pgSpotCache.queryNearbyPGSpots(countryCode, center, radiusMiles, limit))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying features for country $countryCode", e)
        }
        
        return features
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): Map<String, Any> {
        val cachedCount = getCachedCountries().size
        return mapOf(
            "cached_countries" to cachedCount,
            "max_countries" to MAX_CACHED_COUNTRIES,
            "current_country" to (currentCountry ?: "unknown"),
            "cache_utilization" to (cachedCount * 100) / MAX_CACHED_COUNTRIES
        ) + accessTracker.statsFragment()
    }

    /**
     * Force refresh of country data (used by all overlay managers)
     */
    fun refreshCountryData(location: GeoPoint) {
        managerScope.launch {
            handleLocationChange(location)
        }
    }

    // ==================== PERFORMANCE MONITORING INTEGRATION ====================

    private fun recordBorderTransition(fromCountry: String, toCountry: String, isSmooth: Boolean) {
        // Integration with PerformanceDebugger (debug only)
        try {
            // PerformanceDebugger.recordBorderCrossing(fromCountry, toCountry)
            if (isSmooth) {
                // PerformanceDebugger.recordSmoothTransition()
            } else {
                // PerformanceDebugger.recordVisualDiscontinuity()
            }
        } catch (e: Exception) {
            // Silently handle - performance monitoring is debug-only
        }
    }

    // ==================== CLEANUP ====================

    /**
     * Clean shutdown of cache manager
     */
    fun shutdown() {
        managerScope.cancel()
        accessTracker.clear()
        currentCountry = null
        lastLocation = null
        Log.d(TAG, "UniversalCountryCacheManager shutdown complete")
    }
}