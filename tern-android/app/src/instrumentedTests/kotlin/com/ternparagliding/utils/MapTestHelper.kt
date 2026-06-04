package com.ternparagliding.utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Test helpers for map-backed instrumented tests.
 *
 * Gesture helpers (Phase 1, 2026-06): the OSMDroid versions were deleted in the
 * MapLibre migration. The rebuilt [clickGeoPoint]/[longPressGeoPoint]/
 * [dragGeoPoint] convert a lat/lon to a screen pixel via the live MapLibre
 * projection (exposed by MapViewContainer through [MapProjectionTestHook]) and
 * drive a real Compose touch on the `map_view` node — so gesture tests exercise
 * the actual map instead of dispatching Redux actions tautologically.
 */
object MapTestHelper {

    private const val MAP_TAG = "map_view"

    /**
     * Resolve a geographic point to a screen pixel via the live MapLibre
     * projection, waiting up to [timeoutMillis] for the map to compose and the
     * projection to become ready.
     */
    fun screenPxForGeoPoint(
        rule: AndroidComposeTestRule<*, *>,
        lat: Double,
        lon: Double,
        timeoutMillis: Long = 5000,
    ): Offset {
        var px: Offset? = null
        rule.waitUntil(timeoutMillis) {
            // MapLibre's projection (pixelForLatLng) must run on the UI thread.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                px = MapProjectionTestHook.screenPxFor(lat, lon)
            }
            px != null
        }
        return px ?: error("MapProjectionTestHook not ready for ($lat, $lon)")
    }

    /** Single-tap the map at a geographic point. */
    fun clickGeoPoint(rule: AndroidComposeTestRule<*, *>, lat: Double, lon: Double) {
        val px = screenPxForGeoPoint(rule, lat, lon)
        ReportGenerator.logStep("ACTION", "Click at ($lat, $lon) -> px(${px.x}, ${px.y})")
        rule.onNodeWithTag(MAP_TAG).performTouchInput { click(px) }
    }

    /** Long-press the map at a geographic point (e.g. waypoint creation). */
    fun longPressGeoPoint(rule: AndroidComposeTestRule<*, *>, lat: Double, lon: Double) {
        val px = screenPxForGeoPoint(rule, lat, lon)
        ReportGenerator.logStep("ACTION", "Long-press at ($lat, $lon) -> px(${px.x}, ${px.y})")
        rule.onNodeWithTag(MAP_TAG).performTouchInput { longClick(px) }
    }

    /** Drag across the map from one geographic point to another. */
    fun dragGeoPoint(
        rule: AndroidComposeTestRule<*, *>,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        durationMillis: Long = 500,
    ) {
        val from = screenPxForGeoPoint(rule, fromLat, fromLon)
        val to = screenPxForGeoPoint(rule, toLat, toLon)
        ReportGenerator.logStep("ACTION", "Drag ($fromLat,$fromLon)->($toLat,$toLon)")
        rule.onNodeWithTag(MAP_TAG).performTouchInput { swipe(from, to, durationMillis) }
    }

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
