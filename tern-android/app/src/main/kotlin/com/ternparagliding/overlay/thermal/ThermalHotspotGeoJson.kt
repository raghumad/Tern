package com.ternparagliding.overlay.thermal

import com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point

/**
 * Converts kk7 [OverlayFeature] thermal hotspots into MapLibre GeoJSON for the [ThermalHotspotLayer]
 * CircleLayer. Each hotspot becomes a Point carrying a "bucket" string ("hi"/"mid"/"low") derived
 * from its reliability probability — the layer colour-codes by that bucket via a `switch` expression
 * (mirroring [com.ternparagliding.overlay.flight.FlightTrackLayer], since the expression DSL's
 * `interpolate` isn't used elsewhere in the app).
 */

/** Probability → reliability bucket. */
enum class ThermalBucket { HI, MID, LOW }

fun thermalBucketOf(probability: Float): ThermalBucket = when {
    probability >= 0.66f -> ThermalBucket.HI
    probability >= 0.33f -> ThermalBucket.MID
    else -> ThermalBucket.LOW
}

fun thermalFeaturesToGeoJson(
    features: List<OverlayFeature>,
): FeatureCollection<Geometry, JsonObject> {
    val geo = features.map { f ->
        val point = Point(f.centroid.longitude, f.centroid.latitude)
        val prob = (f.feature["probability"] as? String)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
        val props = JsonObject(
            mapOf(
                "bucket" to JsonPrimitive(thermalBucketOf(prob).name),
                "prob" to JsonPrimitive(prob),
            ),
        )
        Feature<Geometry, JsonObject>(point, props)
    }
    return FeatureCollection(geo)
}

/** Empty collection — sentinel for "no data yet / disabled". */
val EMPTY_THERMAL_COLLECTION: FeatureCollection<Geometry, JsonObject> = FeatureCollection(emptyList())
