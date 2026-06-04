package com.ternparagliding.utils.cache

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import android.util.Log
import org.osmdroid.util.GeoPoint

/**
 * GeoJSON ingest for map overlays: parses ND-GeoJSON / FeatureCollection text
 * into [MapOverlayCacheUtils.OverlayFeature]s and computes feature centroids.
 *
 * Extracted from MapOverlayCacheUtils (Phase 0c god-file split) — the "ingest"
 * concern, distinct from the FlexBuffers codec + Hilbert math that remain in
 * MapOverlayCacheUtils. The central OverlayFeature model and the shared
 * computeHilbertIndex stay there and are referenced by qualified name.
 */
object OverlayGeoJsonParser {

    private const val TAG = "OverlayGeoJsonParser"
    private val mapper = jacksonObjectMapper()

    /**
     * Parse NDGeoJSON string to list of OverlayFeature
     */
    fun parseNdGeoJsonToFeatures(ndGeoJson: String, overlayType: String = "generic"): List<MapOverlayCacheUtils.OverlayFeature> {
        val features = mutableListOf<MapOverlayCacheUtils.OverlayFeature>()
        val lines = ndGeoJson.lines()

        Log.d(TAG, "Parsing ${lines.size} lines of NDGeoJSON for $overlayType")

        var processedCount = 0
        var skippedEmpty = 0
        var skippedMalformed = 0
        var skippedNoGeometry = 0
        var skippedNoCentroid = 0

        lines.forEach { line ->
            processedCount++
            if (line.isNotBlank()) {
                try {
                    val feature: Map<String, Any> = mapper.readValue(line)
                    @Suppress("UNCHECKED_CAST")
                    val geometry = feature["geometry"] as? Map<String, Any>
                    if (geometry != null) {
                        val centroid = computeCentroid(geometry)
                        if (centroid != null) {
                            val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, 16) // 16-bit precision
                            val id = feature["id"] as? String // Extract ID if present
                            features.add(MapOverlayCacheUtils.OverlayFeature(id, feature, centroid, hilbertIndex, overlayType))
                        } else {
                            skippedNoCentroid++
                        }
                    } else {
                        skippedNoGeometry++
                    }
                } catch (e: Exception) {
                    skippedMalformed++
                    if (skippedMalformed <= 5) { // Only log first few errors to avoid spam
                        Log.w(TAG, "Malformed line $processedCount: ${e.message}, line: '$line'")
                    }
                }
            } else {
                skippedEmpty++
            }
        }

        Log.d(TAG, "Parsed ${features.size} features. Skipped: empty=$skippedEmpty, malformed=$skippedMalformed, noGeometry=$skippedNoGeometry, noCentroid=$skippedNoCentroid")

        return features
    }

    /**
     * Parse standard GeoJSON FeatureCollection to list of OverlayFeature
     */
    fun parseGeoJsonToFeatures(geoJsonString: String, overlayType: String): List<MapOverlayCacheUtils.OverlayFeature> {
        val features = mutableListOf<MapOverlayCacheUtils.OverlayFeature>()

        try {
            val geoJson: Any = mapper.readValue(geoJsonString)

            if (geoJson is Map<*, *>) {
                val type = geoJson["type"] as? String
                if (type == "FeatureCollection") {
                    // Handle standard FeatureCollection
                    val featuresList = geoJson["features"] as? List<*>
                    featuresList?.forEach { featureObj ->
                        if (featureObj is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val featureMap = featureObj as Map<String, Any>
                            val feature = parseFeature(featureMap, overlayType)
                            if (feature != null) {
                                features.add(feature)
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Expected FeatureCollection, got $type")
                }
            } else {
                Log.w(TAG, "Unexpected GeoJSON format: ${geoJson.javaClass.simpleName}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GeoJSON", e)
        }

        return features
    }

    /**
     * Helper to parse a single GeoJSON Feature map into an OverlayFeature
     */
    fun parseFeature(feature: Map<String, Any>, overlayType: String): MapOverlayCacheUtils.OverlayFeature? {
        @Suppress("UNCHECKED_CAST")
        val geometry = feature["geometry"] as? Map<String, Any>
        if (geometry != null) {
            val centroid = computeCentroid(geometry)
            if (centroid != null) {
                val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, 16) // 16-bit precision
                val id = feature["id"] as? String // Extract ID if present
                return MapOverlayCacheUtils.OverlayFeature(id, feature, centroid, hilbertIndex, overlayType)
            }
        }
        return null
    }

    /**
     * Compute centroid of a GeoJSON geometry
     */
    fun computeCentroid(geometry: Map<String, Any>): GeoPoint? {
        val type = geometry["type"] as? String
        val coordinates = geometry["coordinates"] as? List<*>

        return when (type) {
            "Polygon" -> {
                // For Polygon: [[[lon1,lat1], [lon2,lat2], ...]]
                val outerRingRaw = coordinates?.getOrNull(0)
                val outerRing = when (outerRingRaw) {
                    is List<*> -> outerRingRaw.filterIsInstance<List<*>>().mapNotNull { pair ->
                        if (pair.size >= 2) {
                            val lon = pair[0]
                            val lat = pair[1]
                            if (lon is Number && lat is Number) {
                                GeoPoint(lat.toDouble(), lon.toDouble())
                            } else null
                        } else null
                    }
                    else -> emptyList()
                }
                val points = outerRing

                if (points.isNotEmpty()) {
                    val avgLat = points.map { it.latitude }.average()
                    val avgLon = points.map { it.longitude }.average()
                    GeoPoint(avgLat, avgLon)
                } else null
            }
            "LineString" -> {
                // For LineString: [[lon1,lat1], [lon2,lat2], ...]
                val points = coordinates?.filterIsInstance<List<*>>()?.mapNotNull { pair ->
                    if (pair.size >= 2) {
                        val lon = pair[0]
                        val lat = pair[1]
                        if (lon is Number && lat is Number) {
                            GeoPoint(lat.toDouble(), lon.toDouble())
                        } else null
                    } else null
                } ?: emptyList()

                if (points.isNotEmpty()) {
                    val avgLat = points.map { it.latitude }.average()
                    val avgLon = points.map { it.longitude }.average()
                    GeoPoint(avgLat, avgLon)
                } else null
            }
            "Point" -> {
                // For Point: [lon, lat]
                val lonLat = coordinates
                if (lonLat is List<*> && lonLat.size >= 2) {
                    val lon = lonLat[0]
                    val lat = lonLat[1]
                    if (lon is Number && lat is Number) {
                        GeoPoint(lat.toDouble(), lon.toDouble())
                    } else null
                } else null
            }
            else -> null
        }
    }
}
