package com.ternparagliding.overlay.route

import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * Pure functions that convert [Route] model objects into GeoJSON
 * structures consumable by MapLibre sources. No Android dependencies,
 * no side effects -- trivially testable.
 */
object RouteGeoJson {

    /**
     * Builds a [FeatureCollection] containing one [LineString] feature
     * per visible route. The route polyline source.
     */
    fun routeLines(routes: List<Route>): FeatureCollection<LineString, JsonObject> {
        val features = routes
            .filter { it.isVisible && it.waypoints.size >= 2 }
            .map { route ->
                val positions = route.waypoints.map { wp ->
                    Position(wp.lon, wp.lat)
                }
                Feature(
                    LineString(positions),
                    JsonObject(mapOf("routeId" to JsonPrimitive(route.id))),
                )
            }
        return FeatureCollection(features)
    }

    /**
     * Builds a [FeatureCollection] of [Point] features for every
     * waypoint in visible routes. Each feature carries properties
     * the SymbolLayer uses for labeling:
     *   - `name`: waypoint label (or "WP {index}")
     *   - `type`: the [LocationType] name (LAUNCH, SSS, ESS, TURNPOINT, GOAL, LANDING)
     *   - `label`: combined "name \n type" for the text-field expression
     *   - `waypointId`: for click handling
     *   - `routeId`: parent route
     */
    fun waypointPoints(routes: List<Route>): FeatureCollection<Point, JsonObject> {
        val features = routes
            .filter { it.isVisible }
            .flatMap { route ->
                route.waypoints.mapIndexed { index, wp ->
                    waypointFeature(wp, index, route.id)
                }
            }
        return FeatureCollection(features)
    }

    /**
     * Converts one [Waypoint] into a GeoJSON [Feature] with properties.
     */
    internal fun waypointFeature(
        wp: Waypoint,
        index: Int,
        routeId: String,
    ): Feature<Point, JsonObject> {
        val displayName = wp.label ?: "WP ${index + 1}"
        val typeName = wp.type.name
        return Feature(
            Point(wp.lon, wp.lat),
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(displayName),
                    "type" to JsonPrimitive(typeName),
                    "label" to JsonPrimitive("$displayName\n$typeName"),
                    "waypointId" to JsonPrimitive(wp.id),
                    "routeId" to JsonPrimitive(routeId),
                )
            ),
        )
    }

    /**
     * Wraps all waypoints from visible routes as [RouteWaypointCandidate]s
     * for the overlay prioritizer.
     */
    fun waypointCandidates(routes: List<Route>): List<RouteWaypointCandidate> =
        routes
            .filter { it.isVisible }
            .flatMap { route ->
                route.waypoints.map { RouteWaypointCandidate(it) }
            }
}
