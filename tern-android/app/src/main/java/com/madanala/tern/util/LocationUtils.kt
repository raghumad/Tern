package com.madanala.tern.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Attempts to get the ISO 3166-1 alpha-2 country code from a given Location.
 * Assumes app's minSdk is 33 or higher (current project minSdk is 36).
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
        // API Level 33+ (Tiramisu and above) - GeocodeListener
        return@withContext suspendCancellableCoroutine<String?> { continuation ->
            geocoder.getFromLocation(
                location.latitude,
                location.longitude,
                1, // maxResults
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        val countryCode = if (addresses.isNotEmpty()) {
                            addresses[0].countryCode
                        } else {
                            null
                        }

                        if (countryCode.isNullOrEmpty()) {
                            println("Country code from geocoder (API 33+) was null or empty for location: $location")
                            continuation.resume(null)
                        } else {
                            println("Successfully determined country code (API 33+): $countryCode for location: $location")
                            continuation.resume(countryCode)
                        }
                    }

                    override fun onError(errorMessage: String?) {
                        println("Geocoder (API 33+) error: ${errorMessage ?: "Unknown error"}")
                        continuation.resume(null)
                    }
                }
            )
            // Handle coroutine cancellation if the Geocoder API had a way to cancel its operation.
            // For this API, cancellation of the coroutine primarily prevents resuming if it hasn't already.
            continuation.invokeOnCancellation { 
                // Log or handle cancellation if specific cleanup is needed
            }
        }
    } catch (e: IOException) {
        // Typically a network error or no backend service available
        println("Geocoder IOException: Failed to get country code. ${e.message}")
    } catch (e: IllegalArgumentException) {
        // Invalid latitude or longitude
        println("Geocoder IllegalArgumentException: Invalid location provided. ${e.message}")
    } catch (e: Exception) {
        // Any other unexpected errors
        println("Geocoder generic error: ${e.message}")
    }
    return@withContext null // Fallback if the outer try-catch is hit before or after suspendCancellableCoroutine completes with an exception
}
