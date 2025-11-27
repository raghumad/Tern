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

object MapTestHelper {

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
        val mapView = findMapView(activity.window.decorView) ?: throw IllegalStateException("MapView not found")
        val point = android.graphics.Point()
        mapView.projection.toPixels(GeoPoint(lat, lon), point)
        
        // Convert local coordinates to screen coordinates
        val locationOnScreen = IntArray(2)
        mapView.getLocationOnScreen(locationOnScreen)
        val x = (locationOnScreen[0] + point.x).toFloat()
        val y = (locationOnScreen[1] + point.y).toFloat()
        
        ReportGenerator.logStep("ACTION", "Long press at lat=$lat, lon=$lon -> screen x=$x, y=$y")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()
        
        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
        try {
            instrumentation.sendPointerSync(downEvent)
            ReportGenerator.logStep("DEBUG", "Sent ACTION_DOWN")
        } catch (e: Exception) {
            ReportGenerator.logStep("ERROR", "Failed to send ACTION_DOWN: ${e.message}", "FAIL")
        }
        
        // Wait for long press timeout (usually 500ms, wait 1000ms to be safe)
        ReportGenerator.logStep("ACTION", "Waiting for long press timeout (1000ms)")
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        
        val upEvent = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.sendPointerSync(upEvent)
            ReportGenerator.logStep("DEBUG", "Sent ACTION_UP")
        } catch (e: Exception) {
            ReportGenerator.logStep("ERROR", "Failed to send ACTION_UP: ${e.message}", "FAIL")
        }
        
        downEvent.recycle()
        upEvent.recycle()
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        try {
            locationManager.addTestProvider(
                android.location.LocationManager.GPS_PROVIDER,
                false, false, false, false, true, true, true, 1, 1
            )
            locationManager.setTestProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
            
            val mockLocation = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                latitude = lat
                longitude = lon
                altitude = 1600.0
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                accuracy = 1.0f
            }
            locationManager.setTestProviderLocation(android.location.LocationManager.GPS_PROVIDER, mockLocation)
        } catch (e: SecurityException) {
            println("Warning: Could not set mock location: ${e.message}")
        }
    }

    private fun findMapView(view: View): MapView? {
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
