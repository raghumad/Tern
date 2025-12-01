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

        try {
            val geoJson: Any = mapper.readValue(geoJsonString)

            val featureList = when (geoJson) {
                is Map<*, *> -> {
                    val type = geoJson["type"] as? String
                    if (type == "FeatureCollection") {
                        geoJson["features"] as? List<Map<String, Any>> ?: emptyList()
                    } else {
                        android.util.Log.w("MapOverlayCacheUtils", "Expected FeatureCollection, got $type")
                        emptyList()
                    }
                }
                is List<*> -> {
                    // Handle raw array of features
                    @Suppress("UNCHECKED_CAST")
                    geoJson as? List<Map<String, Any>> ?: emptyList()
                }
                else -> {
                    android.util.Log.w("MapOverlayCacheUtils", "Unexpected GeoJSON format: ${geoJson.javaClass.simpleName}")
                    emptyList()
                }
            }

            featureList.forEach { feature ->
                @Suppress("UNCHECKED_CAST")
                val geometry = feature["geometry"] as? Map<String, Any>
                if (geometry != null) {
                    val centroid = computeCentroid(geometry)
                    if (centroid != null) {
                        val hilbertIndex = computeHilbertIndex(centroid, 16) // 16-bit precision
                        features.add(OverlayFeature(feature, centroid, hilbertIndex, overlayType))
                    }
                }
            }

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

    /**
     * Compute Hilbert index for a GeoPoint (global coordinates)
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
     * Compute Hilbert index for a GeoPoint relative to a center point (for overlay ordering)
     * @param point The GeoPoint to index
     * @param center The center point (used as origin for relative coordinates)
     * @param bits Number of bits for precision (e.g., 16 for 65536x65536 grid)
     * @return Hilbert index relative to center
     */
    fun computeHilbertIndexRelativeToCenter(point: GeoPoint, center: GeoPoint, bits: Int): Long {
        // Normalize coordinates relative to center (for overlay ordering)
        val metersPerDegree = 111320.0
        val latOffset = (point.latitude - center.latitude) * metersPerDegree
        val lonOffset = (point.longitude - center.longitude) * metersPerDegree * cos(Math.toRadians(center.latitude))

        // Normalize to [0, 1] range relative to center
        val scaleFactor = 1.0
        val normalizedLat = 0.5 + (latOffset / metersPerDegree) * scaleFactor
        val normalizedLon = 0.5 + (lonOffset / metersPerDegree) * scaleFactor

        // Clamp to [0, 1] range
        val clampedLat = normalizedLat.coerceIn(0.0, 1.0)
        val clampedLon = normalizedLon.coerceIn(0.0, 1.0)

        // Scale to grid size
        val gridSize = 1L shl bits
        val x = (clampedLon * (gridSize - 1)).toLong().coerceIn(0, gridSize - 1)
        val y = (clampedLat * (gridSize - 1)).toLong().coerceIn(0, gridSize - 1)

        return hilbertXYToIndex(bits, x, y)
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
        val outputStream = java.io.ByteArrayOutputStream()

        sortedFeatures.forEach { feature ->
            val builder = com.google.flatbuffers.FlexBuffersBuilder()
            val mapStart = builder.startMap()
            
            // Serialize feature properties
            val featureMapStart = builder.startMap()
            serializeMap(builder, feature.feature)
            builder.endMap("feature", featureMapStart)
            
            // Serialize centroid
            val centroidMapStart = builder.startMap()
            builder.putFloat("latitude", feature.centroid.latitude)
            builder.putFloat("longitude", feature.centroid.longitude)
            builder.endMap("centroid", centroidMapStart)
            
            builder.putInt("hilbertIndex", feature.hilbertIndex)
            builder.putString("overlayType", feature.overlayType)
            
            builder.endMap(null, mapStart)
            val buffer = builder.finish()
            
            // Write length prefix (4 bytes) followed by data
            val length = buffer.remaining()
            val lengthBytes = java.nio.ByteBuffer.allocate(4).putInt(length).array()
            
            val currentOffset = outputStream.size()
            outputStream.write(lengthBytes)
            
            val data = ByteArray(length)
            buffer.get(data)
            outputStream.write(data)
            
            // Record entry (offset points to the start of the length prefix)
            // But wait, for random access via memory map, we usually want to point to the data?
            // Actually, if we point to the length prefix, we can read length then data.
            // Let's point to the start of the record (length prefix).
            val entry = HilbertIndexEntry(feature.hilbertIndex, currentOffset, 4 + length)
            indexEntries.add(entry)
        }

        val spatialIndex = SpatialIndex(indexEntries)
        return Pair(spatialIndex, outputStream.toByteArray())
    }

    private fun serializeMap(builder: com.google.flatbuffers.FlexBuffersBuilder, map: Map<String, Any>) {
        map.forEach { (key, value) ->
            when (value) {
                is String -> builder.putString(key, value)
                is Int -> builder.putInt(key, value)
                is Long -> builder.putInt(key, value) // FlexBuffers handles 64-bit ints
                is Double -> builder.putFloat(key, value)
                is Float -> builder.putFloat(key, value)
                is Boolean -> builder.putBoolean(key, value)
                is Map<*, *> -> {
                    val mapStart = builder.startMap()
                    @Suppress("UNCHECKED_CAST")
                    serializeMap(builder, value as Map<String, Any>)
                    builder.endMap(key, mapStart)
                }
                is List<*> -> {
                    val vecStart = builder.startVector()
                    value.forEach { item ->
                        when (item) {
                            is String -> builder.putString(item)
                            is Int -> builder.putInt(item)
                            is Long -> builder.putInt(item)
                            is Double -> builder.putFloat(item)
                            is Float -> builder.putFloat(item)
                            is Boolean -> builder.putBoolean(item)
                        }
                    }
                    builder.endVector(key, vecStart, false, false)
                }
            }
        }
    }

    /**
     * Deserialize FlexBuffers blob to list of OverlayFeature
     */
    fun deserializeFlexBuffersToFeatures(data: ByteArray): List<OverlayFeature> {
        val features = mutableListOf<OverlayFeature>()
        val buffer = java.nio.ByteBuffer.wrap(data)

        while (buffer.hasRemaining()) {
            try {
                if (buffer.remaining() < 4) break
                val length = buffer.getInt()
                if (length <= 0 || length > buffer.remaining()) break
                
                val featureData = ByteArray(length)
                buffer.get(featureData)
                
                val root = com.google.flatbuffers.FlexBuffers.getRoot(java.nio.ByteBuffer.wrap(featureData))
                val map = root.asMap()
                
                val featureMap = deserializeMap(map.get("feature").asMap())
                val centroidMap = map.get("centroid").asMap()
                val latitude = centroidMap.get("latitude").asFloat()
                val longitude = centroidMap.get("longitude").asFloat()
                val hilbertIndex = map.get("hilbertIndex").asLong()
                val overlayType = map.get("overlayType").asString()

                val centroid = GeoPoint(latitude, longitude)
                features.add(OverlayFeature(featureMap, centroid, hilbertIndex, overlayType))
                
            } catch (e: Exception) {
                android.util.Log.e("MapOverlayCacheUtils", "Error deserializing FlexBuffer", e)
                break
            }
        }

        return features
    }

    private fun deserializeMap(map: com.google.flatbuffers.FlexBuffers.Map): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val keys = map.keys() // keys() returns KeyVector
        
        for (i in 0 until keys.size()) {
            val key = keys.get(i).toString()
            val value = map.get(key)
            
            val convertedValue: Any = when {
                value.isString -> value.asString()
                value.isInt -> value.asInt()
                value.isFloat -> value.asFloat()
                value.isBoolean -> value.asBoolean()
                value.isMap -> deserializeMap(value.asMap())
                else -> value.toString()
            }
            result[key] = convertedValue
        }
        return result
    }


}
