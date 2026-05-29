package com.ternparagliding.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ternparagliding.redux.GpsStatus
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import org.osmdroid.util.GeoPoint

private const val TAG = "ReduxLocationService"
private const val UPDATE_INTERVAL_MS = 2_000L
private const val MIN_UPDATE_INTERVAL_MS = 1_000L

class ReduxLocationService(
    private val store: MapStore,
    context: Context,
) {
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        UPDATE_INTERVAL_MS,
    ).setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val geoPoint = GeoPoint(location.latitude, location.longitude, location.altitude)
            store.dispatch(MapAction.UpdateUserLocation(geoPoint))

            if (!store.state.value.isLocationReady) {
                Log.d(TAG, "First GPS fix: ${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}")
                store.dispatch(MapAction.UpdateCenter(geoPoint))
                store.setLocationReady(true)
                store.updateGpsStatus(GpsStatus.ACTIVE)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        Log.d(TAG, "Starting location updates")
        store.updateGpsStatus(GpsStatus.ACQUIRING)
        fusedClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper(),
        )
    }

    fun stopLocationUpdates() {
        Log.d(TAG, "Stopping location updates")
        fusedClient.removeLocationUpdates(locationCallback)
        store.dispatch(MapAction.SetLocationReady(false))
    }
}
