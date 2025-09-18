package com.madanala.tern.utils

import android.graphics.Color
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Utility class for handling GeoJSON data with osmdroid maps
 */
object GeoJsonUtils {

    private val objectMapper = ObjectMapper()
    private val httpClient = OkHttpClient()

    /**
     * GeoJSON Feature Collection structure
     */
    data class GeoJsonFeatureCollection(
        val type: String,
        val features: List<GeoJsonFeature>
    )

    /**
     * GeoJSON Feature structure
     */
    data class GeoJsonFeature(
        val type: String,
        val geometry: GeoJsonGeometry,
        val properties: Map<String, Any>? = null
    )

    /**
     * GeoJSON Geometry structure
     */
    data class GeoJsonGeometry(
        val type: String,
        val coordinates: Any
    )

    /**
     * Parse GeoJSON string and return FeatureCollection
     */
    fun parseGeoJson(jsonString: String): GeoJsonFeatureCollection? {
        return try {
            objectMapper.readValue(jsonString, GeoJsonFeatureCollection::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Add GeoJSON features to the map as overlays
     */
    fun addGeoJsonToMap(mapView: MapView, geoJsonString: String): List<Any> {
        val overlays = mutableListOf<Any>()
        val featureCollection = parseGeoJson(geoJsonString) ?: return overlays

        for (feature in featureCollection.features) {
            when (feature.geometry.type) {
                "Point" -> {
                    val pointOverlay = createPointOverlay(mapView, feature)
                    pointOverlay?.let { overlays.add(it) }
                }
                "LineString" -> {
                    val lineOverlay = createLineStringOverlay(mapView, feature)
                    lineOverlay?.let { overlays.add(it) }
                }
                "Polygon" -> {
                    val polygonOverlay = createPolygonOverlay(mapView, feature)
                    polygonOverlay?.let { overlays.add(it) }
                }
                "MultiPoint" -> {
                    val pointOverlays = createMultiPointOverlay(mapView, feature)
                    overlays.addAll(pointOverlays)
                }
                "MultiLineString" -> {
                    val lineOverlays = createMultiLineStringOverlay(mapView, feature)
                    overlays.addAll(lineOverlays)
                }
                "MultiPolygon" -> {
                    val polygonOverlays = createMultiPolygonOverlay(mapView, feature)
                    overlays.addAll(polygonOverlays)
                }
            }
        }

        // Add all overlays to map
        overlays.forEach { overlay ->
            when (overlay) {
                is Marker -> mapView.overlays.add(overlay)
                is Polyline -> mapView.overlays.add(overlay)
                is Polygon -> mapView.overlays.add(overlay)
            }
        }

        mapView.invalidate()
        return overlays
    }

    private fun createPointOverlay(mapView: MapView, feature: GeoJsonFeature): Marker? {
        return try {
            val coordinates = feature.geometry.coordinates as? List<Double> ?: return null
            if (coordinates.size < 2) return null

            val geoPoint = GeoPoint(coordinates[1], coordinates[0]) // GeoJSON is [lng, lat]
            Marker(mapView).apply {
                position = geoPoint
                title = feature.properties?.get("name") as? String ?: "Point"
                snippet = feature.properties?.get("description") as? String
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createLineStringOverlay(mapView: MapView, feature: GeoJsonFeature): Polyline? {
        return try {
            val coordinates = feature.geometry.coordinates as? List<List<Double>> ?: return null
            val geoPoints = coordinates.mapNotNull { coord ->
                if (coord.size >= 2) GeoPoint(coord[1], coord[0]) else null
            }

            if (geoPoints.size < 2) return null

            Polyline(mapView).apply {
                setPoints(geoPoints)
                color = Color.BLUE
                width = 5f
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createPolygonOverlay(mapView: MapView, feature: GeoJsonFeature): Polygon? {
        return try {
            val coordinates = feature.geometry.coordinates as? List<List<List<Double>>> ?: return null
            if (coordinates.isEmpty()) return null

            // Take the first ring (outer boundary)
            val outerRing = coordinates[0]
            val geoPoints = outerRing.mapNotNull { coord ->
                if (coord.size >= 2) GeoPoint(coord[1], coord[0]) else null
            }

            if (geoPoints.size < 3) return null

            Polygon(mapView).apply {
                points = geoPoints
                fillColor = Color.argb(100, 0, 255, 0) // Semi-transparent green
                strokeColor = Color.GREEN
                strokeWidth = 3f
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createMultiPointOverlay(mapView: MapView, feature: GeoJsonFeature): List<Marker> {
        return try {
            val coordinates = feature.geometry.coordinates as? List<List<Double>> ?: return emptyList()
            coordinates.mapNotNull { coord ->
                if (coord.size >= 2) {
                    val geoPoint = GeoPoint(coord[1], coord[0])
                    Marker(mapView).apply {
                        position = geoPoint
                        title = feature.properties?.get("name") as? String ?: "Point"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun createMultiLineStringOverlay(mapView: MapView, feature: GeoJsonFeature): List<Polyline> {
        return try {
            val coordinates = feature.geometry.coordinates as? List<List<List<Double>>> ?: return emptyList()
            coordinates.mapNotNull { lineCoords ->
                val geoPoints = lineCoords.mapNotNull { coord ->
                    if (coord.size >= 2) GeoPoint(coord[1], coord[0]) else null
                }
                if (geoPoints.size >= 2) {
                    Polyline(mapView).apply {
                        setPoints(geoPoints)
                        color = Color.BLUE
                        width = 5f
                    }
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun createMultiPolygonOverlay(mapView: MapView, feature: GeoJsonFeature): List<Polygon> {
        return try {
            val coordinates = feature.geometry.coordinates as? List<List<List<List<Double>>>> ?: return emptyList()
            coordinates.mapNotNull { polygonCoords ->
                if (polygonCoords.isNotEmpty()) {
                    val outerRing = polygonCoords[0]
                    val geoPoints = outerRing.mapNotNull { coord ->
                        if (coord.size >= 2) GeoPoint(coord[1], coord[0]) else null
                    }
                    if (geoPoints.size >= 3) {
                        Polygon(mapView).apply {
                            points = geoPoints
                            fillColor = Color.argb(100, 0, 255, 0)
                            strokeColor = Color.GREEN
                            strokeWidth = 3f
                        }
                    } else null
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Parse NDGeoJSON (Newline Delimited GeoJSON) string
     */
    fun parseNdGeoJson(ndGeoJsonString: String): GeoJsonFeatureCollection {
        val features = mutableListOf<GeoJsonFeature>()

        // Split by newlines and parse each line as a separate GeoJSON feature
        val lines = ndGeoJsonString.trim().split("\n").filter { it.isNotBlank() }

        for (line in lines) {
            try {
                val feature = objectMapper.readValue(line, GeoJsonFeature::class.java)
                features.add(feature)
            } catch (e: Exception) {
                // Skip malformed lines
                continue
            }
        }

        return GeoJsonFeatureCollection("FeatureCollection", features)
    }

    /**
     * Download GeoJSON from URL
     */
    suspend fun downloadGeoJson(url: String): String = suspendCoroutine { continuation ->
        try {
            val request = Request.Builder()
                .url(url)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    continuation.resumeWithException(Exception("HTTP ${response.code}: ${response.message}"))
                    return@use
                }

                val body = response.body?.string()
                if (body != null) {
                    continuation.resume(body)
                } else {
                    continuation.resumeWithException(Exception("Empty response body"))
                }
            }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    /**
     * Add NDGeoJSON features to the map as airspace overlays
     */
    fun addAirspaceToMap(mapView: MapView, ndGeoJsonString: String): List<Polygon> {
        val polygons = mutableListOf<Polygon>()
        val featureCollection = parseNdGeoJson(ndGeoJsonString)

        for (feature in featureCollection.features) {
            if (feature.geometry.type == "Polygon") {
                val polygon = createAirspacePolygon(mapView, feature)
                polygon?.let { polygons.add(it) }
            }
        }

        // Add all polygons to map
        polygons.forEach { mapView.overlays.add(it) }
        mapView.invalidate()

        return polygons
    }

    /**
     * Create airspace polygon with appropriate styling
     */
    private fun createAirspacePolygon(mapView: MapView, feature: GeoJsonFeature): Polygon? {
        return try {
            val coordinates = feature.geometry.coordinates as? List<List<List<Double>>> ?: return null
            if (coordinates.isEmpty()) return null

            // Take the first ring (outer boundary)
            val outerRing = coordinates[0]
            val geoPoints = outerRing.mapNotNull { coord ->
                if (coord.size >= 2) GeoPoint(coord[1], coord[0]) else null
            }

            if (geoPoints.size < 3) return null

            // Get airspace class from properties to determine color
            val airspaceClass = feature.properties?.get("class") as? String ?: "UNKNOWN"
            val (fillColor, strokeColor) = getAirspaceColors(airspaceClass)

            Polygon(mapView).apply {
                points = geoPoints
                this.fillColor = fillColor
                this.strokeColor = strokeColor
                strokeWidth = 2f
                title = feature.properties?.get("name") as? String ?: airspaceClass
                snippet = buildAirspaceInfo(feature.properties)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get appropriate colors for different airspace classes
     */
    private fun getAirspaceColors(airspaceClass: String): Pair<Int, Int> {
        return when (airspaceClass.uppercase()) {
            "A" -> Color.argb(60, 255, 0, 0) to Color.RED        // Class A - Red
            "B" -> Color.argb(60, 255, 165, 0) to Color.rgb(255, 140, 0)  // Class B - Orange
            "C" -> Color.argb(60, 255, 255, 0) to Color.YELLOW   // Class C - Yellow
            "D" -> Color.argb(60, 0, 255, 0) to Color.GREEN      // Class D - Green
            "E" -> Color.argb(60, 0, 0, 255) to Color.BLUE       // Class E - Blue
            "G" -> Color.argb(60, 128, 128, 128) to Color.GRAY  // Class G - Gray
            "RESTRICTED", "PROHIBITED", "DANGER" -> Color.argb(80, 255, 0, 0) to Color.RED
            else -> Color.argb(40, 128, 128, 128) to Color.DKGRAY // Unknown - Gray
        }
    }

    /**
     * Build airspace information string from properties
     */
    private fun buildAirspaceInfo(properties: Map<String, Any>?): String? {
        if (properties == null) return null

        val info = StringBuilder()
        properties["class"]?.let { info.append("Class: $it\n") }
        properties["name"]?.let { info.append("Name: $it\n") }
        properties["lower"]?.let { info.append("Lower: $it\n") }
        properties["upper"]?.let { info.append("Upper: $it") }

        return if (info.isNotEmpty()) info.toString() else null
    }

    /**
     * Clear all GeoJSON overlays from the map
     */
    fun clearGeoJsonOverlays(mapView: MapView) {
        mapView.overlays.removeAll { overlay ->
            overlay is Marker || overlay is Polyline || overlay is Polygon
        }
        mapView.invalidate()
    }
}
