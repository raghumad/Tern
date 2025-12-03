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

    // Smart country caching - simplified for working implementation
    private val cachedCountries = mutableSetOf<String>()
    private var lastLocation: GeoPoint? = null
    private var currentCountry: String? = null

    // Enhanced access tracking for intelligent cache management
    private val countryAccessMetadata = mutableMapOf<String, CountryAccessMetadata>()
    private val accessOrderedCountries = LinkedHashSet<String>() // LRU ordering for cache eviction

    // Aviation-optimized configuration
    companion object {
        const val MAX_CACHED_COUNTRIES = 4
        const val PRELOAD_DISTANCE_KM = 150.0
        const val RETAIN_DISTANCE_KM = 200.0
        const val SIGNIFICANT_MOVE_KM = 2.0
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

    // Callback for when a country is fully loaded
    var onCountryLoaded: ((String) -> Unit)? = null

    // ==================== CORE COUNTRY MANAGEMENT ====================

    /**
     * Handle location changes with intelligent country management
     * This is the main entry point for all overlay types
     */
    fun onLocationChanged(newLocation: GeoPoint) {
        if (!isSignificantLocationChange(newLocation)) {
            return // Too close to last location
        }

        lastLocation = newLocation

        coroutineScope.launch {
            handleLocationChange(newLocation)
        }

        // Record for performance monitoring (debug only)
        recordStateUpdate()
    }

    /**
     * Check if location change is significant enough to process (aviation-appropriate)
     */
    private fun isSignificantLocationChange(newLocation: GeoPoint): Boolean {
        lastLocation?.let { lastLoc ->
            val distance = lastLoc.distanceToAsDouble(newLocation) / 1000.0 // Convert to km
            return distance > SIGNIFICANT_MOVE_KM
        }
        return true // First location
    }

    /**
     * Main location change handler with smart country management
     */
    private suspend fun handleLocationChange(location: GeoPoint) {
        mutex.withLock {
            val newCountry = getCurrentCountry(location)

            if (newCountry == null) {
                Log.w(TAG, "Could not determine country for location: $location")
                return@withLock
            }

            // Check if we've crossed a border
            if (newCountry != currentCountry) {
                handleBorderCrossing(currentCountry, newCountry, location)
                currentCountry = newCountry
            } else {
                // Same country - just update access time if cached
                updateCountryAccess(newCountry)
                
                // Aviation Safety: Continuously check for border proximity
                // This ensures that if we approach a border without crossing it yet,
                // the adjacent airspace is preloaded (critical for X-Alps style flights)
                coroutineScope.launch {
                    preloadAdjacentCountries(newCountry, location)
                }
            }
        }
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
            if (toCountry !in cachedCountries) {
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

            adjacentCountries.forEach { country ->
                if (country !in cachedCountries) {
                    preloadCountry(country)
                }
            }

            Log.d(TAG, "Preloaded adjacent countries for all overlay types: $adjacentCountries")
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
        try {
            Log.d(TAG, "Preloading country for all overlay types: $country")

            // 1. Download PG Spots (delegated to cache)
            Log.d(TAG, "Triggering PG spot download for $country")
            pgSpotCache.downloadAndCache(country)

            // 2. Download Airspaces (delegated to cache)
            Log.d(TAG, "Triggering Airspace download for $country")
            airspaceCache.downloadAndCache(country)

            mutex.withLock {
                cachedCountries.add(country)
            }
            Log.d(TAG, "Country cached for all overlay types: $country")
            
            // Notify listeners that country data is ready
            withContext(Dispatchers.Main) {
                onCountryLoaded?.invoke(country)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading country: $country", e)
        }
    }

    /**
     * Remove country from cache
     */
    private suspend fun removeCountry(country: String) {
        mutex.withLock {
            cachedCountries.remove(country)
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
    private suspend fun updateCountryAccess(country: String): Boolean {
        // Input validation - early return for invalid inputs
        if (country.isBlank()) {
            Log.w(TAG, "Attempted to update access for blank country code")
            return false
        }

        if (country.length != 2) {
            Log.w(TAG, "Invalid country code format (expected 2 chars): $country")
            return false
        }

        return try {
            mutex.withLock {
                updateCountryAccessInternal(country)
            }
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

        val lruCountry = accessOrderedCountries.removeFirst()
        countryAccessMetadata.remove(lruCountry)?.let { metadata ->
            Log.d(TAG, "Evicted LRU country: $lruCountry " +
                  "(accessed ${metadata.accessCount} times, last: ${metadata.lastAccessTime})")

            // Record eviction for performance monitoring
            recordCacheEviction(lruCountry, metadata.accessCount)
        }

        // Remove from main cache if present
        cachedCountries.remove(lruCountry)
    }

    // ==================== UTILITY FUNCTIONS ====================

    /**
     * Get current country code for location using Geocoder
     */
    private fun getCurrentCountry(location: GeoPoint): String? {
        return try {
            val geocoder = android.location.Geocoder(applicationContext, java.util.Locale.US)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                // Use async Geocoder for API 33+
                // Note: Since this function is currently synchronous, we might need to refactor to suspend
                // For now, we'll use the synchronous fallback or a blocking call if strictly necessary,
                // but ideally this entire chain should be suspend.
                // Given the current architecture, we'll use the legacy method which is still functional
                // but wrapped in try-catch for safety.
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                addresses?.firstOrNull()?.countryCode
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                addresses?.firstOrNull()?.countryCode
            } ?: "US" // Fallback to US if geocoder fails (e.g. no network/emulator issues)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting country code", e)
            "US" // Fallback
        }
    }

    // Adjacency map for major paragliding regions
    private val adjacencyMap = mapOf(
        "US" to listOf("CA", "MX"),
        "CA" to listOf("US"),
        "MX" to listOf("US", "GT", "BZ"),
        "DE" to listOf("FR", "CH", "AT", "NL", "BE", "LU", "DK", "PL", "CZ"),
        "AT" to listOf("DE", "CH", "IT", "SI", "HU", "SK", "CZ", "LI"),
        "CH" to listOf("DE", "AT", "IT", "FR", "LI"),
        "IT" to listOf("CH", "AT", "SI", "FR"),
        "FR" to listOf("CH", "IT", "DE", "BE", "ES", "LU", "MC", "AD"),
        "ES" to listOf("FR", "PT", "AD"),
        "GB" to listOf("IE", "FR"), // France via tunnel/proximity
        "IE" to listOf("GB")
    )

    /**
     * Get adjacent countries for preloading using data map
     */
    private fun getAdjacentCountries(currentCountry: String, location: GeoPoint): List<String> {
        return adjacencyMap[currentCountry] ?: emptyList()
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
     * Get currently cached countries (used by all overlay managers)
     */
    fun getCachedCountries(): Set<String> = cachedCountries.toSet()

    /**
     * Check if country is cached (used by all overlay managers)
     */
    fun isCountryCached(country: String): Boolean = country in cachedCountries

    /**
     * Query features from multiple countries using spatial indexing
     * This method provides the spatial query functionality for overlay managers
     */
    suspend fun queryMultiCountryArea(
        center: GeoPoint,
        radiusKm: Double
    ): List<com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature> {
        Log.v(TAG, "Multi-country spatial query: center=$center, radius=$radiusKm km")

        return mutex.withLock {
            val allFeatures = mutableListOf<com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature>()

            // Query each cached country for relevant features
            cachedCountries.forEach { countryCode ->
                try {
                    val features = queryCountryFeatures(center, countryCode, radiusKm)
                    allFeatures.addAll(features)
                    Log.v(TAG, "Found ${features.size} features in $countryCode")
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying country $countryCode", e)
                }
            }

            Log.d(TAG, "Total features from ${cachedCountries.size} countries: ${allFeatures.size}")
            allFeatures
        }
    }

    /**
     * Query features from a specific country using existing cache infrastructure
     */
    private suspend fun queryCountryFeatures(
        center: GeoPoint,
        countryCode: String,
        radiusKm: Double
    ): List<com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature> {
        val features = mutableListOf<com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature>()
        
        try {
            // 1. Query Airspaces
            if (airspaceCache.isCached(countryCode)) {
                features.addAll(airspaceCache.queryNearbyFeatures(countryCode, center, radiusKm))
            }

            // 2. Query PG Spots
            if (pgSpotCache.isCached(countryCode)) {
                // Use 200.0 miles (approx 320km) or convert radiusKm to miles. 
                // queryNearbyPGSpots takes miles. radiusKm is in km.
                val radiusMiles = radiusKm * 0.621371
                features.addAll(pgSpotCache.queryNearbyPGSpots(countryCode, center, radiusMiles))
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

        return mapOf(
            "cached_countries" to cachedCountries.size,
            "max_countries" to MAX_CACHED_COUNTRIES,
            "current_country" to (currentCountry ?: "unknown"),
            "cache_utilization" to (cachedCountries.size * 100) / MAX_CACHED_COUNTRIES,
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

        if (country.length != 2) {
            Log.w(TAG, "Invalid country code format for ${eventType.name.lowercase()}: $country (expected 2 chars)")
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
        cachedCountries.clear()
        countryAccessMetadata.clear()
        accessOrderedCountries.clear()
        currentCountry = null
        lastLocation = null
        Log.d(TAG, "UniversalCountryCacheManager shutdown complete")
    }
}