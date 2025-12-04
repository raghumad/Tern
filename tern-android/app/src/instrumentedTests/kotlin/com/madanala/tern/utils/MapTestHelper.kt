package com.madanala.tern.utils

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

import android.os.SystemClock
import android.view.MotionEvent
import com.madanala.tern.ui.components.MapViewModel

class MockLocationProvider(private val lat: Double, private val lon: Double) : org.osmdroid.views.overlay.mylocation.IMyLocationProvider {
    private var consumer: org.osmdroid.views.overlay.mylocation.IMyLocationConsumer? = null

    override fun startLocationProvider(myLocationConsumer: org.osmdroid.views.overlay.mylocation.IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        val location = android.location.Location("mock").apply {
            latitude = lat
            longitude = lon
            altitude = 1600.0
            time = System.currentTimeMillis()
            accuracy = 1.0f
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
        // Deliver location immediately
        consumer?.onLocationChanged(location, this)
        return true
    }

    override fun stopLocationProvider() {
        consumer = null
    }

    override fun getLastKnownLocation(): android.location.Location {
        return android.location.Location("mock").apply {
            latitude = lat
            longitude = lon
            altitude = 1600.0
            time = System.currentTimeMillis()
            accuracy = 1.0f
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
    }

    override fun destroy() {}
}

object MapTestHelper {

    fun getScreenCoordinates(activity: Activity, lat: Double, lon: Double): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
            val point = android.graphics.Point()
            mapView.projection.toPixels(GeoPoint(lat, lon), point)
            x = point.x.toFloat()
            y = point.y.toFloat()
        }
        return Pair(x, y)
    }

    fun clickOnGeoPoint(activity: Activity, lat: Double, lon: Double) {
        val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
        val point = android.graphics.Point()
        mapView.projection.toPixels(GeoPoint(lat, lon), point)
        
        // Convert local coordinates to screen coordinates
        val locationOnScreen = IntArray(2)
        mapView.getLocationOnScreen(locationOnScreen)
        val screenX = locationOnScreen[0] + point.x
        val screenY = locationOnScreen[1] + point.y
        
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ReportGenerator.logStep("ACTION", "Click on GeoPoint: $lat, $lon (Screen: $screenX, $screenY)")
        device.click(screenX, screenY)
    }

    fun longPressOnGeoPoint(activity: Activity, lat: Double, lon: Double) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var x = 0f
        var y = 0f
        
        instrumentation.runOnMainSync {
            val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
            val point = android.graphics.Point()
            mapView.projection.toPixels(GeoPoint(lat, lon), point)
            
            // Convert local coordinates to screen coordinates
            val locationOnScreen = IntArray(2)
            mapView.getLocationOnScreen(locationOnScreen)
            x = (locationOnScreen[0] + point.x).toFloat()
            y = (locationOnScreen[1] + point.y).toFloat()
        }
        
        ReportGenerator.logStep("ACTION", "Long press at lat=$lat, lon=$lon -> screen x=$x, y=$y")

        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()
        
        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
        instrumentation.sendPointerSync(downEvent)
        
        // Wait for long press timeout (usually 500ms) + buffer
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        
        val upEvent = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        instrumentation.sendPointerSync(upEvent)
        
        downEvent.recycle()
        upEvent.recycle()
        
        ReportGenerator.logStep("DEBUG", "Performed long press via MotionEvents")
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

    fun injectMockLocation(composeTestRule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>, lat: Double, lon: Double) {
        // Set the custom provider factory in MapViewModel
        MapViewModel.locationProviderFactory = { MockLocationProvider(lat, lon) }
        
        // Also try to mock system location for completeness (though likely bypassed now)
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

    fun findMapView(view: View): MapView? {
        if (view is MapView) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findMapView(child)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }
}
