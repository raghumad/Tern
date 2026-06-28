package com.ternparagliding.utils.geo

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import org.junit.Test
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * The render-safety invariant: after [GeoJsonSafe.sanitize], a FeatureCollection
 * contains **no non-finite Double anywhere** — which is exactly the condition under
 * which MapLibre's GeoJSON serializer throws (and crashes the map, violating P2).
 * So "no non-finite remains" == "the encoder cannot throw on this collection".
 */
class GeoJsonSafeTest {

    private fun props(vararg pairs: Pair<String, Double>) =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    /** True if any coordinate or numeric property in the collection is non-finite. */
    private fun hasNonFinite(fc: FeatureCollection<out Geometry, JsonObject>): Boolean =
        fc.features.any { f ->
            val geomBad = when (val g = f.geometry) {
                is Point -> !g.coordinates.finite()
                is LineString -> g.coordinates.any { !it.finite() }
                is Polygon -> g.coordinates.any { ring -> ring.any { !it.finite() } }
                else -> false
            }
            val propBad = f.properties?.values?.any {
                it is JsonPrimitive && !it.isString && it.doubleOrNull?.isFinite() == false
            } ?: false
            geomBad || propBad
        }

    private fun Position.finite() = latitude.isFinite() && longitude.isFinite()

    @Test
    fun `drops point features with a non-finite coordinate, keeps finite ones`() {
        val fc = FeatureCollection(
            listOf(
                Feature(Point(Position(6.0, 45.0)), props("r" to 400.0)),
                Feature(Point(Position(Double.NaN, 45.0)), props("r" to 1.0)),
                Feature(Point(Position(7.0, Double.POSITIVE_INFINITY)), props("r" to 1.0)),
            )
        )
        val out = GeoJsonSafe.sanitize(fc)
        assertThat(out.features).hasSize(1)
        assertThat(hasNonFinite(out)).isFalse()
    }

    @Test
    fun `omits non-finite properties, keeps finite ones and strings`() {
        val fc = FeatureCollection(
            listOf(
                Feature(
                    Point(Position(6.0, 45.0)),
                    JsonObject(
                        mapOf(
                            "alt" to JsonPrimitive(Double.NaN),          // <- the WaypointLibrary crash
                            "elevationM" to JsonPrimitive(Double.POSITIVE_INFINITY),
                            "radius" to JsonPrimitive(400.0),
                            "name" to JsonPrimitive("Gold's Point"),
                        )
                    ),
                ),
            )
        )
        val p = GeoJsonSafe.sanitize(fc).features.single().properties!!
        assertThat(p.containsKey("alt")).isFalse()
        assertThat(p.containsKey("elevationM")).isFalse()
        assertThat((p["radius"] as JsonPrimitive).double).isEqualTo(400.0)
        assertThat((p["name"] as JsonPrimitive).content).isEqualTo("Gold's Point")
        assertThat(hasNonFinite(GeoJsonSafe.sanitize(fc))).isFalse()
    }

    @Test
    fun `drops a polygon with any non-finite ring vertex`() {
        val good = listOf(Position(6.0, 45.0), Position(6.1, 45.0), Position(6.1, 45.1), Position(6.0, 45.0))
        val bad = listOf(Position(6.0, 45.0), Position(Double.NaN, 45.0), Position(6.1, 45.1), Position(6.0, 45.0))
        val fc = FeatureCollection(
            listOf(Feature(Polygon(listOf(good)), props()), Feature(Polygon(listOf(bad)), props())),
        )
        val out = GeoJsonSafe.sanitize(fc)
        assertThat(out.features).hasSize(1)
        assertThat(hasNonFinite(out)).isFalse()
    }

    @Test
    fun `a fully finite collection passes through unchanged`() {
        val fc = FeatureCollection(
            listOf(
                Feature(LineString(listOf(Position(6.0, 45.0), Position(6.1, 45.1))), props("legKm" to 25.3)),
            )
        )
        val out = GeoJsonSafe.sanitize(fc)
        assertThat(out.features).hasSize(1)
        assertThat(hasNonFinite(out)).isFalse()
    }
}
