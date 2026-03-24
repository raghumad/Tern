package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.madanala.tern.utils.CacheManager
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
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Smart country tracking - relying on disk state for persistence
    private var lastLocation: GeoPoint? = null
    private var currentCountry: String? = null
    private var locationJob: kotlinx.coroutines.Job? = null
    // Enhanced access tracking for intelligent cache management
    private val countryAccessMetadata = mutableMapOf<String, CountryAccessMetadata>()
    private val accessOrderedCountries = LinkedHashSet<String>() // LRU ordering for cache eviction
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

    /**
     * Data class for tracking country access metadata for intelligent cache management.
     * Enables LRU-style eviction and performance monitoring for aviation safety.
     */
    private data class CountryAccessMetadata(
        var firstAccessTime: Long,
        var lastAccessTime: Long,
        var accessCount: Int,
        var totalAccessTime: Long
    )

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

        coroutineScope.launch {
            try {
                handleLocationChange(normalizedLocation)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling location change for $normalizedLocation", e)
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
    private suspend fun handleLocationChange(location: GeoPoint) {
        val newCountry = try {
            getCurrentCountry(location)
        } catch (e: Exception) {
            Log.e(TAG, "Critical error determining country for location: $location", e)
            null
        }

        if (newCountry == null) {
            Log.w(TAG, "Could not determine country for location: $location (likely Geocoder failure)")
            return
        }

        var countryCrossed = false
        mutex.withLock {
            if (newCountry != currentCountry) {
                Log.i(TAG, "Country change detected: ${currentCountry ?: "NONE"} -> $newCountry at $location")
                handleBorderCrossing(currentCountry, newCountry, location)
                currentCountry = newCountry
                countryCrossed = true
            } else {
                updateCountryAccess(newCountry)
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
            coroutineScope.launch {
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
        coroutineScope.coroutineContext.cancelChildren()
        
        lastLocation = null
        currentCountry = null
        downloadingCountries.clear()
        
        synchronized(countryAccessMetadata) {
            countryAccessMetadata.clear()
        }
        synchronized(accessOrderedCountries) {
            accessOrderedCountries.clear()
        }
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
        coroutineScope.launch {
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
        coroutineScope.launch {
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

    /**
     * Update country access time with intelligent cache management.
     *
     * This method implements LRU-style access tracking for aviation-optimized
     * country caching. It maintains access metadata for performance monitoring
     * and enables smart cache eviction based on usage patterns.
     *
     * @param country The ISO country code to update access for
     * @return true if access was recorded successfully, false if country is invalid
     */
    private fun updateCountryAccess(country: String): Boolean {
        // Input validation - early return for invalid inputs
        if (country.isBlank()) {
            Log.w(TAG, "Attempted to update access for blank country code")
            return false
        }

        if (country.length != 2 && country != "TEST") {
            Log.w(TAG, "Invalid country code format (expected 2 chars or 'TEST'): $country")
            return false
        }

        return try {
            updateCountryAccessInternal(country)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating access for country: $country", e)
            false
        }
    }

    /**
     * Internal access update logic with full cache management functionality.
     * Protected by mutex lock for thread safety.
     */
    private fun updateCountryAccessInternal(country: String): Boolean {
        val currentTime = System.currentTimeMillis()

        // Initialize or update country metadata
        val metadata = countryAccessMetadata.getOrPut(country) {
            CountryAccessMetadata(
                firstAccessTime = currentTime,
                lastAccessTime = currentTime,
                accessCount = 0,
                totalAccessTime = 0L
            )
        }

        // Update access statistics
        metadata.apply {
            lastAccessTime = currentTime
            accessCount++
            totalAccessTime += currentTime - (this.lastAccessTime - (currentTime - lastAccessTime))
        }

        // Record performance metrics for aviation safety monitoring
        recordCountryAccess(country, metadata.accessCount)

        // Implement LRU-style management: move to end of access order
        accessOrderedCountries.remove(country)
        accessOrderedCountries.add(country)

        // Smart cache size management - evict least recently used if needed
        if (accessOrderedCountries.size > MAX_CACHED_COUNTRIES) {
            evictLeastRecentlyUsedCountry()
        }

        Log.v(TAG, "Updated access for country: $country (access count: ${metadata.accessCount})")
        return true
    }

    /**
     * Evict the least recently used country when cache is full.
     * This implements intelligent cache management for aviation performance.
     */
    private fun evictLeastRecentlyUsedCountry() {
        if (accessOrderedCountries.isEmpty()) return

        val iterator = accessOrderedCountries.iterator()
        val lruCountry = iterator.next()
        iterator.remove()
        countryAccessMetadata.remove(lruCountry)?.let { metadata ->
            Log.d(TAG, "Evicted LRU country: $lruCountry " +
                  "(accessed ${metadata.accessCount} times, last: ${metadata.lastAccessTime})")

            // Record eviction for performance monitoring
            recordCacheEviction(lruCountry, metadata.accessCount)
        }

        // Remove from main cache (disk purged through removeCountry)
        coroutineScope.launch {
            removeCountry(lruCountry)
        }
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
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
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
    ): List<com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature> = coroutineScope {
        val normalizedCenter = center.normalizePrecision()
        
        // Dynamically resolve nearby countries from coordinates (Stateless Principle)
        val countriesToQuery = withContext(Dispatchers.IO) {
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
            this@UniversalCountryCacheManager.coroutineScope.async(Dispatchers.IO) {
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
    ): List<com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature> {
        val features = mutableListOf<com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature>()
        
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
        val totalAccesses = countryAccessMetadata.values.sumOf { it.accessCount }
        val mostAccessed = countryAccessMetadata.maxByOrNull { it.value.accessCount }
        val avgAccessTime = if (totalAccesses > 0) {
            countryAccessMetadata.values.sumOf { it.totalAccessTime } / totalAccesses
        } else 0L

        val cachedCount = getCachedCountries().size
        return mapOf(
            "cached_countries" to cachedCount,
            "max_countries" to MAX_CACHED_COUNTRIES,
            "current_country" to (currentCountry ?: "unknown"),
            "cache_utilization" to (cachedCount * 100) / MAX_CACHED_COUNTRIES,
            "total_country_accesses" to totalAccesses,
            "countries_with_metadata" to countryAccessMetadata.size,
            "most_accessed_country" to (mostAccessed?.key ?: "none"),
            "most_accessed_count" to (mostAccessed?.value?.accessCount ?: 0),
            "average_access_time_ms" to avgAccessTime,
            "lru_order_size" to accessOrderedCountries.size
        )
    }

    /**
     * Force refresh of country data (used by all overlay managers)
     */
    fun refreshCountryData(location: GeoPoint) {
        coroutineScope.launch {
            handleLocationChange(location)
        }
    }

    // ==================== PERFORMANCE MONITORING INTEGRATION ====================

    private fun recordStateUpdate() {
        // Integration with PerformanceDebugger (debug only)
        try {
            PerformanceDebugger.recordStateUpdate(1)
        } catch (e: Exception) {
            // Silently handle - performance monitoring is debug-only
        }
    }

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

    /**
     * Record country access or cache eviction for performance monitoring and optimization analysis.
     *
     * @param country The ISO country code being recorded
     * @param accessCount The access count for the country (use current metadata for accuracy)
     * @param eventType Whether this is an access or eviction event
     */
    private fun recordCountryEvent(country: String, accessCount: Int, eventType: CountryEventType) {
        // Input validation
        if (country.isBlank()) {
            Log.w(TAG, "Attempted to record ${eventType.name.lowercase()} for blank country code")
            return
        }

        if (country.length != 2 && country != "TEST") {
            Log.w(TAG, "Invalid country code format for ${eventType.name.lowercase()}: $country (expected 2 chars or 'TEST')")
            return
        }

        if (accessCount < 0) {
            Log.w(TAG, "Invalid negative access count for ${eventType.name.lowercase()}: $accessCount")
            return
        }

        try {
            // Record performance metrics with appropriate delta
            val stateDelta = if (eventType == CountryEventType.EVICTION) -1 else 1
            PerformanceDebugger.recordStateUpdate(stateDelta)

            // Use lazy string evaluation for better performance when logging is disabled
            val logMessage = {
                when (eventType) {
                    CountryEventType.ACCESS ->
                        "Country access recorded: $country (access count: $accessCount)"
                    CountryEventType.EVICTION ->
                        "Cache eviction recorded: $country (was accessed $accessCount times)"
                }
            }

            // Consistent logging level - use DEBUG for both events
            Log.d(TAG, logMessage())
        } catch (e: IllegalStateException) {
            // Handle PerformanceDebugger being in an invalid state
            Log.w(TAG, "PerformanceDebugger state error during ${eventType.name.lowercase()}: ${e.message}")
        } catch (e: OutOfMemoryError) {
            // Handle memory issues specifically - don't let monitoring cause crashes
            Log.e(TAG, "Memory error during ${eventType.name.lowercase()} monitoring: ${e.message}")
        } catch (e: Exception) {
            // Log unexpected errors for debugging but don't crash
            Log.e(TAG, "Unexpected error during ${eventType.name.lowercase()} monitoring for $country", e)
        }
    }

    /**
     * Types of country cache events for performance monitoring.
     */
    private enum class CountryEventType {
        ACCESS, EVICTION
    }

    /**
     * Record country access for performance monitoring and cache optimization.
     * @param country The ISO country code that was accessed
     * @param accessCount The current access count for the country
     */
    private fun recordCountryAccess(country: String, accessCount: Int) {
        recordCountryEvent(country, accessCount, CountryEventType.ACCESS)
    }

    /**
     * Record cache eviction for performance monitoring and optimization analysis.
     * @param country The ISO country code being evicted
     * @param accessCount The access count for the country at time of eviction
     */
    private fun recordCacheEviction(country: String, accessCount: Int) {
        recordCountryEvent(country, accessCount, CountryEventType.EVICTION)
    }

    // ==================== CLEANUP ====================

    /**
     * Clean shutdown of cache manager
     */
    fun shutdown() {
        coroutineScope.cancel()
        countryAccessMetadata.clear()
        accessOrderedCountries.clear()
        currentCountry = null
        lastLocation = null
        Log.d(TAG, "UniversalCountryCacheManager shutdown complete")
    }
}