package com.ternparagliding.overlay.hazard

import com.ternparagliding.model.Route
import com.ternparagliding.utils.WeatherForecast
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point

/**
 * Hazard severity at a geo position, derived from its weather forecast.
 * The [key] is the GeoJSON property value the [HazardLayer] switches on.
 *
 * Thresholds live in `WeatherForecast` (WeatherAPI.kt): convective danger
 * is CAPE > 500 J/kg; thunderstorm is lightning potential > 60.
 */
enum class HazardLevel(val key: String) {
    /** CAPE > 500 J/kg — amber halo (warning). */
    CONVECTIVE("convective"),

    /** Lightning potential > 60 — red halo + bolt (critical). */
    THUNDERSTORM("thunderstorm"),
}

/** A hazardous site: where it is, what it's called, and how bad. */
data class HazardSite(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val level: HazardLevel,
)

/**
 * Classifies a forecast into a [HazardLevel], or null if benign.
 * Thunderstorm dominates convective — the worse warning always wins.
 */
fun classifyHazard(forecast: WeatherForecast?): HazardLevel? = when {
    forecast == null -> null
    forecast.hasThunderstorm() -> HazardLevel.THUNDERSTORM
    forecast.hasConvectiveDanger() -> HazardLevel.CONVECTIVE
    else -> null
}

/**
 * Joins route waypoints with their weather forecasts and emits a hazard
 * site for every waypoint whose forecast is convective or stormy. Benign
 * waypoints are dropped.
 */
fun hazardSitesFrom(
    routes: List<Route>,
    waypointWeathers: Map<String, WeatherForecast>,
): List<HazardSite> =
    routes.flatMap { it.waypoints }.mapNotNull { wp ->
        val level = classifyHazard(waypointWeathers[wp.id]) ?: return@mapNotNull null
        HazardSite(wp.lat, wp.lon, wp.label ?: "", level)
    }

/** Converts hazard sites to a MapLibre [FeatureCollection] with level + label. */
fun hazardSitesToGeoJson(
    sites: List<HazardSite>,
): FeatureCollection<Geometry, JsonObject> {
    val features = sites.map { site ->
        val point = Point(site.longitude, site.latitude)
        val props = JsonObject(
            buildMap {
                put("level", JsonPrimitive(site.level.key))
                put("label", JsonPrimitive(site.label))
            }
        )
        Feature<Geometry, JsonObject>(point, props)
    }
    return FeatureCollection(features)
}

/** Empty feature collection — sentinel for "no hazards". */
val EMPTY_HAZARD_COLLECTION: FeatureCollection<Geometry, JsonObject> =
    FeatureCollection(emptyList())
