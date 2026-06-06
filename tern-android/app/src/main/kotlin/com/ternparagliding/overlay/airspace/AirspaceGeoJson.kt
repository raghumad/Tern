package com.ternparagliding.overlay.airspace

import com.ternparagliding.overlay.nestedProperties
import com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Pure-function conversion from [OverlayFeature] (cache format) to
 * MapLibre spatialk [FeatureCollection]. No Android dependencies —
 * testable with plain JUnit.
 *
 * Each feature carries a `"class"` property so the FillLayer/LineLayer
 * expressions can color-code by airspace type.
 */
object AirspaceGeoJson {

    /**
     * Convert a list of [AirspaceCandidate]s (already prioritized) into
     * a [FeatureCollection] that MapLibre's GeoJsonSource can consume.
     *
     * Features whose geometry can't be parsed are silently dropped —
     * the cache occasionally holds degenerate entries and crashing
     * would violate graceful degradation.
     */
    fun toFeatureCollection(
        candidates: List<AirspaceCandidate>,
    ): FeatureCollection<Geometry, JsonObject> {
        // Test seam (pure JVM): report the thread this potentially-expensive
        // build runs on. Inert in production (no observer set). Lets
        // AirspaceRenderPerfTest assert the build happens off the main thread.
        AirspaceBuildProbe.observer?.invoke(Thread.currentThread().name)
        val features = candidates.mapNotNull { candidate ->
            toFeature(candidate)
        }
        AirspaceBuildProbe.recordBuild(features.size)
        return FeatureCollection(features)
    }

    /** Empty collection — the initial value while the first off-thread build runs. */
    fun empty(): FeatureCollection<Geometry, JsonObject> = FeatureCollection(emptyList())

    /**
     * Convert a single cached [OverlayFeature] + resolved airspace
     * class into a spatialk [Feature] with a Polygon geometry and
     * properties that drive layer styling.
     */
    internal fun toFeature(
        candidate: AirspaceCandidate,
    ): Feature<Geometry, JsonObject>? {
        val polygon = extractPolygon(candidate.feature) ?: return null
        val props = buildProperties(candidate)
        return Feature(polygon, props)
    }

    /**
     * Resolve the "class" string from the cache's property soup.
     * The OpenAIP data uses several different key names and sometimes
     * numeric codes instead of strings. This function normalises
     * them all into a single uppercase string like "C", "RESTRICTED",
     * "DANGER", etc.
     *
     * Returns "UNKNOWN" if nothing matches — the renderer uses that
     * as the fallback colour (red).
     */
    fun resolveAirspaceClass(feature: OverlayFeature): String {
        val raw = feature.feature
        val props = raw.nestedProperties()

        // Try string keys first (most common in clean data).
        val stringClass = props["class"] as? String
            ?: props["airspace_class"] as? String
            ?: props["category"] as? String
            ?: raw["class"] as? String
            ?: raw["airspace_class"] as? String
            ?: raw["category"] as? String

        if (stringClass != null) return normaliseClass(stringClass)

        // Fall back to numeric codes (OpenAIP style).
        val typeNum = (props["type"] as? Number)?.toInt()
        val icaoNum = (props["icaoClass"] as? Number)?.toInt()

        return when {
            typeNum == 1 -> "RESTRICTED"
            typeNum == 2 -> "DANGER"
            typeNum == 3 -> "PROHIBITED"
            typeNum == 4 -> "MILITARY"
            // OpenAIP icaoClass is 0-indexed: 0=A, 1=B, 2=C, 3=D, 4=E, 5=F,
            // 6=G. Verified against real data — "DENVER CLASS B AREA A" carries
            // icaoClass=1. The old `1..5 -> 'A'+(n-1)` shifted every class down
            // one (Class B rendered as Class A — a safety-relevant mislabel),
            // dropped Class A (0) to UNKNOWN, and lumped 7/8 into G so they
            // were skipped as "unrestricted".
            icaoNum != null && icaoNum in 0..6 -> ('A' + icaoNum).toString()
            // 7 = unclassified, 8 = special-use airspace — NOT Class G. Map to
            // UNKNOWN so they still render (isUnrestricted only skips real G).
            icaoNum == 7 || icaoNum == 8 -> "UNKNOWN"
            else -> "UNKNOWN"
        }
    }

    /**
     * True when the airspace class is one a paraglider (FAR 103 /
     * SERA) can freely enter. We skip these during candidate
     * production so they never consume budget.
     */
    fun isUnrestricted(airspaceClass: String): Boolean =
        airspaceClass == "G" || airspaceClass == "CLASS_G"

    // ── internals ────────────────────────────────────────────────────

    private fun normaliseClass(raw: String): String {
        val upper = raw.uppercase().trim()
        return when {
            upper.startsWith("CLASS_") -> upper.removePrefix("CLASS_")
            else -> upper
        }
    }

    /**
     * Build the JsonObject properties attached to each GeoJSON feature.
     * The FillLayer/LineLayer expressions read `"class"` to pick colours.
     * Including floor/ceiling for at-a-glance labelling.
     */
    private fun buildProperties(candidate: AirspaceCandidate): JsonObject {
        val raw = candidate.feature.feature
        val props = raw.nestedProperties()
        
        val name = props["name"] as? String
            ?: props["Name"] as? String
            ?: candidate.feature.id

        // Extract vertical limits (OpenAIP format)
        val floor = formatLimit(props["lowerLimit"])
        val ceiling = formatLimit(props["upperLimit"])

        return JsonObject(
            buildMap {
                put("class", JsonPrimitive(candidate.airspaceClass))
                put("name", JsonPrimitive(name))
                put("id", JsonPrimitive(candidate.id))
                put("floor", JsonPrimitive(floor))
                put("ceiling", JsonPrimitive(ceiling))
                put("label", JsonPrimitive("${candidate.airspaceClass}\n$floor/$ceiling"))
            }
        )
    }

    private fun formatLimit(limitObj: Any?): String {
        val map = limitObj as? Map<*, *> ?: return "???"
        val value = (map["value"] as? Number)?.toInt() ?: return "???"
        val unit = (map["unit"] as? Number)?.toInt() ?: 1 // Default feet
        val ref = (map["referenceDatum"] as? Number)?.toInt() ?: 1 // Default MSL

        val unitStr = when(unit) {
            6 -> "FL"
            0 -> "m"
            else -> "ft"
        }

        val refStr = when(ref) {
            0 -> "GND"
            1 -> "MSL"
            2 -> "STD"
            else -> ""
        }

        return if (unit == 6) {
            "FL${value / 100}"
        } else if (value == 0 && ref == 0) {
            "SFC"
        } else {
            "$value$unitStr $refStr".trim()
        }
    }

    /**
     * Extract a spatialk [Polygon] from the cache feature's
     * `geometry.coordinates` field. Returns null for degenerate
     * geometries (fewer than 3 vertices).
     */
    internal fun extractPolygon(feature: OverlayFeature): Polygon? {
        val raw = feature.feature
        val geometry = raw["geometry"] as? Map<*, *> ?: return null
        val type = geometry["type"] as? String
        val coordinates = geometry["coordinates"] as? List<*> ?: return null

        return when (type) {
            "Polygon" -> parsePolygonRings(coordinates)
            "MultiPolygon" -> {
                // Take the first polygon from a MultiPolygon. A better
                // approach would emit one feature per polygon, but the
                // cache rarely contains MultiPolygon airspaces and this
                // keeps the conversion simple.
                val firstPoly = coordinates.firstOrNull() as? List<*> ?: return null
                parsePolygonRings(firstPoly)
            }
            else -> null
        }
    }

    private fun parsePolygonRings(rings: List<*>): Polygon? {
        val parsed = rings.mapNotNull { ring ->
            (ring as? List<*>)?.mapNotNull { coord ->
                toPosition(coord)
            }?.takeIf { it.size >= 3 }
        }
        if (parsed.isEmpty()) return null
        return Polygon(parsed)
    }

    private fun toPosition(coord: Any?): Position? {
        val list = coord as? List<*> ?: return null
        if (list.size < 2) return null
        val lon = (list[0] as? Number)?.toDouble() ?: return null
        val lat = (list[1] as? Number)?.toDouble() ?: return null
        return Position(longitude = lon, latitude = lat)
    }
}
