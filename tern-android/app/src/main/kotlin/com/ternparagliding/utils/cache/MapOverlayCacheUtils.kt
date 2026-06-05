package com.ternparagliding.utils.cache

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import android.util.Log
import org.osmdroid.util.GeoPoint
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * Normalizes the precision of a GeoPoint by rounding latitude and longitude to a specified number of decimal places.
 * Default is 5 decimal places (approx. 1.1 meters) to reduce floating-point jitter.
 */
fun GeoPoint.normalizePrecision(decimalPlaces: Int = 5): GeoPoint {
    val factor = 10.0.pow(decimalPlaces)
    val lat = (this.latitude * factor).roundToLong() / factor
    val lon = (this.longitude * factor).roundToLong() / factor
    return GeoPoint(lat, lon)
}

/**
 * Utility class for converting GeoJSON to FlexBuffers and Hilbert curve indexing for map overlays
 */
object MapOverlayCacheUtils {

    /**
     * Generic data class for map overlay feature with centroid
     */
    data class OverlayFeature(
        val internalId: String? = null,
        val centroid: GeoPoint,
        val hilbertIndex: Long,
        val overlayType: String = "generic"
    ) : com.ternparagliding.model.UnifiedLocation {
        override val id: String get() = internalId ?: "feat_${hilbertIndex}"
        override val coordinate: GeoPoint get() = centroid
        override val name: String? get() = (feature["name"] ?: feature["label"]) as? String
        override val type: com.ternparagliding.model.LocationType 
            get() = when (overlayType) {
                "launch" -> com.ternparagliding.model.LocationType.LAUNCH
                "landing" -> com.ternparagliding.model.LocationType.LANDING
                else -> com.ternparagliding.model.LocationType.TURNPOINT
            }
        override val source: com.ternparagliding.model.LocationSource 
            get() = com.ternparagliding.model.LocationSource.PG_SPOT
        override val altitude: Double? get() = getDoubleProperty("altitude").takeIf { it > 0 }
        override val metadata: Map<String, Any> get() = feature
        // Internal storage for lazy deserialization
        var rawData: java.nio.ByteBuffer? = null
        private var _featureMap: Map<String, Any>? = null

        /**
         * The full feature property map, deserialized on demand.
         * Priority: Performance (Lazy load)
         */
        val feature: Map<String, Any>
            get() {
                if (_featureMap == null) {
                    _featureMap = rawData?.let {
                        @Suppress("DEPRECATION")
                        val root = com.google.flatbuffers.FlexBuffers.getRoot(it)
                        deserializeMap(root.asMap().get("feature").asMap())
                    } ?: emptyMap()
                }
                return _featureMap!!
            }

        /**
         * Fast property access without full map hydration.
         * Recommended for rendering and filtering.
         */
        fun getStringProperty(key: String): String? {
            return rawData?.let {
                @Suppress("DEPRECATION")
                val root = com.google.flatbuffers.FlexBuffers.getRoot(it)
                root.asMap().get("feature").asMap().get(key).asString()
            } ?: (feature[key] as? String)
        }

        fun getIntProperty(key: String): Int {
             return rawData?.let {
                @Suppress("DEPRECATION")
                val root = com.google.flatbuffers.FlexBuffers.getRoot(it)
                root.asMap().get("feature").asMap().get(key).asInt()
            } ?: (feature[key] as? Int ?: 0)
        }

        fun getDoubleProperty(key: String): Double {
             return rawData?.let {
                @Suppress("DEPRECATION")
                val root = com.google.flatbuffers.FlexBuffers.getRoot(it)
                root.asMap().get("feature").asMap().get(key).asFloat().toDouble()
            } ?: (feature[key] as? Double ?: 0.0)
        }

        // Secondary constructor for legacy/JSON loads
        constructor(
            internalId: String? = null,
            feature: Map<String, Any>,
            centroid: GeoPoint,
            hilbertIndex: Long,
            overlayType: String = "generic"
        ) : this(internalId, centroid, hilbertIndex, overlayType) {
            this._featureMap = feature
        }

        override fun toString(): String {
            return "OverlayFeature(id=$id, type=$overlayType, centroid=$centroid)"
        }
    }

    /**
     * Hilbert spatial index entry
     */
    @Suppress("EXPERIMENTAL_TYPE_INFERENCE")
    data class HilbertIndexEntry @JsonCreator constructor(
        @JsonProperty("hilbertIndex") val hilbertIndex: Long,
        @JsonProperty("byteOffset") val byteOffset: Int,
        @JsonProperty("byteLength") val byteLength: Int,
        // Feature centroid, carried on the index so a radius query can filter
        // in-memory without touching the memory-mapped feature buffer. NaN on
        // legacy indices written before this field existed — queryNearby then
        // falls back to peekCentroid for those.
        @JsonProperty("centroidLat") val centroidLat: Double = Double.NaN,
        @JsonProperty("centroidLon") val centroidLon: Double = Double.NaN
    )

    /**
     * Geographic bounding box of a cached region's data. Persisted in a small
     * sidecar so [queryAllCachedNearby]-style callers can skip a country whose
     * data is nowhere near the query — without loading (cold-parsing) its index.
     */
    data class RegionBounds @JsonCreator constructor(
        @JsonProperty("minLat") val minLat: Double,
        @JsonProperty("minLon") val minLon: Double,
        @JsonProperty("maxLat") val maxLat: Double,
        @JsonProperty("maxLon") val maxLon: Double,
    ) {
        /** Standard AABB overlap (lat/lon). */
        fun intersects(o: RegionBounds): Boolean =
            minLat <= o.maxLat && maxLat >= o.minLat && minLon <= o.maxLon && maxLon >= o.minLon

        /** True if this (query) box wraps past ±180° — callers then skip the
         *  bbox filter and query everything, so a wrap can't wrongly exclude a region. */
        fun crossesAntimeridian(): Boolean = minLon < -180.0 || maxLon > 180.0

        companion object {
            /** The lat/lon box covering [center] ± [radiusMeters] (matches the query disc's bbox). */
            fun ofRadius(center: GeoPoint, radiusMeters: Double): RegionBounds {
                val dLat = radiusMeters / 111_320.0
                val cosLat = Math.cos(Math.toRadians(center.latitude)).coerceAtLeast(1e-6)
                val dLon = radiusMeters / (111_320.0 * cosLat)
                return RegionBounds(
                    minLat = (center.latitude - dLat).coerceIn(-90.0, 90.0),
                    minLon = center.longitude - dLon, // not clamped: caller compares conservatively
                    maxLat = (center.latitude + dLat).coerceIn(-90.0, 90.0),
                    maxLon = center.longitude + dLon,
                )
            }
        }
    }

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

    // ──────────────────────────────────────────────────────────────────────
    // Hilbert-range spatial query (restores O(log N + k) querying; see
    // docs/design/hilbert-spatial-query-restore.md). A radius query box is
    // covered by a small set of Hilbert intervals — NOT one window, which was
    // the old single-window bug that silently dropped features across curve
    // folds. We binary-search the sorted index for each interval, then
    // haversine-refine to the exact radius.
    // ──────────────────────────────────────────────────────────────────────

    private const val MAX_CELLS_PER_AXIS = 8

    /**
     * Hilbert intervals (inclusive, coalesced, ascending) whose union contains
     * every point inside the lat/lon box `center ± radius`, for a `bits`-deep
     * global Hilbert curve.
     *
     * Completeness: every point in the box lies in some level-`L` cell, and a
     * level-`L` cell maps to exactly one contiguous interval
     * `[hL << 2·(bits−L), (hL+1) << 2·(bits−L))` (Hilbert self-similarity). So
     * covering the box with cells covers it with intervals — no fold can drop a
     * point. We pick the finest `L` keeping ≤ [MAX_CELLS_PER_AXIS] cells/axis.
     */
    fun hilbertCoveringIntervals(
        center: GeoPoint,
        radiusMeters: Double,
        bits: Int,
        maxCellsPerAxis: Int = MAX_CELLS_PER_AXIS,
    ): List<LongRange> {
        val gridMax = (1L shl bits) - 1
        val dLat = radiusMeters / 111_320.0
        val cosLat = Math.cos(Math.toRadians(center.latitude)).coerceAtLeast(1e-6)
        val dLon = radiusMeters / (111_320.0 * cosLat)

        val latMin = (center.latitude - dLat).coerceIn(-90.0, 90.0)
        val latMax = (center.latitude + dLat).coerceIn(-90.0, 90.0)
        val lonMin = center.longitude - dLon
        val lonMax = center.longitude + dLon

        // Split across the antimeridian so a wrap can't drop the far side.
        val lonRanges: List<Pair<Double, Double>> = when {
            lonMax - lonMin >= 360.0 -> listOf(-180.0 to 180.0)
            lonMin < -180.0 -> listOf(-180.0 to lonMax, (lonMin + 360.0) to 180.0)
            lonMax > 180.0 -> listOf(lonMin to 180.0, -180.0 to (lonMax - 360.0))
            else -> listOf(lonMin to lonMax)
        }

        fun gx(lon: Double) = (((lon + 180.0) / 360.0) * gridMax).toLong().coerceIn(0, gridMax)
        fun gy(lat: Double) = (((lat + 90.0) / 180.0) * gridMax).toLong().coerceIn(0, gridMax)
        val yMin = gy(latMin)
        val yMax = gy(latMax)

        val intervals = ArrayList<LongRange>()
        for ((loA, loB) in lonRanges) {
            val xMin = gx(loA)
            val xMax = gx(loB)
            // Finest level with a bounded cell count on both axes.
            var level = bits
            while (level > 0) {
                val shift = bits - level
                val cx = (xMax shr shift) - (xMin shr shift) + 1
                val cy = (yMax shr shift) - (yMin shr shift) + 1
                if (cx <= maxCellsPerAxis && cy <= maxCellsPerAxis) break
                level--
            }
            val shift = bits - level
            var xl = xMin shr shift
            val xl1 = xMax shr shift
            val yl1 = yMax shr shift
            while (xl <= xl1) {
                var yl = yMin shr shift
                while (yl <= yl1) {
                    val hL = hilbertXYToIndex(level, xl, yl)
                    val lo = hL shl (2 * shift)
                    val hi = ((hL + 1) shl (2 * shift)) - 1
                    intervals.add(lo..hi)
                    yl++
                }
                xl++
            }
        }
        if (intervals.isEmpty()) return emptyList()
        intervals.sortBy { it.first }
        val merged = ArrayList<LongRange>()
        for (iv in intervals) {
            val last = merged.lastOrNull()
            if (last != null && iv.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, iv.last)
            } else {
                merged.add(iv)
            }
        }
        return merged
    }

    /** First index in [entries] (sorted by hilbertIndex asc) with value ≥ [target]. */
    private fun lowerBound(entries: List<HilbertIndexEntry>, target: Long): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].hilbertIndex < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index in [entries] (sorted by hilbertIndex asc) with value > [target]. */
    private fun upperBound(entries: List<HilbertIndexEntry>, target: Long): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].hilbertIndex <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    /**
     * Spatial query over a Hilbert-sorted index: interval cover → binary search
     * → haversine refine. Returns [HilbertIndexEntry]s within [radiusMeters] of
     * [center], nearest first, capped at [limit]. Pure (uses the centroid
     * carried on each entry); the disk cache calls this then hydrates the
     * survivors. [centroidFallback] supplies a centroid for legacy entries whose
     * `centroidLat/Lon` are NaN (peeked from the feature buffer).
     *
     * Precondition: [sortedEntries] is ascending by `hilbertIndex` (the cache
     * writes it that way).
     */
    fun queryHilbertRange(
        sortedEntries: List<HilbertIndexEntry>,
        bits: Int,
        center: GeoPoint,
        radiusMeters: Double,
        limit: Int,
        centroidFallback: ((HilbertIndexEntry) -> GeoPoint?)? = null,
    ): List<HilbertIndexEntry> {
        if (sortedEntries.isEmpty()) return emptyList()
        val intervals = hilbertCoveringIntervals(center, radiusMeters, bits)
        val survivors = ArrayList<Pair<HilbertIndexEntry, Double>>()
        for (iv in intervals) {
            var i = lowerBound(sortedEntries, iv.first)
            val end = upperBound(sortedEntries, iv.last)
            while (i < end) {
                val entry = sortedEntries[i]
                i++
                if (entry.byteLength <= 4) continue
                val lat: Double
                val lon: Double
                if (!entry.centroidLat.isNaN() && !entry.centroidLon.isNaN()) {
                    lat = entry.centroidLat
                    lon = entry.centroidLon
                } else {
                    val c = centroidFallback?.invoke(entry) ?: continue
                    lat = c.latitude
                    lon = c.longitude
                }
                val d = haversineMeters(center.latitude, center.longitude, lat, lon)
                if (d <= radiusMeters) survivors.add(entry to d)
            }
        }
        survivors.sortBy { it.second }
        return if (survivors.size > limit) {
            survivors.asSequence().take(limit).map { it.first }.toList()
        } else {
            survivors.map { it.first }
        }
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
            feature.id?.let { builder.putString("id", it) } // Serialize ID if present
            
            builder.endMap(null, mapStart)
            val buffer = builder.finish()
            
            // Write length prefix (4 bytes) followed by data
            val length = buffer.remaining()
            val lengthBytes = ByteBuffer.allocate(4).putInt(length).array()
            
            val currentOffset = outputStream.size()
            outputStream.write(lengthBytes)
            
            val data = ByteArray(length)
            buffer.get(data)
            outputStream.write(data)
            
            // Record entry (offset points to the start of the length prefix)
            // But wait, for random access via memory map, we usually want to point to the data?
            // Actually, if we point to the length prefix, we can read length then data.
            // Let's point to the start of the record (length prefix).
            val entry = HilbertIndexEntry(
                feature.hilbertIndex, currentOffset, 4 + length,
                feature.centroid.latitude, feature.centroid.longitude,
            )
            indexEntries.add(entry)
        }

        val spatialIndex = SpatialIndex(indexEntries)
        return Pair(spatialIndex, outputStream.toByteArray())
    }

    fun serializeMap(builder: com.google.flatbuffers.FlexBuffersBuilder, map: Map<String, Any>) {
        map.forEach { (key, value) ->
            when (value) {
                is String -> builder.putString(key, value)
                is Int -> builder.putInt(key, value)
                is Long -> builder.putInt(key, value)
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
                    serializeList(builder, value)
                    builder.endVector(key, vecStart, false, false)
                }
            }
        }
    }

    fun serializeList(builder: com.google.flatbuffers.FlexBuffersBuilder, list: List<*>) {
        list.forEach { item ->
            when (item) {
                is String -> builder.putString(item)
                is Int -> builder.putInt(item)
                is Long -> builder.putInt(item)
                is Double -> builder.putFloat(item)
                is Float -> builder.putFloat(item)
                is Boolean -> builder.putBoolean(item)
                is Map<*, *> -> {
                    val mapStart = builder.startMap()
                    @Suppress("UNCHECKED_CAST")
                    serializeMap(builder, item as Map<String, Any>)
                    builder.endMap(null, mapStart)
                }
                is List<*> -> {
                    val vecStart = builder.startVector()
                    serializeList(builder, item)
                    builder.endVector(null, vecStart, false, false)
                }
            }
        }
    }

    /**
     * Deserialize FlexBuffers blob to list of OverlayFeature
     */
    fun deserializeFlexBuffersToFeatures(data: ByteArray): List<OverlayFeature> {
        val features = mutableListOf<OverlayFeature>()
        val buffer = ByteBuffer.wrap(data)

        while (buffer.hasRemaining()) {
            val feature = deserializeSingleFlexBufferFeature(buffer)
            if (feature != null) {
                features.add(feature)
            } else {
                // If deserialization fails for one feature, stop processing the rest
                break
            }
        }

        return features
    }

    /**
     * PEAK CENTROID WITHOUT FULL HYDRATION
     * Extremely fast, zero-copy read from memory-mapped buffer.
     * Used for pre-sort/budgeting before full OverlayFeature allocation.
     */
    fun peekCentroid(buffer: ByteBuffer): GeoPoint? {
        try {
            // Buffer is at start of record (length prefix)
            if (buffer.remaining() < 4) return null
            val length = buffer.getInt()
            if (length <= 0) return null
            
            // Slice the buffer for the feature data (zero-copy)
            val featureSlice = buffer.slice()
            featureSlice.limit(length)
            
            // Advance original buffer
            buffer.position(buffer.position() + length)
            
            @Suppress("DEPRECATION")
            val root = com.google.flatbuffers.FlexBuffers.getRoot(featureSlice)
            val centroidMap = root.asMap().get("centroid").asMap()
            val latitude = centroidMap.get("latitude").asFloat().toDouble()
            val longitude = centroidMap.get("longitude").asFloat().toDouble()
            
            return GeoPoint(latitude, longitude)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Deserialize a single FlexBuffer feature from the ByteBuffer.
     * Assumes the buffer is positioned at the start of a length-prefixed FlexBuffer.
     */
    fun deserializeSingleFlexBufferFeature(buffer: ByteBuffer): OverlayFeature? {
        try {
            if (buffer.remaining() < 4) return null
            val length = buffer.getInt()
            if (length <= 0) return null
            
            val featureData = ByteArray(length)
            buffer.get(featureData)
            
            @Suppress("DEPRECATION")
            val root = com.google.flatbuffers.FlexBuffers.getRoot(java.nio.ByteBuffer.wrap(featureData))
            val map = root.asMap()
            
            // Pull high-frequency fields immediately (centroid, hilbertIndex, type)
            val centroidMap = map.get("centroid").asMap()
            val latitude = centroidMap.get("latitude").asFloat()
            val longitude = centroidMap.get("longitude").asFloat()
            val hilbertIndex = map.get("hilbertIndex").asLong()
            val overlayType = map.get("overlayType").asString()
            val idRef = map.get("id")
            val id = if (!idRef.isNull) idRef.asString() else null

            val centroid = GeoPoint(latitude, longitude)
            
            val feature = OverlayFeature(
                internalId = id,
                centroid = centroid,
                hilbertIndex = hilbertIndex,
                overlayType = overlayType
            )
            feature.rawData = java.nio.ByteBuffer.wrap(featureData)
            return feature
        } catch (e: Exception) {
            Log.e("MapOverlayCacheUtils", "Error deserializing FlexBuffer", e)
            return null
        }
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
                value.isVector -> deserializeVector(value.asVector())
                else -> value.toString()
            }
            result[key] = convertedValue
        }
        return result
    }

    private fun deserializeVector(vector: com.google.flatbuffers.FlexBuffers.Vector): List<Any> {
        val result = mutableListOf<Any>()
        for (i in 0 until vector.size()) {
            val value = vector.get(i)
            val convertedValue: Any = when {
                value.isString -> value.asString()
                value.isInt -> value.asInt()
                value.isFloat -> value.asFloat()
                value.isBoolean -> value.asBoolean()
                value.isMap -> deserializeMap(value.asMap())
                value.isVector -> deserializeVector(value.asVector())
                else -> value.toString()
            }
            result.add(convertedValue)
        }
        return result
    }


}
