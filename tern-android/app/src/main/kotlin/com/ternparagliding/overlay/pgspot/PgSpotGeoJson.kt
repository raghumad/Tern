package com.ternparagliding.overlay.pgspot

import com.ternparagliding.overlay.nestedOrFlatString
import com.ternparagliding.overlay.priority.OverlayCandidate
import com.ternparagliding.overlay.priority.OverlayKind
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature
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
        // The cache stores the full GeoJSON feature, so the human fields live
        // under nested `properties` (e.g. properties.name) — NOT at the top
        // level that `getStringProperty` reads. Look nested first, then fall
        // back to flat, mirroring AirspaceGeoJson.resolveAirspaceClass. Without
        // this every PG-spot name resolves to "" and PgSpotLayer renders
        // nothing (names.isEmpty() bail-out).
        val raw = overlay.feature
        val name = raw.nestedOrFlatString("name") ?: raw.nestedOrFlatString("label") ?: ""
        val siteType = raw.nestedOrFlatString("siteType") ?: ""
        // Carry the launch geometry (PGE elevation + orientation octants) onto the
        // feature so a tap can build a SiteContext for site-aware Flyability without
        // re-reading the cache. The map renderer ignores these; the tap handler reads them.
        val siteProps = siteContextOf(raw).toMapLibreProps()
        val geoProps = JsonObject(
            buildMap {
                put("name", JsonPrimitive(name))
                if (siteType.isNotEmpty()) put("siteType", JsonPrimitive(siteType))
                putAll(siteProps)
            }
        )
        Feature<Geometry, JsonObject>(point, geoProps)
    }
    return FeatureCollection(geoJsonFeatures)
}

/** Empty feature collection — sentinel for "no data yet". */
val EMPTY_PG_SPOT_COLLECTION: FeatureCollection<Geometry, JsonObject> =
    FeatureCollection(emptyList())

/**
 * A PG spot as a **selectable waypoint** — enough to search for it, snap to it, and add
 * it to a task (via [com.ternparagliding.redux.MapAction.AddPgSpotToTask]). Derived from
 * the rendered [pgSpotGeoJson] features so the task surfaces treat PG spots as first-class
 * points without a separate store. [id] is the canonical "name|lat|lon" PG-spot id used
 * across weather + add-to-task.
 */
data class PgSpotPoint(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
)

/** Extract the PG spots currently on the map as selectable waypoints. */
fun pgSpotPoints(fc: FeatureCollection<Geometry, JsonObject>?): List<PgSpotPoint> {
    if (fc == null) return emptyList()
    return fc.features.mapNotNull { f ->
        val pt = f.geometry as? Point ?: return@mapNotNull null
        val name = (f.properties?.get("name") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val lat = pt.coordinates.latitude
        val lon = pt.coordinates.longitude
        val alt = siteContextFromJsonProps(f.properties).elevationM
        PgSpotPoint(id = "$name|$lat|$lon", name = name, lat = lat, lon = lon, alt = alt)
    }
}
