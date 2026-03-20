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
        var screenX = 0
        var screenY = 0
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        
        instrumentation.runOnMainSync {
            val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
            val point = android.graphics.Point()
            mapView.projection.toPixels(GeoPoint(lat, lon), point)
            
            // Convert local coordinates to screen coordinates
            val locationOnScreen = IntArray(2)
            mapView.getLocationOnScreen(locationOnScreen)
            screenX = locationOnScreen[0] + point.x
            screenY = locationOnScreen[1] + point.y
        }
        
        val device = UiDevice.getInstance(instrumentation)
        ReportGenerator.logStep("ACTION", "Click on GeoPoint: $lat, $lon (Screen: $screenX, $screenY)")
        device.click(screenX, screenY)
    }

    fun longPressOnGeoPoint(activity: Activity, lat: Double, lon: Double) {
        var screenX = 0f
        var screenY = 0f
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        
        instrumentation.runOnMainSync {
            val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
            val point = android.graphics.Point()
            mapView.projection.toPixels(GeoPoint(lat, lon), point)
            
            val locationOnScreen = IntArray(2)
            mapView.getLocationOnScreen(locationOnScreen)
            screenX = (locationOnScreen[0] + point.x).toFloat()
            screenY = (locationOnScreen[1] + point.y).toFloat()
        }
        
        ReportGenerator.logStep("ACTION", "Long press at lat=$lat, lon=$lon -> screen x=$screenX, y=$screenY")
        
        val device = UiDevice.getInstance(instrumentation)
        device.swipe(screenX.toInt(), screenY.toInt(), screenX.toInt(), screenY.toInt(), 300) 
        
        ReportGenerator.logStep("DEBUG", "Performed long press via UiDevice swipe")
    }

    /**
     * Starts a press and hold interaction. 
     * Returns the down event which must be used for subsequent move/release calls.
     */
    fun pressAndHoldGeoPoint(activity: Activity, lat: Double, lon: Double): MotionEvent {
        var screenX = 0f
        var screenY = 0f
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var downEvent: MotionEvent? = null

        instrumentation.runOnMainSync {
            val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
            val point = android.graphics.Point()
            mapView.projection.toPixels(GeoPoint(lat, lon), point)
            
            val locationOnScreen = IntArray(2)
            mapView.getLocationOnScreen(locationOnScreen)
            screenX = (locationOnScreen[0] + point.x).toFloat()
            screenY = (locationOnScreen[1] + point.y).toFloat()

            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis()
            downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, screenX, screenY, 0)
            mapView.dispatchTouchEvent(downEvent)
        }
        
        ReportGenerator.logStep("ACTION", "INITIATED PRESS-HOLD at lat=$lat, lon=$lon")
        return downEvent!!
    }

    fun moveHold(activity: Activity, originalEvent: MotionEvent, lat: Double, lon: Double) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
            val point = android.graphics.Point()
            mapView.projection.toPixels(GeoPoint(lat, lon), point)
            
            val locationOnScreen = IntArray(2)
            mapView.getLocationOnScreen(locationOnScreen)
            val screenX = (locationOnScreen[0] + point.x).toFloat()
            val screenY = (locationOnScreen[1] + point.y).toFloat()

            val moveEvent = MotionEvent.obtain(
                originalEvent.downTime, 
                SystemClock.uptimeMillis(), 
                MotionEvent.ACTION_MOVE, 
                screenX, 
                screenY, 
                0
            )
            mapView.dispatchTouchEvent(moveEvent)
            moveEvent.recycle()
        }
        ReportGenerator.logStep("ACTION", "MOVED HOLD to lat=$lat, lon=$lon")
    }

    fun releaseHold(activity: Activity, originalEvent: MotionEvent) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
            val releaseEvent = MotionEvent.obtain(
                originalEvent.downTime, 
                SystemClock.uptimeMillis(), 
                MotionEvent.ACTION_UP, 
                originalEvent.x, 
                originalEvent.y, 
                0
            )
            mapView.dispatchTouchEvent(releaseEvent)
            releaseEvent.recycle()
            originalEvent.recycle()
        }
        ReportGenerator.logStep("ACTION", "RELEASED HOLD")
    }

    fun swipeMap(activity: Activity, startLat: Double, startLon: Double, endLat: Double, endLon: Double, steps: Int = 50) {
        var startX = 0
        var startY = 0
        var endX = 0
        var endY = 0
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        
        instrumentation.runOnMainSync {
            val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
            
            val locationOnScreen = IntArray(2)
            mapView.getLocationOnScreen(locationOnScreen)

            val startPoint = android.graphics.Point()
            mapView.projection.toPixels(GeoPoint(startLat, startLon), startPoint)
            startX = locationOnScreen[0] + startPoint.x
            startY = locationOnScreen[1] + startPoint.y

            val endPoint = android.graphics.Point()
            mapView.projection.toPixels(GeoPoint(endLat, endLon), endPoint)
            endX = locationOnScreen[0] + endPoint.x
            endY = locationOnScreen[1] + endPoint.y
        }
        
        ReportGenerator.logStep("ACTION", "Swipe from ($startLat, $startLon) to ($endLat, $endLon)")
        
        val device = UiDevice.getInstance(instrumentation)
        device.swipe(startX, startY, endX, endY, steps)
        
        // Wait for any map kinetic scrolling to settle
        waitForMapTiles(1000)
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
