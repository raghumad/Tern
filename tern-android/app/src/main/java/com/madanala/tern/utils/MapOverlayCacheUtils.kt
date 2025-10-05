package com.madanala.tern.utils

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Utility class for converting GeoJSON to FlexBuffers and Hilbert curve indexing for map overlays
 */
object MapOverlayCacheUtils {

    private val mapper = jacksonObjectMapper()

    /**
     * Generic data class for map overlay feature with centroid
     */
    data class OverlayFeature(
        val feature: Map<String, Any>,
        val centroid: GeoPoint,
        val hilbertIndex: Long,
        val overlayType: String = "generic" // e.g., "airspace", "waypoint", etc.
    )

    /**
     * Hilbert spatial index entry
     */
    @Suppress("EXPERIMENTAL_TYPE_INFERENCE")
    data class HilbertIndexEntry @JsonCreator constructor(
        @JsonProperty("hilbertIndex") val hilbertIndex: Long,
        @JsonProperty("byteOffset") val byteOffset: Int,
        @JsonProperty("byteLength") val byteLength: Int
    )

    /**
     * Spatial index for efficient Hilbert-based queries
     */
    @Suppress("EXPERIMENTAL_TYPE_INFERENCE")
    data class SpatialIndex @JsonCreator constructor(
        @JsonProperty("entries") val entries: List<HilbertIndexEntry>,
        @JsonProperty("bits") val bits: Int = 16
    ) {
        // For range queries, we can use binary search on sorted Hilbert indices
        fun findNearbyIndices(centerIndex: Long, range: Long): List<HilbertIndexEntry> {
            val minIndex = (centerIndex - range).coerceAtLeast(0)
            val maxIndex = centerIndex + range

            return entries.filter { entry ->
                entry.hilbertIndex in minIndex..maxIndex
            }
        }
    }

    /**
     * Parse NDGeoJSON string to list of OverlayFeature
     */
    fun parseNdGeoJsonToFeatures(ndGeoJsonString: String): List<OverlayFeature> {
        val features = mutableListOf<OverlayFeature>()
        val lines = ndGeoJsonString.lines()

        android.util.Log.d("MapOverlayCacheUtils", "Parsing ${lines.size} lines of NDGeoJSON")

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
                            val hilbertIndex = computeHilbertIndex(centroid, 16) // 16-bit precision
                            features.add(OverlayFeature(feature, centroid, hilbertIndex))
                        } else {
                            skippedNoCentroid++
                        }
                    } else {
                        skippedNoGeometry++
                    }
                } catch (e: Exception) {
                    skippedMalformed++
                    if (processedCount <= 3) { // Log first few malformed lines
                        android.util.Log.w("MapOverlayCacheUtils", "Malformed line $processedCount: ${e.message}, line: '$line'")
                    }
                }
            } else {
                skippedEmpty++
            }
        }

        android.util.Log.d("MapOverlayCacheUtils", "Parsed ${features.size} features. Skipped: empty=$skippedEmpty, malformed=$skippedMalformed, noGeometry=$skippedNoGeometry, noCentroid=$skippedNoCentroid")

        return features
    }

    /**
     * Parse standard GeoJSON FeatureCollection to list of OverlayFeature
     */
    fun parseGeoJsonToFeatures(geoJsonString: String, overlayType: String): List<OverlayFeature> {
        val features = mutableListOf<OverlayFeature>()

        android.util.Log.d("MapOverlayCacheUtils", "Parsing GeoJSON FeatureCollection")

        try {
            val geoJson: Map<String, Any> = mapper.readValue(geoJsonString)

            val type = geoJson["type"] as? String
            if (type != "FeatureCollection") {
                android.util.Log.w("MapOverlayCacheUtils", "Expected FeatureCollection, got $type")
                return emptyList()
            }

            @Suppress("UNCHECKED_CAST")
            val featureList = geoJson["features"] as? List<Map<String, Any>> ?: emptyList()

            android.util.Log.d("MapOverlayCacheUtils", "Parsing ${featureList.size} GeoJSON features for type: $overlayType")

            var skippedNoGeometry = 0
            var skippedNoCentroid = 0

            featureList.forEach { feature ->
                @Suppress("UNCHECKED_CAST")
                val geometry = feature["geometry"] as? Map<String, Any>
                if (geometry != null) {
                    val centroid = computeCentroid(geometry)
                    if (centroid != null) {
                        val hilbertIndex = computeHilbertIndex(centroid, 16) // 16-bit precision
                        features.add(OverlayFeature(feature, centroid, hilbertIndex, overlayType))
                    } else {
                        skippedNoCentroid++
                    }
                } else {
                    skippedNoGeometry++
                }
            }

            android.util.Log.d("MapOverlayCacheUtils", "Parsed ${features.size} features. Skipped: noGeometry=$skippedNoGeometry, noCentroid=$skippedNoCentroid")

        } catch (e: Exception) {
            android.util.Log.e("MapOverlayCacheUtils", "Error parsing GeoJSON", e)
        }

        return features
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
                @Suppress("UNCHECKED_CAST")
                val outerRing = coordinates?.get(0) as? List<List<Double>>
                val points = outerRing?.mapNotNull { lonLat ->
                    try {
                        if (lonLat.size >= 2) {
                            GeoPoint(lonLat[1], lonLat[0]) // lat, lon
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                } ?: emptyList()

                if (points.isNotEmpty()) {
                    val avgLat = points.map { it.latitude }.average()
                    val avgLon = points.map { it.longitude }.average()
                    GeoPoint(avgLat, avgLon)
                } else null
            }
            "Point" -> {
                // For Point: [lon, lat]
                try {
                    val lonLat = coordinates as List<Double>
                    if (lonLat.size >= 2) {
                        GeoPoint(lonLat[1], lonLat[0])
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * Compute Hilbert index for a GeoPoint
     * @param point The GeoPoint
     * @param bits Number of bits for precision (e.g., 16 for 65536x65536 grid)
     */
    fun computeHilbertIndex(point: GeoPoint, bits: Int): Long {
        // Normalize latitude (-90 to 90) and longitude (-180 to 180) to 0-1
        val normLat = (point.latitude + 90.0) / 180.0
        val normLon = (point.longitude + 180.0) / 360.0

        // Scale to grid size
        val gridSize = 1L shl bits // 2^bits
        val x = (normLon * (gridSize - 1)).toLong().coerceIn(0, gridSize - 1)
        val y = (normLat * (gridSize - 1)).toLong().coerceIn(0, gridSize - 1)

        return hilbertXYToIndex(bits, x, y)
    }

    /**
     * Convert Hilbert XY coordinates to index
     */
    private fun hilbertXYToIndex(bits: Int, x: Long, y: Long): Long {
        var d = 0L
        var s = 1L shl (bits - 1)
        var xx = x
        var yy = y

        while (s > 0) {
            val rx = (xx and s) > 0
            val ry = (yy and s) > 0
            d += s * s * ((3 * (if (rx) 1 else 0)) xor (if (ry) 1 else 0)).toLong()
            if (ry == false) {
                if (rx == true) {
                    xx = (1L shl bits) - 1 - xx
                    yy = (1L shl bits) - 1 - yy
                }
                // Swap x and y
                val temp = xx
                xx = yy
                yy = temp
            }
            s = s shr 1
        }
        return d
    }

    /**
     * Create spatial index and serialize features to FlexBuffer with byte offsets
     * @param features List of features to index and serialize
     * @return Pair of (SpatialIndex, FlexBuffer data)
     */
    fun createSpatialIndexAndSerialize(features: List<OverlayFeature>): Pair<SpatialIndex, ByteArray> {
        // Sort features by Hilbert index for efficient range queries
        val sortedFeatures = features.sortedBy { it.hilbertIndex }

        // Create FlexBuffer data and track byte offsets
        val indexEntries = mutableListOf<HilbertIndexEntry>()
        var currentOffset = 0

        // For now, serialize as JSON with offsets (will replace with proper FlexBuffer later)
        val serializedFeatures = sortedFeatures.map { feature ->
            val featureData = mapOf(
                "feature" to feature.feature,
                "centroid" to mapOf(
                    "latitude" to feature.centroid.latitude,
                    "longitude" to feature.centroid.longitude
                ),
                "hilbertIndex" to feature.hilbertIndex,
                "overlayType" to feature.overlayType
            )
            val jsonBytes = mapper.writeValueAsBytes(featureData)
            val entry = HilbertIndexEntry(feature.hilbertIndex, currentOffset, jsonBytes.size)
            indexEntries.add(entry)
            currentOffset += jsonBytes.size
            jsonBytes
        }

        // Combine all feature data into single byte array
        val totalSize = serializedFeatures.sumOf { it.size }
        val combinedData = ByteArray(totalSize)
        var offset = 0
        serializedFeatures.forEach { bytes ->
            bytes.copyInto(combinedData, offset)
            offset += bytes.size
        }

        val spatialIndex = SpatialIndex(indexEntries)
        return Pair(spatialIndex, combinedData)
    }

    /**
     * Deserialize JSON to list of OverlayFeature
     */
    fun deserializeFlexBuffersToFeatures(data: ByteArray): List<OverlayFeature> {
        return try {
            // Parse as concatenated JSON objects, not as JSON array
            val jsonString = String(data, Charsets.UTF_8)
            val features = mutableListOf<OverlayFeature>()

            // Split by newlines and parse each feature individually
            val lines = jsonString.lines()
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    try {
                        val item: Map<String, Any> = mapper.readValue(line)
                        @Suppress("UNCHECKED_CAST")
                        val feature = item["feature"] as? Map<String, Any> ?: return@forEach
                        @Suppress("UNCHECKED_CAST")
                        val centroidData = item["centroid"] as? Map<String, Any> ?: return@forEach
                        val latitude = centroidData["latitude"] as? Double ?: return@forEach
                        val longitude = centroidData["longitude"] as? Double ?: return@forEach
                        val hilbertIndex = item["hilbertIndex"] as? Long ?: return@forEach
                        val overlayType = item["overlayType"] as? String ?: "generic"

                        val centroid = GeoPoint(latitude, longitude)
                        features.add(OverlayFeature(feature, centroid, hilbertIndex, overlayType))
                    } catch (e: Exception) {
                        // Skip malformed lines
                    }
                }
            }

            features
        } catch (_: Exception) {
            emptyList()
        }
    }


}
