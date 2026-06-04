package com.ternparagliding.utils

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Test helpers for map-backed instrumented tests.
 *
 * M8: the OSMDroid-based gesture helpers (clickOnGeoPoint, longPressOnGeoPoint,
 * pressAndHoldGeoPoint/moveHold/releaseHold, swipeMap, findMapView,
 * getScreenCoordinates, MockLocationProvider) were removed when the app
 * migrated from OSMDroid to MapLibre — they projected via `MapView.projection`,
 * which no longer exists. Real gesture testing now goes through MapLibre's
 * `cameraState.projection` (see OffScreenPeerIndicators); until those helpers
 * are rebuilt, gesture tests dispatch via Redux. Only the OSMDroid-free
 * helpers below remain.
 */
object MapTestHelper {

    fun waitForMapTiles(timeoutMillis: Long = 3000) {
        try {
            Thread.sleep(timeoutMillis)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun grantLocationPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
        uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @Suppress("DEPRECATION")
    fun injectMockLocation(composeTestRule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>, lat: Double, lon: Double) {
        // M8: locationProviderFactory was removed from MapViewModel when
        // OSMDroid was replaced by MapLibre. Location is handled by
        // ReduxLocationService. We still inject the system-level mock
        // location below for the GPS provider.

        // Mock system location for the Android LocationManager
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            try {
                // Remove existing providers to ensure clean state
                try {
                    locationManager.removeTestProvider(android.location.LocationManager.GPS_PROVIDER)
                } catch (e: Exception) { /* Ignore */ }

                try {
                    locationManager.removeTestProvider(android.location.LocationManager.NETWORK_PROVIDER)
                } catch (e: Exception) { /* Ignore */ }

                locationManager.addTestProvider(
                    android.location.LocationManager.GPS_PROVIDER,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
                locationManager.setTestProviderStatus(
                    android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationProvider.AVAILABLE,
                    null,
                    System.currentTimeMillis()
                )

                locationManager.addTestProvider(
                    android.location.LocationManager.NETWORK_PROVIDER,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER, true)
                locationManager.setTestProviderStatus(
                    android.location.LocationManager.NETWORK_PROVIDER,
                    android.location.LocationProvider.AVAILABLE,
                    null,
                    System.currentTimeMillis()
                )

                // Mock Fused Provider (Common source of default emulator location)
                try {
                    locationManager.addTestProvider(
                        "fused",
                        false,
                        false,
                        false,
                        false,
                        true,
                        true,
                        true,
                        android.location.Criteria.POWER_LOW,
                        android.location.Criteria.ACCURACY_FINE
                    )
                    locationManager.setTestProviderEnabled("fused", true)
                    locationManager.setTestProviderStatus(
                        "fused",
                        android.location.LocationProvider.AVAILABLE,
                        null,
                        System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    // Fused provider might not exist or be mockable on all devices
                    android.util.Log.w("MapTestHelper", "Could not mock fused provider: ${e.message}")
                }

            val mockLocationGps = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                latitude = lat
                longitude = lon
                altitude = 1600.0
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                accuracy = 1.0f
            }
            locationManager.setTestProviderLocation(android.location.LocationManager.GPS_PROVIDER, mockLocationGps)

            val mockLocationNetwork = android.location.Location(android.location.LocationManager.NETWORK_PROVIDER).apply {
                latitude = lat
                longitude = lon
                altitude = 1600.0
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                accuracy = 1.0f
            }
            locationManager.setTestProviderLocation(android.location.LocationManager.NETWORK_PROVIDER, mockLocationNetwork)

            try {
                val mockLocationFused = android.location.Location("fused").apply {
                    latitude = lat
                    longitude = lon
                    altitude = 1600.0
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    accuracy = 1.0f
                }
                locationManager.setTestProviderLocation("fused", mockLocationFused)
            } catch (e: Exception) { /* Ignore */ }

        } catch (e: SecurityException) {
            println("Warning: Could not set mock location: ${e.message}")
        } catch (e: IllegalArgumentException) {
             // Provider might already exist or be unmockable
             println("Warning: Error setting mock provider: ${e.message}")
        }
    }
}
