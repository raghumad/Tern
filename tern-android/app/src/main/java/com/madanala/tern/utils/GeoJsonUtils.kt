package com.madanala.tern.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Marker
import java.io.IOException

object GeoJsonUtils {

    private val client = OkHttpClient()
    private val mapper = jacksonObjectMapper()

    /**
     * Download GeoJSON data from a URL
     * @param url The URL to download from
     * @return The GeoJSON data as a string, or null if download failed
     */
    suspend fun downloadGeoJson(url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body.string()
                } else {
                    null
                }
            } catch (_: IOException) {
                null
            }
        }
    }





    /**
     * Add airspace data to the map with distance filtering
     * @param mapView The MapView to add the data to
     * @param ndGeoJsonString The newline-delimited GeoJSON data
     * @param centerPoint The center point to filter around
     * @param maxDistanceMiles Maximum distance in miles from center point
     * @return A list of Polygon overlays that were added
     */
    fun addAirspaceToMapFiltered(
        mapView: MapView,
        ndGeoJsonString: String,
        centerPoint: GeoPoint,
        maxDistanceMiles: Double = 300.0
    ): List<Polygon> {
        val polygons = mutableListOf<Polygon>()
        val lines = ndGeoJsonString.lines()

        lines.forEachIndexed { index, line ->
            if (line.isNotBlank()) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val feature = mapper.readValue<Map<String, Any>>(line)
                    @Suppress("UNCHECKED_CAST")
                    val geometry = feature["geometry"] as? Map<String, Any>
                    if (geometry != null) {
                        val overlay = createOverlayFromGeometry(mapView, geometry)
                        if (overlay is Polygon) {
                            // Check if polygon is within distance
                            if (isPolygonWithinDistance(overlay, centerPoint, maxDistanceMiles)) {
                                // Customize polygon appearance for airspaces
                                overlay.fillPaint.color = 0x40FF0000 // Semi-transparent red
                                overlay.outlinePaint.color = 0xFFFF0000.toInt()
                                overlay.outlinePaint.strokeWidth = 2f
                                mapView.overlays.add(overlay)
                                polygons.add(overlay)
                                @Suppress("DEPRECATION")
                                val firstPoint = overlay.points?.firstOrNull()
                                if (firstPoint != null) {
                                    println("DEBUG: Added filtered polygon at ${firstPoint.latitude}, ${firstPoint.longitude}")
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val firstPoint = overlay.points?.firstOrNull()
                                if (firstPoint != null) {
                                    println("DEBUG: Filtered out polygon at ${firstPoint.latitude}, ${firstPoint.longitude} - too far from center")
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip malformed lines
                }
            }
        }
        mapView.invalidate()
        return polygons
    }

    /**
     * Check if a polygon is within a certain distance from a center point
     * @param polygon The polygon to check
     * @param centerPoint The center point
     * @param maxDistanceMiles Maximum distance in miles
     * @return true if any part of the polygon is within the distance
     */
    @Suppress("DEPRECATION")
    private fun isPolygonWithinDistance(
        polygon: Polygon,
        centerPoint: GeoPoint,
        maxDistanceMiles: Double
    ): Boolean {
        // Check if any vertex of the polygon is within the distance
        // For better performance, we could check the centroid or bounding box
        val maxDistanceMeters = maxDistanceMiles * 1609.34 // Convert miles to meters
        return polygon.points?.any { point ->
            point != null && centerPoint.distanceToAsDouble(point) <= maxDistanceMeters
        } ?: false
    }

    /**
     * Check if a polygon is within the map viewport
     * @param polygon The polygon to check
     * @param boundingBox The map's bounding box
     * @return true if any part of the polygon is visible in the viewport
     */
    @Suppress("DEPRECATION")
    private fun isPolygonInViewport(polygon: Polygon, boundingBox: org.osmdroid.util.BoundingBox): Boolean {
        // Check if any vertex of the polygon is within the viewport
        return polygon.points?.any { point ->
            point != null && boundingBox.contains(point)
        } ?: false
    }

    /**
     * Create an osmdroid Overlay from a GeoJSON geometry
     * @param mapView The MapView (needed for Marker context)
     * @param geometry The GeoJSON geometry object
     * @return An Overlay (Polygon or Marker), or null if geometry type is unsupported
     */
    private fun createOverlayFromGeometry(mapView: MapView, geometry: Map<String, Any>): Overlay? {
        val type = geometry["type"] as? String
        val coordinates = geometry["coordinates"] as? List<*>

        return when (type) {
            "Polygon" -> {
                val polygon = Polygon(mapView)
                val outerRing = coordinates?.get(0) as? List<*>
                val points: List<GeoPoint> = outerRing?.mapNotNull { coord ->
                    try {
                        val lonLat = coord as List<*>
                        if (lonLat.size >= 2) {
                            // GeoJSON is [longitude, latitude], GeoPoint is (latitude, longitude)
                            GeoPoint(lonLat[1] as Double, lonLat[0] as Double)
                        } else null
                } catch (_: Exception) {
                    null
                }
                } ?: emptyList()

                @Suppress("KotlinConstantConditions")
                if (points.size >= 3) {
                    @Suppress("DEPRECATION")
                    polygon.points = points
                    // Set default styling for polygons
                    polygon.fillPaint.color = 0x40FF0000 // Semi-transparent red
                    polygon.outlinePaint.color = 0xFFFF0000.toInt()
                    polygon.outlinePaint.strokeWidth = 2f
                    val firstPoint = points.firstOrNull()
                    if (firstPoint != null) {
                        println("DEBUG: Created polygon with ${points.size} points, center at ${firstPoint.latitude}, ${firstPoint.longitude}")
                    }
                    polygon
                } else {
                    println("DEBUG: Failed to create polygon - points: ${points.size}, valid: ${points.size >= 3}")
                    null
                }
            }
            "Point" -> {
                val marker = Marker(mapView)
                try {
                    val lonLat = coordinates as List<*>
                    if (lonLat.size >= 2) {
                        marker.position = GeoPoint(lonLat[1] as Double, lonLat[0] as Double)
                        marker
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
            // Add support for other geometry types like LineString, MultiPolygon etc. as needed
            else -> null
        }
    }

    /**
     * Clear all GeoJSON overlays from the map
     * @param mapView The MapView to clear
     */
    fun clearGeoJsonOverlays(mapView: MapView) {
        // A bit of a hack: We identify our overlays by their type.
        // This could be improved by using a custom Overlay subclass.
        mapView.overlays.removeAll { it is Polygon || it is Marker }
        mapView.invalidate()
    }

    /**
     * Remove airspaces that are outside the current map viewport
     * @param mapView The MapView to filter
     * @return Number of polygons removed
     */
    fun removeAirspacesOutsideViewport(mapView: MapView): Int {
        val boundingBox = mapView.boundingBox ?: return 0

        val polygonsToRemove = mapView.overlays.filter { overlay ->
            overlay is Polygon && !isPolygonInViewport(overlay, boundingBox)
        }

        mapView.overlays.removeAll(polygonsToRemove)
        if (polygonsToRemove.isNotEmpty()) {
            mapView.invalidate()
            println("DEBUG: Removed ${polygonsToRemove.size} polygons outside viewport")
        }

        return polygonsToRemove.size
    }

    /**
     * Remove airspaces that are outside the specified distance from center
     * @param mapView The MapView to filter
     * @param centerPoint The center point to measure distance from
     * @param maxDistanceMiles Maximum distance in miles from center point
     * @return Number of polygons removed
     */
    fun removeAirspacesOutsideRadius(
        mapView: MapView,
        centerPoint: GeoPoint,
        maxDistanceMiles: Double
    ): Int {
        val polygonsToRemove = mapView.overlays.filter { overlay ->
            overlay is Polygon && !isPolygonWithinDistance(overlay, centerPoint, maxDistanceMiles)
        }

        mapView.overlays.removeAll(polygonsToRemove)
        if (polygonsToRemove.isNotEmpty()) {
            mapView.invalidate()
            println("DEBUG: Removed ${polygonsToRemove.size} polygons outside ${maxDistanceMiles} mile radius")
        }

        return polygonsToRemove.size
    }
}
