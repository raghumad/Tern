package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.GeoJsonUtils
import com.madanala.tern.utils.ReportGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

@RunWith(AndroidJUnit4::class)
class AirspaceIntersectionTest : MapVisualTest() {

    @Test
    fun testWaypointInsideAirspace() = scenario("Airspace Intersection") {
        givenAppIsLaunchedOnMap()
        
        var airspacePolygon: Polygon? = null
        val insidePoint = GeoPoint(40.0, -105.0)
        val outsidePoint = GeoPoint(41.0, -106.0)

        given("a defined airspace polygon") {
            ReportGenerator.logStep("SETUP", "Creating mock airspace polygon")
            
            composeTestRule.runOnUiThread {
                val activity = composeTestRule.activity
                val mapViewModel = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.ui.components.MapViewModel::class.java]
                val mapView = mapViewModel.mapView
                
                // Create a simple square polygon around 40.0, -105.0
                airspacePolygon = Polygon(mapView).apply {
                    points = listOf(
                        GeoPoint(39.9, -105.1),
                        GeoPoint(40.1, -105.1),
                        GeoPoint(40.1, -104.9),
                        GeoPoint(39.9, -104.9)
                    )
                }
                mapView.overlays.add(airspacePolygon)
                mapView.invalidate()
            }
            waitForMapToRender()
        }

        this.then("a point inside the airspace should be detected") {
            ReportGenerator.logStep("VERIFY", "Checking point inside: $insidePoint")
            val isInside = airspacePolygon?.let { GeoJsonUtils.isPointInPolygon(it, insidePoint) } ?: false
            assertTrue("Point $insidePoint should be inside the airspace", isInside)
        }

        this.then("a point outside the airspace should not be detected") {
            ReportGenerator.logStep("VERIFY", "Checking point outside: $outsidePoint")
            val isInside = airspacePolygon?.let { GeoJsonUtils.isPointInPolygon(it, outsidePoint) } ?: false
            assertFalse("Point $outsidePoint should be outside the airspace", isInside)
        }
    }
}
