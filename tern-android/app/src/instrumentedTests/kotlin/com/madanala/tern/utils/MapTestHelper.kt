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
        
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()
        
        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
        instrumentation.sendPointerSync(downEvent)
        
        // Wait for long press timeout (usually 500ms, wait 1000ms to be safe)
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        
        val upEvent = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        instrumentation.sendPointerSync(upEvent)
        
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
