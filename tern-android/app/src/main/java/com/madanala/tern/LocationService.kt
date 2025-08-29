package com.madanala.tern

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*

class LocationService(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null

    private fun hasLocationPermission(): Boolean {
        // Checks if EITHER fine OR coarse location permission is granted.
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun startLocationUpdates(onLocationUpdate: (Location) -> Unit) {

        if (!hasLocationPermission()) {
            Log.w("LocationService", "Location permission not granted. Cannot start updates.")
            return
        }

        val locationRequest = LocationRequest.Builder(1000)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    onLocationUpdate(location) // Uses the lambda passed into this specific call
                }
            }
        }

        // It's generally safer to use a local variable for the callback
        // or ensure locationCallback cannot be nullified between creation and use.
        val currentCallback = locationCallback
        if (currentCallback != null) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                currentCallback, // Use the local non-null variable
                null // Looper, null means main thread
            )
        } else {
            Log.e("LocationService", "LocationCallback was null, cannot request updates.")
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
            locationCallback = null // Consider nullifying to prevent reuse if stop is definitive
        }
    }

    fun getLastLocation(onSuccess: (Location) -> Unit) {
        if (!hasLocationPermission()) {
            Log.w("LocationService", "Location permission not granted. Cannot get last location.")
            return
        }

        // Add a try-catch for security exceptions, though hasLocationPermission should prevent this.
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let { onSuccess(it) }
            }
        } catch (e: SecurityException) {
            Log.e("LocationService", "SecurityException in getLastLocation: ${e.message}")
        }
    }
}
