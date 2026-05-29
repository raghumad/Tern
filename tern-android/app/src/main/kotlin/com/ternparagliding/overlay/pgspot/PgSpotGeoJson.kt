package com.ternparagliding.overlay.pgspot

import com.ternparagliding.overlay.priority.OverlayCandidate
import com.ternparagliding.overlay.priority.OverlayKind
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.utils.MapOverlayCacheUtils.OverlayFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point

/**
 * Converts PGSpotCache [OverlayFeature]s into MapLibre GeoJSON for
 * rendering as a SymbolLayer. Each feature becomes a Point with a
 * "name" property that the SymbolLayer reads via `feature.get("name")`.
 *
 * Weather orchestration is NOT this file's concern — it belongs in a
 * separate weather layer that reads from WeatherCache and decorates
 * the symbols. This file only converts cache data to GeoJSON.
 */

/** Wraps an [OverlayFeature] so the [OverlayPrioritizer] can score it. */
data class PgSpotCandidate(
    val feature: OverlayFeature,
) : OverlayCandidate {
    override val kind: OverlayKind = OverlayKind.PG_SPOT
    override val position: Position = Position(
        latitudeDeg = feature.centroid.latitude,
        longitudeDeg = feature.centroid.longitude,
    )
}

/** Converts a list of [OverlayFeature]s to a MapLibre [FeatureCollection]. */
fun overlayFeaturesToGeoJson(
    features: List<OverlayFeature>,
): FeatureCollection<Geometry, JsonObject> {
    val geoJsonFeatures = features.map { overlay ->
        val point = Point(overlay.centroid.longitude, overlay.centroid.latitude)
        val name = overlay.getStringProperty("name") ?: overlay.getStringProperty("label") ?: ""
        val siteType = overlay.getStringProperty("siteType") ?: ""
        val props = JsonObject(
            buildMap {
                put("name", JsonPrimitive(name))
                if (siteType.isNotEmpty()) put("siteType", JsonPrimitive(siteType))
            }
        )
        Feature<Geometry, JsonObject>(point, props)
    }
    return FeatureCollection(geoJsonFeatures)
}

/** Empty feature collection — sentinel for "no data yet". */
val EMPTY_PG_SPOT_COLLECTION: FeatureCollection<Geometry, JsonObject> =
    FeatureCollection(emptyList())
