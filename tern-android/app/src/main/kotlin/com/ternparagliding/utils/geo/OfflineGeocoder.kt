package com.ternparagliding.utils.geo

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.osmdroid.util.GeoPoint
import java.io.InputStream

/**
 * Offline Geocoder that uses Point-in-Polygon math against a resident
 * simplified country boundary dataset. Zero-I/O after initialization.
 */
object OfflineGeocoder {
    private const val TAG = "OfflineGeocoder"
    private const val ASSET_PATH = "geo/countries.geojson"

    private data class CountryBoundary(
        val code: String,
        val name: String,
        val rings: List<List<GeoPoint>>, // Outer rings of Polygons/MultiPolygons
        val bounds: BBox
    )

    private data class BBox(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double
    ) {
        fun contains(point: GeoPoint): Boolean =
            point.latitude in minLat..maxLat && point.longitude in minLon..maxLon
    }

    private var countryBoundaries: List<CountryBoundary> = emptyList()
    @Volatile
    private var isInitialized = false

    /**
     * Initialize the geocoder by loading boundaries from assets into memory.
     * Should be called at app startup (e.g., Application.onCreate).
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        
        try {
            val t0 = System.currentTimeMillis()
            val inputStream: InputStream = context.assets.open(ASSET_PATH)
            val mapper = jacksonObjectMapper()
            val root: Map<String, Any> = mapper.readValue(inputStream)
            
            @Suppress("UNCHECKED_CAST")
            val features = root["features"] as? List<Map<String, Any>> ?: emptyList()
            
            countryBoundaries = features.mapNotNull { feature ->
                val props = feature["properties"] as? Map<String, Any>
                val code = (props?.get("iso_a2") ?: props?.get("ISO_A2")) as? String ?: return@mapNotNull null
                val name = (props?.get("name") ?: props?.get("NAME")) as? String ?: code
                
                val geom = feature["geometry"] as? Map<String, Any> ?: return@mapNotNull null
                val type = geom["type"] as? String ?: return@mapNotNull null
                val coords = geom["coordinates"] as? List<*> ?: return@mapNotNull null
                
                val rings = mutableListOf<List<GeoPoint>>()
                
                when (type) {
                    "Polygon" -> {
                        // For simplicity, we only take the outer ring (first element)
                        @Suppress("UNCHECKED_CAST")
                        val polygonCoords = coords as? List<List<List<Number>>>
                        polygonCoords?.firstOrNull()?.let { ring ->
                            rings.add(ring.map { GeoPoint(it[1].toDouble(), it[0].toDouble()) })
                        }
                    }
                    "MultiPolygon" -> {
                        @Suppress("UNCHECKED_CAST")
                        val multiPolygonCoords = coords as? List<List<List<List<Number>>>>
                        multiPolygonCoords?.forEach { polygon ->
                            polygon.firstOrNull()?.let { ring ->
                                rings.add(ring.map { GeoPoint(it[1].toDouble(), it[0].toDouble()) })
                            }
                        }
                    }
                }
                
                if (rings.isEmpty()) return@mapNotNull null
                
                // Compute BBox for Stage 1 filtering
                var minLat = 90.0; var minLon = 180.0; var maxLat = -90.0; var maxLon = -180.0
                rings.flatten().forEach { pt ->
                    if (pt.latitude < minLat) minLat = pt.latitude
                    if (pt.latitude > maxLat) maxLat = pt.latitude
                    if (pt.longitude < minLon) minLon = pt.longitude
                    if (pt.longitude > maxLon) maxLon = pt.longitude
                }
                
                CountryBoundary(code.uppercase(), name, rings, BBox(minLat, minLon, maxLat, maxLon))
            }
            
            isInitialized = true
            Log.i(TAG, "Initialized with ${countryBoundaries.size} countries in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize offline geocoder", e)
        }
    }

    /**
     * Get country code from coordinates using Point-in-Polygon math.
     * Returns 2-letter ISO code or null if not found.
     */
    fun getCountryCode(point: GeoPoint): String? {
        if (!isInitialized) return null
        
        // Stage 1: BBox filter (Fast)
        val candidates = countryBoundaries.filter { it.bounds.contains(point) }
        if (candidates.isEmpty()) return null
        
        // Stage 2: Point-in-Polygon (Ray Casting)
        return candidates.find { country ->
            country.rings.any { ring -> isPointInRing(point, ring) }
        }?.code
    }

    /**
     * Standard Ray Casting algorithm for Point-in-Polygon
     */
    private fun isPointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val vi = ring[i]
            val vj = ring[j]
            
            if (((vi.latitude > point.latitude) != (vj.latitude > point.latitude)) &&
                (point.longitude < (vj.longitude - vi.longitude) * (point.latitude - vi.latitude) / (vj.latitude - vi.latitude) + vi.longitude)) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
