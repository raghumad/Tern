package com.ternparagliding.utils

import android.util.Log

/**
 * LRU access tracking, eviction bookkeeping, and performance-event recording
 * extracted from UniversalCountryCacheManager (Phase 0c god-file split).
 *
 * NOT thread-safe on its own — the owner invokes [recordAccess] while holding
 * its own mutex, exactly as the prior inline code did. Eviction is delegated
 * via the [onEvict] callback so the owner keeps control of the coroutine that
 * actually purges disk (preserving the original threading discipline).
 */
internal class CountryAccessTracker(
    private val maxCachedCountries: Int,
    private val onEvict: (String) -> Unit
) {
    private val TAG = "CountryAccessTracker"

    /**
     * Tracking metadata for intelligent (LRU) cache management — enables
     * usage-based eviction and performance monitoring for aviation safety.
     */
    private data class CountryAccessMetadata(
        var firstAccessTime: Long,
        var lastAccessTime: Long,
        var accessCount: Int,
        var totalAccessTime: Long
    )

    private val countryAccessMetadata = mutableMapOf<String, CountryAccessMetadata>()
    private val accessOrderedCountries = LinkedHashSet<String>() // LRU ordering for cache eviction

    /**
     * Update country access time with intelligent cache management.
     * Returns true if access was recorded, false for invalid country codes.
     */
    fun recordAccess(country: String): Boolean {
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
            recordAccessInternal(country)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating access for country: $country", e)
            false
        }
    }

    /**
     * Internal access update logic with full cache management functionality.
     * Callers must hold the owner's mutex (as the original inline code did).
     */
    private fun recordAccessInternal(country: String): Boolean {
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
        recordCountryEvent(country, metadata.accessCount, CountryEventType.ACCESS)

        // Implement LRU-style management: move to end of access order
        accessOrderedCountries.remove(country)
        accessOrderedCountries.add(country)

        // Smart cache size management - evict least recently used if needed
        if (accessOrderedCountries.size > maxCachedCountries) {
            evictLeastRecentlyUsedCountry()
        }

        Log.v(TAG, "Updated access for country: $country (access count: ${metadata.accessCount})")
        return true
    }

    /**
     * Evict the least recently used country when cache is full, delegating the
     * actual disk purge to the owner via [onEvict].
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
            recordCountryEvent(lruCountry, metadata.accessCount, CountryEventType.EVICTION)
        }

        // Remove from main cache (disk purged by the owner)
        onEvict(lruCountry)
    }

    /** Clear all access bookkeeping (test reset / shutdown). */
    fun clear() {
        synchronized(countryAccessMetadata) {
            countryAccessMetadata.clear()
        }
        synchronized(accessOrderedCountries) {
            accessOrderedCountries.clear()
        }
    }

    /** Stats fragment merged into UniversalCountryCacheManager.getCacheStats(). */
    fun statsFragment(): Map<String, Any> {
        val totalAccesses = countryAccessMetadata.values.sumOf { it.accessCount }
        val mostAccessed = countryAccessMetadata.maxByOrNull { it.value.accessCount }
        val avgAccessTime = if (totalAccesses > 0) {
            countryAccessMetadata.values.sumOf { it.totalAccessTime } / totalAccesses
        } else 0L

        return mapOf(
            "total_country_accesses" to totalAccesses,
            "countries_with_metadata" to countryAccessMetadata.size,
            "most_accessed_country" to (mostAccessed?.key ?: "none"),
            "most_accessed_count" to (mostAccessed?.value?.accessCount ?: 0),
            "average_access_time_ms" to avgAccessTime,
            "lru_order_size" to accessOrderedCountries.size
        )
    }

    /**
     * Record country access or cache eviction for performance monitoring.
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

    /** Types of country cache events for performance monitoring. */
    private enum class CountryEventType {
        ACCESS, EVICTION
    }
}
