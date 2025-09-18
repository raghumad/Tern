package com.madanala.tern.utils

import android.content.Context
import android.location.Address
import android.location.Geocoder
import org.osmdroid.util.GeoPoint
import java.util.Locale

/**
 * Utility class for country detection and geocoding operations
 */
object CountryUtils {

    /**
     * Get country code from coordinates using reverse geocoding
     * @param context Android context
     * @param latitude Latitude coordinate
     * @param longitude Longitude coordinate
     * @return Two-letter country code (ISO 3166-1 alpha-2) or null if not found
     */
    fun getCountryCodeFromCoordinates(
        context: Context,
        latitude: Double,
        longitude: Double
    ): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses: List<Address> = geocoder.getFromLocation(latitude, longitude, 1)
                ?: return null

            if (addresses.isNotEmpty()) {
                val countryCode = addresses[0].countryCode
                // Convert to lowercase for consistency with OpenAIP format
                countryCode?.lowercase()
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
