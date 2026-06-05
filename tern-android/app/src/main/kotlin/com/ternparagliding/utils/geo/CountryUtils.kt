package com.ternparagliding.utils.geo

import android.content.Context
import android.location.Address
import android.location.Geocoder
import org.osmdroid.util.GeoPoint
import android.util.Log
import java.util.Locale

/**
 * Utility class for country detection and geocoding operations
 */
object CountryUtils {
    private const val TAG = "CountryUtils"

    /**
     * Get country code from coordinates using reverse geocoding
     * @param context Android context
     * @param latitude Latitude coordinate
     * @param longitude Longitude coordinate
     * @return Two-letter country code (ISO 3166-1 alpha-2) or null if not found
     */
    @Volatile
    private var testCountryCode: String? = null

    @androidx.annotation.VisibleForTesting
    fun setTestCountryCode(code: String?) {
        Log.i(TAG, "Setting test country code to: $code")
        testCountryCode = code?.uppercase()
    }

    /**
     * True when a test has pinned the country code via [setTestCountryCode].
     * Production wiring (CountryPreloadMiddleware) checks this to avoid firing
     * real network downloads for the synthetic "TEST" country during
     * instrumented tests that inject their own cache data.
     */
    fun isTestMode(): Boolean = testCountryCode != null

    /**
     * Resolved country codes cached by a coarse lat/lon grid (~0.1° ≈ 11 km).
     * A country code is constant across such a cell (except right at a border),
     * so this turns the per-pan reverse-geocode — a *blocking network call* that
     * gated every airspace/PG-spot re-query AND the download path, and competed
     * with MapLibre tile downloads — into a one-time lookup per area. Only
     * successful results are cached; transient failures are not, so they retry.
     * (This app is offline-first; the network Geocoder is the wrong long-term
     * dependency here, but caching removes the repeated-call latency cheaply.)
     */
    private val countryCodeCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private fun gridKey(latitude: Double, longitude: Double): Long {
        val la = Math.round(latitude * 10.0)   // 0.1° cells
        val lo = Math.round(longitude * 10.0)
        return (la shl 32) or (lo and 0xFFFFFFFFL)
    }

    fun getCountryCodeFromCoordinates(
        context: Context,
        latitude: Double,
        longitude: Double
    ): String? {
        if (testCountryCode != null) return testCountryCode

        val key = gridKey(latitude, longitude)
        countryCodeCache[key]?.let { return it }

        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses: List<Address> = geocoder.getFromLocation(latitude, longitude, 1)
                ?: return null

            if (addresses.isNotEmpty()) {
                // Convert to lowercase for consistency with OpenAIP format
                val countryCode = addresses[0].countryCode?.lowercase()
                if (countryCode != null) countryCodeCache[key] = countryCode
                countryCode
            } else {
                null
            }
        } catch (e: Exception) {
            // Handle geocoding failures gracefully
            null
        }
    }

    /**
     * Get country code from GeoPoint
     * @param context Android context
     * @param geoPoint GeoPoint containing coordinates
     * @return Two-letter country code or null if not found
     */
    fun getCountryCodeFromGeoPoint(context: Context, geoPoint: GeoPoint): String? {
        return getCountryCodeFromCoordinates(context, geoPoint.latitude, geoPoint.longitude)
    }

    /**
     * Check if coordinates are over water/sea
     * This is a simple heuristic - in a real app you'd use a more sophisticated method
     * @param latitude Latitude coordinate
     * @param longitude Longitude coordinate
     * @return true if likely over water
     */
    fun isOverWater(latitude: Double, longitude: Double): Boolean {
        // Simple heuristic: if no country code found, likely over water
        // In production, you'd want to use a proper land/water detection service
        return false // For now, assume all coordinates have country codes
    }

    /**
     * Get a list of nearby country codes for border regions
     * @param context Android context
     * @param latitude Latitude coordinate
     * @param longitude Longitude coordinate
     * @param radiusKm Search radius in kilometers
     * @return List of country codes found within the radius
     */
    fun getNearbyCountryCodes(
        context: Context,
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 50.0
    ): List<String> {
        val testCode = testCountryCode
        if (testCode != null) {
            Log.d(TAG, "getNearbyCountryCodes: Using test country override: $testCode")
            return listOf(testCode.lowercase())
        }

        val countryCodes = mutableSetOf<String>()

        try {
            val geocoder = Geocoder(context, Locale.getDefault())

            // Sample points in a circle around the center
            val numPoints = 8
            for (i in 0 until numPoints) {
                val angle = 2 * Math.PI * i / numPoints
                val latOffset = radiusKm / 111.0 * Math.sin(angle) // Rough km to degrees conversion
                val lonOffset = radiusKm / (111.0 * Math.cos(Math.toRadians(latitude))) * Math.cos(angle)

                val sampleLat = latitude + latOffset
                val sampleLon = longitude + lonOffset

                @Suppress("DEPRECATION")
                val addresses: List<Address> = geocoder.getFromLocation(sampleLat, sampleLon, 1)
                    ?: continue

                addresses.firstOrNull()?.countryCode?.lowercase()?.let { countryCodes.add(it) }
            }
        } catch (e: Exception) {
            // Handle geocoding failures
        }

        return countryCodes.toList()
    }
}
