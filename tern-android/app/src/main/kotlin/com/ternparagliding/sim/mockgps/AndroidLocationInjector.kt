package com.ternparagliding.sim.mockgps

import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock

/**
 * Production [LocationInjector] backed by Android's
 * [LocationManager.addTestProvider] / [LocationManager.setTestProviderLocation]
 * APIs.
 *
 * Requires the app to be designated the mock-location app in Android
 * Developer Options ("Select mock location app"). On API 23+ that is the
 * only path; the older `ACCESS_MOCK_LOCATION` permission no longer grants
 * anything by itself. We still declare the permission in the manifest for
 * the sake of platforms that look for it as an opt-in signal.
 *
 * This class is intentionally a thin shim: every method is a one-liner
 * wrapping a `LocationManager` call. All real behaviour — coordinate
 * derivation, ticking, speed/bearing math — lives in
 * [IgcMockLocationProvider], which is plain JVM code and unit-testable
 * without an Android device.
 */
class AndroidLocationInjector(
    private val locationManager: LocationManager,
) : LocationInjector {

    override fun addTestProvider(providerName: String) {
        // Defensive: if a prior run left a provider registered, drop it
        // first so addTestProvider does not throw IllegalArgumentException
        // ("provider X already exists").
        runCatching { locationManager.removeTestProvider(providerName) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties = ProviderProperties.Builder()
                .setHasNetworkRequirement(false)
                .setHasSatelliteRequirement(true)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            locationManager.addTestProvider(
                providerName,
                properties,
                emptySet(),
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                providerName,
                /* requiresNetwork = */ false,
                /* requiresSatellite = */ true,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                /* powerRequirement = */ 3, // POWER_HIGH
                /* accuracy = */ 1, // ACCURACY_FINE
            )
        }
        locationManager.setTestProviderEnabled(providerName, true)
    }

    override fun setTestProviderLocation(
        providerName: String,
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeMeters: Double,
        accuracyMeters: Float,
        speedMetersPerSecond: Float,
        bearingDegrees: Float,
        timeMillis: Long,
        elapsedRealtimeNanos: Long,
    ) {
        val location = Location(providerName).apply {
            latitude = latitudeDeg
            longitude = longitudeDeg
            altitude = altitudeMeters
            accuracy = accuracyMeters
            speed = speedMetersPerSecond
            bearing = bearingDegrees
            time = timeMillis
            this.elapsedRealtimeNanos = elapsedRealtimeNanos
        }
        locationManager.setTestProviderLocation(providerName, location)
    }

    override fun removeTestProvider(providerName: String) {
        runCatching { locationManager.removeTestProvider(providerName) }
    }

    companion object {
        /**
         * Convenience constructor for callers who only have a
         * `LocationManager` via system service lookup.
         */
        fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
    }
}
