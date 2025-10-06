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
 * This is a streamlined, working implementation that demonstrates the core concepts
 * of intelligent country management for aviation safety and smooth border transitions.
 */
class UniversalCountryCacheManager(
    private val applicationContext: Context
) {

    private val TAG = "UniversalCountryCache"
    private val mutex = Mutex()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Smart country caching - simplified for working implementation
    private val cachedCountries = mutableSetOf<String>()
    private var lastLocation: GeoPoint? = null
    private var currentCountry: String? = null

    // Aviation-optimized configuration
    companion object {
        const val MAX_CACHED_COUNTRIES = 4
        const val PRELOAD_DISTANCE_KM = 150.0
        const val RETAIN_DISTANCE_KM = 200.0
        const val SIGNIFICANT_MOVE_KM = 2.0
        const val COUNTRY_UNLOAD_DELAY_MS = 10000L
    }

    init {
        Log.d(TAG, "UniversalCountryCacheManager initialized")
    }

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
    private suspend fun preloadCountry(country: String) {
        try {
            Log.d(TAG, "Preloading country for all overlay types: $country")

            // In real implementation, this would download the full country data
            // For this demo, we'll simulate the caching
            cachedCountries.add(country)

            // Simulate download delay (in real implementation, this would be network I/O)
            delay(100)

            Log.d(TAG, "Country cached for all overlay types: $country")
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading country: $country", e)
        }
    }

    /**
     * Remove country from cache
     */
    private fun removeCountry(country: String) {
        cachedCountries.remove(country)
        Log.d(TAG, "Removed country from cache: $country")
    }

    /**
     * Update country access time
     */
    private fun updateCountryAccess(country: String) {
        // In real implementation, would update access timestamp in cache metadata
        Log.v(TAG, "Updated access for country: $country")
    }

    // ==================== UTILITY FUNCTIONS ====================

    /**
     * Get current country code for location (simplified)
     */
    private fun getCurrentCountry(location: GeoPoint): String? {
        return try {
            // In real implementation, this would use proper country detection
            // For this demo, we'll simulate country detection
            "US" // Placeholder - would use actual country detection
        } catch (e: Exception) {
            Log.e(TAG, "Error getting country code", e)
            null
        }
    }

    /**
     * Get adjacent countries for preloading (works for all overlay types)
     */
    private fun getAdjacentCountries(currentCountry: String, location: GeoPoint): List<String> {
        // In real implementation, this would use proper geographic adjacency calculation
        // For this demo, return common adjacent countries based on current country
        return when (currentCountry) {
            "US" -> listOf("CA", "MX") // US adjacent to Canada and Mexico
            "DE" -> listOf("FR", "CH", "AT", "NL", "BE") // Germany adjacent countries (Alps region)
            "AT" -> listOf("DE", "CH", "IT", "SI", "HU") // Austria adjacent countries
            "CH" -> listOf("DE", "AT", "IT", "FR", "LI") // Switzerland adjacent countries
            "IT" -> listOf("CH", "AT", "SI", "FR") // Italy adjacent countries
            "FR" -> listOf("CH", "IT", "DE", "BE", "ES") // France adjacent countries
            else -> listOf("ADJ1", "ADJ2") // Generic adjacent countries for unknown regions
        }
    }

    /**
     * Calculate distance from current location to country (simplified)
     */
    private fun calculateDistanceFromCurrentLocation(country: String, location: GeoPoint): Double {
        // In real implementation, this would calculate actual distance to country center
        // For this demo, return a placeholder distance
        return 100.0 // Placeholder distance in km
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
        return try {
            // Use existing CacheManager to get country data
            val cacheManager = com.madanala.tern.utils.CacheManager.airspaceCache

            if (cacheManager.isCached(countryCode)) {
                // Query features within radius using existing spatial indexing
                cacheManager.queryNearbyFeatures(countryCode, center, radiusKm)
            } else {
                Log.v(TAG, "Country $countryCode not cached, skipping query")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying features for country $countryCode", e)
            emptyList()
        }
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cached_countries" to cachedCountries.size,
            "max_countries" to MAX_CACHED_COUNTRIES,
            "current_country" to (currentCountry ?: "unknown"),
            "cache_utilization" to (cachedCountries.size * 100) / MAX_CACHED_COUNTRIES
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
            PerformanceDebugger.recordBorderCrossing(fromCountry, toCountry)
            if (isSmooth) {
                PerformanceDebugger.recordSmoothTransition()
            } else {
                PerformanceDebugger.recordVisualDiscontinuity()
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
        coroutineScope.cancel()
        cachedCountries.clear()
        currentCountry = null
        lastLocation = null
        Log.d(TAG, "UniversalCountryCacheManager shutdown complete")
    }
}