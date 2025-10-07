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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)    // Increased from 10s
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)       // Increased from 10s for large files
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)      // Increased from 10s
        .retryOnConnectionFailure(true)                               // Enable automatic retries
        .build()
    private val mapper = jacksonObjectMapper()

    /**
     * Download GeoJSON data from a URL with proper resource management
     * @param url The URL to download from
     * @return The GeoJSON data as a string, or null if download failed
     */
    suspend fun downloadGeoJson(url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val data = body.string()
                            android.util.Log.d("GeoJsonUtils", "Downloaded ${data.length} bytes from $url")
                            data
                        } else {
                            android.util.Log.w("GeoJsonUtils", "Response body is null for $url")
                            null
                        }
                    } else {
                        android.util.Log.w("GeoJsonUtils", "Failed to download from $url: ${response.code} ${response.message}")
                        null
                    }
                }
            } catch (e: IOException) {
                android.util.Log.w("GeoJsonUtils", "IOException downloading $url: ${e.message}")
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
                                // Customize polygon appearance for airspaces - made more translucent
                                overlay.fillPaint.color = 0x20FF0000 // More translucent red (12.5% opacity)
                                overlay.outlinePaint.color = 0xFFFF0000.toInt()
                                overlay.outlinePaint.strokeWidth = 2f
                                mapView.overlays.add(overlay)
                                polygons.add(overlay)
                                @Suppress("DEPRECATION")
                                val firstPoint = overlay.points?.firstOrNull()
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
     * Create airspace overlays without adding them to map (for animation manager)
     * @param mapView The MapView (needed for overlay creation context)
     * @param features List of AirspaceFeature to create overlays for
     * @return A list of Polygon overlays that were created (but not added to map)
     */
    fun createAirspaceOverlays(
        mapView: MapView,
        features: List<MapOverlayCacheUtils.OverlayFeature>
    ): List<Polygon> {
        val polygons = mutableListOf<Polygon>()

        features.forEach { feature ->
            try {
                val geometry = feature.feature["geometry"]
                if (geometry is Map<*, *> && geometry.all { it.key is String && it.value is Any }) {
                    @Suppress("UNCHECKED_CAST")
                    val geometryMap = geometry as Map<String, Any>
                    val overlay = createOverlayFromGeometry(mapView, geometryMap)
                    if (overlay is Polygon) {
                        // Apply color coding based on airspace class
                        // For paragliders, skip Class G airspaces entirely
                        val shouldRender = applyAirspaceStyling(overlay, feature.feature)
                        if (shouldRender) {
                            // Add click listener to show airspace information
                            overlay.setOnClickListener { polygon, mapView, eventPos ->
                                showAirspaceInfoBalloon(polygon, mapView, feature.feature)
                                true // Consume the click
                            }
                            polygons.add(overlay)
                        }
                        // If shouldRender is false, the airspace is skipped (like Class G)
                    }
                }
            } catch (_: Exception) {
                // Skip malformed features
            }
        }
        return polygons
    }

    /**
     * Add airspace features to the map with color coding based on airspace class (legacy method)
     * @param mapView The MapView to add the data to
     * @param features List of AirspaceFeature to add
     * @return A list of Polygon overlays that were added
     * @deprecated Use createAirspaceOverlays() with animation manager instead
     */
    @Deprecated("Use createAirspaceOverlays() with animation manager for proper fade-in animation")
    fun addAirspaceFeaturesToMap(
        mapView: MapView,
        features: List<MapOverlayCacheUtils.OverlayFeature>
    ): List<Polygon> {
        val polygons = createAirspaceOverlays(mapView, features)

        // Add all overlays immediately (no animation)
        // Note: Overlays are not added here anymore - animation manager handles addition
        // This maintains backward compatibility for external callers
        polygons.forEach { polygon ->
            mapView.overlays.add(polygon)
        }
        mapView.invalidate()
        return polygons
    }

    /**
     * Apply appropriate styling to airspace polygons based on their class/type
     * For paraglider pilots (FAR 103 ultralight vehicles), we only show airspaces they need to avoid
     * @param polygon The polygon to style
     * @param feature The GeoJSON feature containing properties
     * @return true if the airspace should be rendered, false if it should be skipped (like Class G)
     */
    private fun applyAirspaceStyling(polygon: Polygon, feature: Map<String, Any>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val properties = feature["properties"] as? Map<String, Any> ?: emptyMap()

        // Only check properties for airspace-related fields
        // Don't check feature["type"] as that's the GeoJSON feature type ("Feature")
        val airspaceClass = properties?.get("class") as? String ?:
                           properties?.get("airspace_class") as? String ?:
                           properties?.get("category") as? String ?:
                           feature["class"] as? String ?:
                           feature["airspace_class"] as? String ?:
                           feature["category"] as? String

        // If we got a string, use it; otherwise try numeric fields
        val finalAirspaceClass = airspaceClass ?: run {
            // Check numeric type field (seems to be airspace type)
            val typeValue = properties?.get("type")
            val icaoClassValue = properties?.get("icaoClass")

            val typeNum = when (typeValue) {
                is Number -> typeValue.toDouble()
                else -> null
            }
            val icaoClassNum = when (icaoClassValue) {
                is Number -> icaoClassValue.toDouble()
                else -> null
            }

            when {
                // Map type values to airspace types (check type first, as it seems more specific)
                typeNum == 1.0 -> "RESTRICTED"
                typeNum == 2.0 -> "DANGER" // From the data, type=2 seems to be danger zones
                typeNum == 3.0 -> "PROHIBITED"
                typeNum == 4.0 -> "MILITARY"

                // Map icaoClass values to ICAO classes (fallback)
                icaoClassNum == 1.0 -> "A"
                icaoClassNum == 2.0 -> "B"
                icaoClassNum == 3.0 -> "C"
                icaoClassNum == 4.0 -> "D"
                icaoClassNum == 5.0 -> "E"
                icaoClassNum == 6.0 -> "F"
                icaoClassNum == 7.0 -> "G"
                icaoClassNum == 8.0 -> "G" // Special case, seems to be uncontrolled

                else -> "UNKNOWN"
            }
        }

        // For paraglider pilots (FAR 103), skip Class G airspaces as they're not restricted
        if (finalAirspaceClass == "G" || finalAirspaceClass == "CLASS_G") {
            return false // Don't render this airspace
        }

        // Standard ICAO airspace colors for airspaces paragliders need to avoid
        // Using more translucent alpha (0x40 = 25% opacity instead of 0x80 = 50%)
        when (finalAirspaceClass.uppercase()) {
            // Controlled airspace (Classes A-E): Bright Blue
            "A", "B", "C", "D", "E", "CLASS_A", "CLASS_B", "CLASS_C", "CLASS_D", "CLASS_E" -> {
                polygon.fillPaint.color = 0x400000FF.toInt() // More translucent bright blue
                polygon.outlinePaint.color = 0xFF0000FF.toInt() // Bright blue outline
                polygon.outlinePaint.strokeWidth = 3f
            }
            // Special use airspace: Yellow
            "RESTRICTED", "R", "RESTRICTED_AREA" -> {
                polygon.fillPaint.color = 0x40FFFF00.toInt() // More translucent yellow
                polygon.outlinePaint.color = 0xFFFFFF00.toInt() // Yellow outline
                polygon.outlinePaint.strokeWidth = 3f
            }
            "PROHIBITED", "P", "PROHIBITED_AREA" -> {
                polygon.fillPaint.color = 0x40FF8000.toInt() // More translucent orange
                polygon.outlinePaint.color = 0xFFFF8000.toInt() // Orange outline
                polygon.outlinePaint.strokeWidth = 4f
            }
            "DANGER", "D", "DANGER_AREA" -> {
                polygon.fillPaint.color = 0x40FF00FF.toInt() // More translucent magenta
                polygon.outlinePaint.color = 0xFFFF00FF.toInt() // Magenta outline
                polygon.outlinePaint.strokeWidth = 3f
            }
            "MILITARY", "M", "MILITARY_AREA" -> {
                polygon.fillPaint.color = 0x408000FF.toInt() // More translucent purple
                polygon.outlinePaint.color = 0xFF8000FF.toInt() // Purple outline
                polygon.outlinePaint.strokeWidth = 3f
            }
            // Default/fallback: Bright Red (different from original red)
            else -> {
                polygon.fillPaint.color = 0x40FF0000.toInt() // More translucent bright red fill
                polygon.outlinePaint.color = 0xFFFF0000.toInt() // Bright red outline
                polygon.outlinePaint.strokeWidth = 5f // Thick outline to make it obvious
            }
        }

        return true // Render this airspace
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
                    // Set default styling for polygons - made more translucent for consistency
                    polygon.fillPaint.color = 0x20FF0000 // More translucent red (12.5% opacity)
                    polygon.outlinePaint.color = 0xFFFF0000.toInt()
                    polygon.outlinePaint.strokeWidth = 2f
                    polygon
                } else {
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
        }

        return polygonsToRemove.size
    }

    /**
     * Show airspace information in a balloon/callout when clicked
     * @param polygon The clicked polygon
     * @param mapView The MapView
     * @param feature The GeoJSON feature containing airspace properties
     */
    private fun showAirspaceInfoBalloon(polygon: Polygon, mapView: MapView, feature: Map<String, Any>) {
        @Suppress("UNCHECKED_CAST")
        val properties = feature["properties"] as? Map<String, Any> ?: return

        // Extract airspace information with improved field detection
        val name = properties["name"] as? String ?: properties["Name"] as? String ?: "Unnamed Airspace"

        // Try multiple field variations for airspace class
        val airspaceClass = properties["class"] as? String ?:
                           properties["airspace_class"] as? String ?:
                           properties["category"] as? String ?:
                           // Try to extract from name if it contains class info (e.g., "FORT CARSON CLASS D")
                           name.uppercase().let { upperName ->
                               when {
                                   upperName.contains("CLASS A") -> "A"
                                   upperName.contains("CLASS B") -> "B"
                                   upperName.contains("CLASS C") -> "C"
                                   upperName.contains("CLASS D") -> "D"
                                   upperName.contains("CLASS E") -> "E"
                                   upperName.contains("CLASS F") -> "F"
                                   upperName.contains("CLASS G") -> "G"
                                   else -> null
                               }
                           } ?:
                           // Map numeric icaoClass to letter (fallback)
                           (properties["icaoClass"] as? Number)?.toInt()?.let { icaoNum ->
                               when (icaoNum) {
                                   1 -> "A"
                                   2 -> "B"
                                   3 -> "C"
                                   4 -> "D"
                                   5 -> "E"
                                   6 -> "F"
                                   7 -> "G"
                                   else -> null
                               }
                           } ?: "Unknown"

        // Try multiple field variations for type
        val type = properties["type"] as? String ?:
                  properties["airspace_type"] as? String ?:
                  properties["designator"] as? String ?:
                  properties["airspaceType"] as? String ?:
                  // Map numeric types to readable names
                  (properties["type"] as? Number)?.toInt()?.let { typeNum ->
                      when (typeNum) {
                          1 -> "RESTRICTED"
                          2 -> "DANGER"
                          3 -> "PROHIBITED"
                          4 -> "MILITARY"
                          else -> "CONTROLLED"
                      }
                  } ?:
                  (properties["airspaceType"] as? Number)?.toInt()?.let { typeNum ->
                      when (typeNum) {
                          1 -> "RESTRICTED"
                          2 -> "DANGER"
                          3 -> "PROHIBITED"
                          4 -> "MILITARY"
                          else -> "CONTROLLED"
                      }
                  } ?: "CONTROLLED"

        val country = properties["country"] as? String ?: properties["Country"] as? String ?: "Unknown"

        // Helper function to extract altitude with unit
        fun extractAltitudeWithUnit(altitudeObj: Map<*, *>?): String {
            if (altitudeObj == null) return "Unknown"

            val value = altitudeObj["value"]?.toString() ?: return "Unknown"
            val unit = (altitudeObj["unit"] as? Number)?.toInt() ?: 1 // Default to feet

            // Special case: Display "GND" for ground level (0 altitude, any unit)
            if (value == "0") {
                return "GND"
            }

            val unitName = when (unit) {
                1 -> "ft"
                2 -> "m"
                3 -> "FL" // Flight Level
                else -> "ft"
            }

            return "$value $unitName"
        }

        // Try multiple field variations for altitudes with units
        val ceiling = properties["ceiling"] as? String ?:
                     properties["upper"] as? String ?:
                     properties["ceiling_ft"] as? String ?:
                     properties["upper_ft"] as? String ?:
                     properties["upper_limit"] as? String ?:
                     properties["max_altitude"] as? String ?:
                     properties["ceiling_altitude"] as? String ?:
                     // Try nested objects (e.g., upperLimit: {value: 8400, unit: 1})
                     extractAltitudeWithUnit(properties["upperLimit"] as? Map<*, *>) ?:
                     extractAltitudeWithUnit(properties["ceilingLimit"] as? Map<*, *>) ?:
                     // Try numeric fields (assume feet if no unit specified)
                     (properties["ceiling"] as? Number)?.let { "${it.toInt()} ft" } ?:
                     (properties["upper"] as? Number)?.let { "${it.toInt()} ft" } ?:
                     "Unknown"

        val floor = properties["floor"] as? String ?:
                   properties["lower"] as? String ?:
                   properties["floor_ft"] as? String ?:
                   properties["lower_ft"] as? String ?:
                   properties["lower_limit"] as? String ?:
                   properties["min_altitude"] as? String ?:
                   properties["floor_altitude"] as? String ?:
                   // Try nested objects (e.g., lowerLimit: {value: 0, unit: 1})
                   extractAltitudeWithUnit(properties["lowerLimit"] as? Map<*, *>) ?:
                   extractAltitudeWithUnit(properties["floorLimit"] as? Map<*, *>) ?:
                   // Try numeric fields (assume feet if no unit specified)
                   (properties["floor"] as? Number)?.let { "${it.toInt()} ft" } ?:
                   (properties["lower"] as? Number)?.let { "${it.toInt()} ft" } ?:
                   "Unknown"

        // Create informative balloon text with HTML formatting for better readability
        val balloonText = "Class: $airspaceClass<br>Type: $type<br>Country: $country<br>Floor: $floor<br>Ceiling: $ceiling"

        // Use OSMDroid's built-in Marker InfoWindow approach
        // Create a temporary marker at the polygon center to show the info
        val tempMarker = Marker(mapView)

        // Find center of polygon for balloon position
        @Suppress("DEPRECATION")
        val centerPoint = polygon.points?.let { points ->
            if (points.isNotEmpty()) {
                val avgLat = points.map { it.latitude }.average()
                val avgLon = points.map { it.longitude }.average()
                GeoPoint(avgLat, avgLon)
            } else null
        } ?: GeoPoint(0.0, 0.0)

        tempMarker.position = centerPoint
        tempMarker.title = name
        tempMarker.snippet = balloonText
        tempMarker.setInfoWindow(MarkerInfoWindow(mapView))

        // Show the info window
        tempMarker.showInfoWindow()
    }

    /**
     * Custom InfoWindow for airspace markers
     */
    private class MarkerInfoWindow(mapView: MapView) : org.osmdroid.views.overlay.infowindow.MarkerInfoWindow(
        org.osmdroid.library.R.layout.bonuspack_bubble,
        mapView
    ) {
        override fun onOpen(item: Any?) {
            super.onOpen(item)
            // Customize the info window appearance if needed
        }
    }
}
