@file:Suppress("SENSELESS_COMPARISON", "DEPRECATION")
package com.madanala.tern.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Marker
import android.util.Log
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
     * Download GeoJSON data from a URL with proper resource management and validation
     * @param url The URL to download from
     * @return The GeoJSON data as a string, or null if download failed or content is invalid
     */
    suspend fun downloadGeoJson(url: String, userAgent: String? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.i("GeoJsonUtils", "Executing HTTP download for: $url")
                val requestBuilder = Request.Builder().url(url)
                if (userAgent != null) {
                    requestBuilder.header("User-Agent", userAgent)
                }
                val request = requestBuilder.build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val data = body.string()
                            Log.d("GeoJsonUtils", "Downloaded ${data.length} bytes from $url")

                            // VALIDATE: Check if downloaded content is valid JSON/GeoJSON
                            if (validateGeoJsonContent(data, url)) {
                                Log.d("GeoJsonUtils", "✅ Downloaded content validated for $url")
                                data
                            } else {
                                Log.w("GeoJsonUtils", "❌ Downloaded content failed validation for $url")
                                null
                            }
                        } else {
                            Log.w("GeoJsonUtils", "Response body is null for $url")
                            null
                        }
                    } else {
                        Log.w("GeoJsonUtils", "Failed to download from $url: ${response.code} ${response.message}")
                        null
                    }
                }
            } catch (e: IOException) {
                Log.w("GeoJsonUtils", "IOException downloading $url: ${e.message}")
                null
            }
        }
    }

    /**
     * Download GeoJSON data and stream features one by one directly from the byte stream.
     * This avoids massive String/Map allocations in the JVM Heap.
     */
    suspend fun streamGeoJsonFeatures(url: String, processFeature: (Map<String, Any>) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i("GeoJsonUtils", "Executing HTTP streaming download for: $url")
                val request = Request.Builder().url(url).build()
                var success = false
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val isNd = url.contains("ndgeojson") || url.contains("_asp.geojson")
                            if (isNd) {
                                // NDGeoJSON: process line by line
                                body.byteStream().bufferedReader().useLines { lines ->
                                    for (line in lines) {
                                        if (!isActive) break
                                        if (line.isNotBlank()) {
                                            try {
                                                @Suppress("UNCHECKED_CAST")
                                                val feature = mapper.readValue<Map<String, Any>>(line, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any>>() {})
                                                processFeature(feature)
                                            } catch (e: Exception) {
                                                // Ignore malformed lines silently on streams
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Standard GeoJSON FeatureCollection
                                val parser = mapper.factory.createParser(body.byteStream())
                                var inFeaturesArray = false
                                while (!parser.isClosed && isActive) {
                                    val token = parser.nextToken()
                                    if (token == null) break

                                    if (!inFeaturesArray) {
                                        if (token == com.fasterxml.jackson.core.JsonToken.FIELD_NAME && parser.currentName == "features") {
                                            if (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
                                                inFeaturesArray = true
                                            }
                                        }
                                    } else {
                                        if (token == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                                            try {
                                                val feature: Map<String, Any> = mapper.readValue(parser, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any>>() {})
                                                processFeature(feature)
                                            } catch (e: Exception) {
                                                Log.w("GeoJsonUtils", "Failed to parse streamed feature object: ${e.message}")
                                            }
                                        } else if (token == com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                                            break
                                        }
                                    }
                                }
                                parser.close()
                            }
                            Log.d("GeoJsonUtils", "✅ Streaming download completed for $url")
                            success = true
                        } else {
                            Log.w("GeoJsonUtils", "Response body is null for $url")
                        }
                    } else {
                        Log.w("GeoJsonUtils", "Failed to download from $url: ${response.code} ${response.message}")
                    }
                }
                success
            } catch (e: Exception) {
                Log.w("GeoJsonUtils", "Exception streaming $url: ${e.message}")
                false
            }
        }
    }

    /**
     * Validate that downloaded content is valid GeoJSON
     */
    private fun validateGeoJsonContent(content: String, url: String): Boolean {
        if (content.isEmpty()) {
            Log.w("GeoJsonUtils", "Content is empty for $url")
            return false
        }

        // Check minimum size (empty or very small files are likely corrupted)
        if (content.length < 50) {
            Log.w("GeoJsonUtils", "Content too small (${content.length} bytes) for $url")
            return false
        }

        // Check for basic JSON structure
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            Log.w("GeoJsonUtils", "Content doesn't start with valid JSON structure for $url")
            return false
        }

        // For NDGeoJSON files (newline-delimited), check if it's properly formatted
        // Also handle _asp.geojson files which are NDGeoJSON
        if (url.contains("ndgeojson") || url.contains("_asp.geojson")) {
            return validateNdGeoJsonContent(content, url)
        }

        // For standard GeoJSON files, do basic JSON validation
        return try {
            // Try to parse as JSON to ensure it's not corrupted
            mapper.readTree(content)
            true
        } catch (e: Exception) {
            android.util.Log.w("GeoJsonUtils", "Invalid JSON structure for $url: ${e.message}")
            false
        }
    }

    /**
     * Check if content is likely NDGeoJSON (Newline Delimited GeoJSON)
     */
    fun isNdGeoJson(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        
        // If it starts with a JSON object '{' and has multiple lines, check if the first line is a Feature
        // Standard GeoJSON FeatureCollection also starts with '{', but usually the whole file is one JSON object
        // NDGeoJSON has multiple independent JSON objects, one per line
        
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) {
            // Single line: ambiguous. If it's a FeatureCollection, it's standard.
            // If it's a Feature, it could be either (but usually standard GeoJSON is FeatureCollection)
            // Let's assume single line starting with { "type": "FeatureCollection" ... } is Standard
            return !trimmed.contains("\"type\"\\s*:\\s*\"FeatureCollection\"".toRegex())
        }
        
        // Multiple lines: Check if first line is a valid JSON object (Feature)
        // and NOT a FeatureCollection start
        val firstLine = lines[0].trim()
        
        try {
            // Try to parse the first line as a JSON object
            val jsonNode = mapper.readTree(firstLine)
            
            // If it parses successfully, it's either a single-line standard GeoJSON or a line of NDGeoJSON
            // If it's a FeatureCollection, it's standard.
            if (jsonNode.has("type") && jsonNode.get("type").asText() == "FeatureCollection") {
                return false
            }
            
            // If it's a Feature (or other object) and on a single line (in a multi-line file), 
            // it's likely NDGeoJSON (where each line is a Feature).
            // However, if the file has only one line, it's ambiguous but we treat single Feature as NDGeoJSON-compatible.
            return true
            
        } catch (e: Exception) {
            // If the first line is NOT a valid JSON object (e.g. just "{"), 
            // it's likely a pretty-printed standard GeoJSON.
            return false
        }
    }

    /**
     * Validate NDGeoJSON (newline-delimited GeoJSON) content
     */
    private fun validateNdGeoJsonContent(content: String, url: String): Boolean {
        val lines = content.lines()

        if (lines.isEmpty()) {
            android.util.Log.w("GeoJsonUtils", "NDGeoJSON has no lines for $url")
            return false
        }

        var validLines = 0
        var invalidLines = 0

        // Check first few lines and some random samples
        val linesToCheck = minOf(50, lines.size) // Check up to 50 lines

        for (i in 0 until linesToCheck) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            if (isValidGeoJsonLine(line)) {
                validLines++
            } else {
                invalidLines++
            }
        }

        // Calculate validity ratio
        val totalChecked = validLines + invalidLines
        val validityRatio = if (totalChecked > 0) validLines.toDouble() / totalChecked else 0.0

        android.util.Log.d("GeoJsonUtils", "NDGeoJSON validation for $url: $validLines valid, $invalidLines invalid (${String.format("%.1f%%", validityRatio * 100)})")

        // Require at least 80% valid lines for acceptance
        return validityRatio >= 0.8
    }

    /**
     * Check if a single line is valid GeoJSON
     */
    private fun isValidGeoJsonLine(line: String): Boolean {
        if (line.isEmpty()) return false

        return try {
            // Should be a valid JSON object starting with "{"
            line.trim().startsWith("{") && mapper.readTree(line) != null
        } catch (e: Exception) {
            false
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
        maxDistanceMiles: Double = com.madanala.tern.redux.OverlayConstants.DEFAULT_FILTER_RADIUS_MILES
    ): List<Polygon> {
        val polygons = mutableListOf<Polygon>()
        val lines = ndGeoJsonString.lines()

        lines.forEachIndexed { index, line ->
            if (line.isNotBlank()) {
                try {
                    // The following lines are added based on the user's request, assuming this is the context
                    // where a featureMap is processed after being streamed.
                    // Note: The original request snippet seemed to be from a call site of streamGeoJsonFeatures,
                    // but this is the most logical place within GeoJsonUtils itself to add such debug output
                    // for a streamed feature.
                    println("DEBUG: AirspaceCache Streaming feature: $line") // Using 'line' as featureMap equivalent
                    Log.d("GeoJsonUtils", "Streaming feature: $line") // Using 'line' as featureMap equivalent
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

        // Log for debugging
        // Log.d("GeoJsonUtils", "Airspace Class: $finalAirspaceClass (Raw: $airspaceClass, Props: $properties)")

        // For paraglider pilots (FAR 103), skip Class G airspaces as they're not restricted
        if (finalAirspaceClass == "G" || finalAirspaceClass == "CLASS_G") {
             Log.d("GeoJsonUtils", "Skipping Class G Airspace: $properties")
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
     * Check if a point is strictly inside a polygon using Ray Casting algorithm
     * @param polygon The polygon to check
     * @param point The point to check
     * @return true if the point is inside the polygon
     */
    fun isPointInPolygon(polygon: Polygon, point: GeoPoint): Boolean {
        val points = polygon.points ?: return false
        var result = false
        var j = points.size - 1
        for (i in points.indices) {
            if ((points[i].latitude > point.latitude) != (points[j].latitude > point.latitude) &&
                (point.longitude < (points[j].longitude - points[i].longitude) * (point.latitude - points[i].latitude) / (points[j].latitude - points[i].latitude) + points[i].longitude)) {
                result = !result
            }
            j = i
        }
        return result
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
     * Create airspace overlays incrementally using a pooled polygon if provided
     */
    fun createAirspaceOverlaysIncrementally(
        mapView: MapView,
        features: List<MapOverlayCacheUtils.OverlayFeature>,
        reusablePolygon: Polygon? = null
    ): List<Polygon> {
        val polygons = mutableListOf<Polygon>()

        features.forEach { feature ->
            try {
                val geometry = feature.feature["geometry"]
                if (geometry is Map<*, *> && geometry.all { it.key is String && it.value is Any }) {
                    @Suppress("UNCHECKED_CAST")
                    val geometryMap = geometry as Map<String, Any>
                    
                    // Use reusablePolygon if provided and it's the first feature
                    val overlay = if (reusablePolygon != null && polygons.isEmpty()) {
                        populatePolygonFromGeometry(reusablePolygon, geometryMap)
                        reusablePolygon
                    } else {
                        createOverlayFromGeometry(mapView, geometryMap)
                    }
                    
                    if (overlay is Polygon) {
                        val shouldRender = applyAirspaceStyling(overlay, feature.feature)
                        if (shouldRender) {
                            overlay.setOnClickListener { polygon, mapView, eventPos ->
                                showAirspaceInfoBalloon(polygon, mapView, feature.feature)
                                true
                            }
                            polygons.add(overlay)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return polygons
    }

    /**
     * Populate an existing Polygon from a GeoJSON geometry map
     */
    private fun populatePolygonFromGeometry(polygon: Polygon, geometry: Map<String, Any>) {
        val coordinates = geometry["coordinates"] as? List<*>
        val outerRing = coordinates?.get(0) as? List<*>
        val points: List<GeoPoint> = outerRing?.mapNotNull { coord ->
            try {
                val lonLat = coord as List<*>
                if (lonLat.size >= 2) {
                    val lon = (lonLat[0] as Number).toDouble()
                    val lat = (lonLat[1] as Number).toDouble()
                    GeoPoint(lat, lon)
                } else null
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()

        if (points.size >= 3) {
            @Suppress("DEPRECATION")
            polygon.points = points
        }
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
                            val lon = (lonLat[0] as Number).toDouble()
                            val lat = (lonLat[1] as Number).toDouble()
                            GeoPoint(lat, lon)
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
                        val lon = (lonLat[0] as Number).toDouble()
                        val lat = (lonLat[1] as Number).toDouble()
                        marker.position = GeoPoint(lat, lon)
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
