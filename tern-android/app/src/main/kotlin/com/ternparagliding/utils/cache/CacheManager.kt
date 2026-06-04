package com.ternparagliding.utils.cache

import android.content.Context
import android.util.Log

/**
 * Singleton cache manager to prevent duplicate downloads across components
 * Aviation-grade resilience: One cache instance per data type prevents conflicts
 */
object CacheManager {

    // Application context for cache initialization
    private lateinit var appContext: Context

    // Singleton cache instances - lazy initialization for memory efficiency
    val airspaceCache: AirspaceCache by lazy {
        ensureInitialized()
        AirspaceCache(appContext)
    }

    val pgSpotCache: PGSpotCache by lazy {
        ensureInitialized()
        PGSpotCache(appContext)
    }

    val routeCache: RouteCache by lazy {
        ensureInitialized()
        RouteCache(appContext)
    }

    val weatherCache: WeatherCache by lazy {
        ensureInitialized()
        WeatherCache(appContext)
    }

    /**
     * Drives location-based country airspace/PG-spot downloads + adjacent
     * preloading + LRU eviction. Single app-lifetime instance; fed by
     * [com.ternparagliding.redux.CountryPreloadMiddleware] from map-centre
     * changes. Without this, nothing ever populates the overlay caches.
     */
    val countryCacheManager: UniversalCountryCacheManager by lazy {
        ensureInitialized()
        UniversalCountryCacheManager(appContext)
    }

    /**
     * Initialize with application context
     * Must be called once during app startup
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        Log.d("CacheManager", "Initialized with application context")
    }

    private fun ensureInitialized() {
        if (!::appContext.isInitialized) {
            throw IllegalStateException("CacheManager must be initialized with application context before use")
        }
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): Map<String, Any> {
        ensureInitialized()
        return mapOf(
            "airspaceCache" to airspaceCache.getCacheStats(),
            "pgSpotCache" to pgSpotCache.getCacheStats(),
            "routeCache" to routeCache.getCacheStats(),
            "weatherCache" to weatherCache.getCacheStats()
        )
    }

    /**
     * Clear all caches (for debugging/testing only)
     */
    fun clearAllCaches() {
        if (::appContext.isInitialized) {
            airspaceCache.clearCache()
            pgSpotCache.clearCache()
            routeCache.clearCache()
            weatherCache.clearCache()
            Log.d("CacheManager", "All caches cleared")
        }
    }
}