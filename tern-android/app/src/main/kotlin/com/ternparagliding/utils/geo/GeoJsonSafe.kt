package com.ternparagliding.utils.geo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * The single render-safety boundary for every MapLibre GeoJSON source.
 *
 * MapLibre serialises a [FeatureCollection] to JSON via kotlinx-serialization, which
 * **throws on any non-finite Double (NaN / Infinity)** — and that exception escapes
 * the map composition and crashes the whole app (principle P2: a crash mid-flight is
 * the worst unknown of all). One bad coordinate or property from *any* overlay used
 * to be enough. Rather than guard each overlay (whack-a-mole), every overlay routes
 * its collection through [sanitize] before handing it to `GeoJsonData.Features(...)`.
 *
 * Pure + allocation-light; bounded by the overlay budget (≤ a few hundred features).
 */
object GeoJsonSafe {

    /**
     * Returns a copy of [fc] that can never make the serialiser throw:
     *  - any feature whose geometry has a non-finite coordinate is **dropped**
     *    (you cannot render a NaN point anyway);
     *  - any property whose value is a non-finite number is **omitted**.
     */
    fun <G : Geometry> sanitize(fc: FeatureCollection<G, JsonObject>): FeatureCollection<G, JsonObject> {
        val safe = fc.features.mapNotNull { f ->
            val g = f.geometry ?: return@mapNotNull null
            if (!g.allCoordsFinite()) return@mapNotNull null
            Feature(g, sanitizeProperties(f.properties ?: JsonObject(emptyMap())))
        }
        return FeatureCollection(safe)
    }

    /** Drop property entries that are non-finite numbers; keep strings and finite numbers. */
    private fun sanitizeProperties(props: JsonObject): JsonObject =
        JsonObject(
            props.filterValues { v ->
                !(v is JsonPrimitive && !v.isString && v.doubleOrNull?.isFinite() == false)
            }
        )

    private fun Geometry.allCoordsFinite(): Boolean = when (this) {
        is Point -> coordinates.isFiniteLatLon()
        is LineString -> coordinates.all { it.isFiniteLatLon() }
        is Polygon -> coordinates.all { ring -> ring.all { it.isFiniteLatLon() } }
        else -> true // geometry types these overlays never emit — don't reject
    }

    private fun Position.isFiniteLatLon(): Boolean = latitude.isFinite() && longitude.isFinite()
}
