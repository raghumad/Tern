package com.madanala.tern.utils

import android.content.Context

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
     * Initialize with application context
     * Must be called once during app startup
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        android.util.Log.d("CacheManager", "Initialized with application context")
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
            "routeCache" to routeCache.getCacheStats()
        )
    }

    /**
     * Clear all caches (for debugging/testing only)
     */
    fun clearAllCaches() {
        ensureInitialized()
        airspaceCache.clearCache()
        pgSpotCache.clearCache()
        routeCache.clearCache()
        android.util.Log.d("CacheManager", "All caches cleared")
    }
}