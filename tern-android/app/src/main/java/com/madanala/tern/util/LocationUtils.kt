@file:Suppress("unused", "DEPRECATION")

package com.madanala.tern.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Attempts to get the ISO 3166-1 alpha-2 country code from a given Location.
 * This function is backward-compatible.
 *
 * @param context The application context.
 * @param location The location from which to derive the country code.
 * @return The country code (e.g., "US", "FR") or null if it cannot be determined or an error occurs.
 */
suspend fun getCountryCodeFromLocation(context: Context, location: Location): String? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) {
        println("Geocoder not present on this device.")
        return@withContext null
    }

    val geocoder = Geocoder(context, Locale.getDefault())

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API Level 33+ (Tiramisu and above) - Use the modern GeocodeListener
            getFromLocationApi33(geocoder, location)
        } else {
            // Older APIs - Use the deprecated synchronous method
            getFromLocationLegacy(geocoder, location)
        }
    } catch (e: IOException) {
        // Typically a network error or no backend service available
        println("Geocoder IOException: Failed to get country code. ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        // Invalid latitude or longitude
        println("Geocoder IllegalArgumentException: Invalid location provided. ${e.message}")
        null
    } catch (e: Exception) {
        // Any other unexpected errors
        println("Geocoder generic error: ${e.message}")
        null
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private suspend fun getFromLocationApi33(geocoder: Geocoder, location: Location): String? {
    return suspendCancellableCoroutine { continuation ->
        geocoder.getFromLocation(
            location.latitude,
            location.longitude,
            1, // maxResults
            object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    val countryCode = addresses.firstOrNull()?.countryCode
                    if (continuation.isActive) {
                        continuation.resume(countryCode)
                    }
                }

                override fun onError(errorMessage: String?) {
                    println("Geocoder (API 33+) error: ${errorMessage ?: "Unknown error"}")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        )
    }
}

private fun getFromLocationLegacy(geocoder: Geocoder, location: Location): String? {
    return try {
        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        addresses?.firstOrNull()?.countryCode
    } catch (e: IOException) {
        println("Geocoder (Legacy) error: ${e.message}")
        null
    }
}
