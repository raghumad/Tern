package com.ternparagliding.overlay.task

import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import kotlin.math.cos

/**
 * Pure functions that convert [Task] model objects into GeoJSON
 * structures consumable by MapLibre sources. No Android dependencies,
 * no side effects -- trivially testable.
 */
object TaskGeoJson {

    /**
     * Builds a [FeatureCollection] containing one [LineString] feature
     * per visible task. The task polyline source.
     */
    fun taskLines(tasks: List<Task>): FeatureCollection<LineString, JsonObject> {
        val features = tasks
            .filter { it.isVisible && it.waypoints.size >= 2 }
            .map { task ->
                val positions = task.waypoints.map { wp ->
                    Position(wp.lon, wp.lat)
                }
                Feature(
                    LineString(positions),
                    JsonObject(mapOf("taskId" to JsonPrimitive(task.id))),
                )
            }
        return FeatureCollection(features)
    }

    /**
     * Builds a [FeatureCollection] of [Point] features for every
     * waypoint in visible tasks. Each feature carries properties
     * the SymbolLayer uses for labeling:
     *   - `name`: waypoint label (or "WP {index}")
     *   - `type`: the [LocationType] name (LAUNCH, SSS, ESS, TURNPOINT, GOAL, LANDING)
     *   - `label`: combined "name \n type" for the text-field expression
     *   - `waypointId`: for click handling
     *   - `taskId`: parent task
     */
    fun waypointPoints(tasks: List<Task>): FeatureCollection<Point, JsonObject> {
        val features = tasks
            .filter { it.isVisible }
            .flatMap { task ->
                var tpSeq = 0
                task.waypoints.mapIndexed { index, wp ->
                    val seq = if (wp.type == LocationType.TURNPOINT) ++tpSeq else index + 1
                    waypointFeature(wp, index, task.id, seq)
                }
            }
        return FeatureCollection(features)
    }

    /**
     * Converts one [Waypoint] into a GeoJSON [Feature] with properties.
     * `markerKey` is unique per waypoint so the SymbolLayer can map each to
     * its rasterised bitmap; `seq` is the turnpoint number (for the code).
     */
    internal fun waypointFeature(
        wp: Waypoint,
        index: Int,
        taskId: String,
        seq: Int = index + 1,
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
                    "taskId" to JsonPrimitive(taskId),
                    "markerKey" to JsonPrimitive("$taskId:${wp.id}"),
                    "seq" to JsonPrimitive(seq),
                    "radius" to JsonPrimitive(wp.radius ?: 0.0),
                )
            ),
        )
    }

    /**
     * Builds [Polygon] footprints (FAI cylinders) for every waypoint that has
     * a radius — the ring IS the waypoint's identity on the map. Properties:
     *   - `type`: [LocationType] name (drives the role colour)
     *   - `radius`: metres
     */
    fun taskCylinders(tasks: List<Task>): FeatureCollection<Polygon, JsonObject> {
        val features = tasks
            .filter { it.isVisible }
            .flatMap { task ->
                task.waypoints.mapNotNull { wp ->
                    val r = wp.radius ?: return@mapNotNull null
                    if (r <= 0.0) return@mapNotNull null
                    Feature(
                        Polygon(listOf(circlePositions(wp.lat, wp.lon, r))),
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive(wp.type.name),
                                "radius" to JsonPrimitive(r),
                            )
                        ),
                    )
                }
            }
        return FeatureCollection(features)
    }

    /**
     * One [Point] at each leg's midpoint, carrying the leg distance — so the
     * "25 km" pill sits on the line (glanceable, no tap). Properties:
     *   - `legKm`: leg distance in km
     *   - `label`: formatted ("25 km" / "850 m")
     */
    fun legMidpoints(tasks: List<Task>): FeatureCollection<Point, JsonObject> {
        val features = tasks
            .filter { it.isVisible && it.waypoints.size >= 2 }
            .flatMap { task ->
                val legs = task.legDistances
                task.waypoints.zipWithNext().mapIndexed { i, (a, b) ->
                    val midLat = (a.lat + b.lat) / 2.0
                    val midLon = (a.lon + b.lon) / 2.0
                    val km = legs.getOrNull(i) ?: 0.0
                    Feature(
                        Point(Position(midLon, midLat)),
                        JsonObject(
                            mapOf(
                                "legKm" to JsonPrimitive(km),
                                "label" to JsonPrimitive(formatKm(km)),
                            )
                        ),
                    )
                }
            }
        return FeatureCollection(features)
    }

    /** Formats a leg distance: "850 m" under 1 km, else "25.3 km". */
    internal fun formatKm(km: Double): String =
        if (km < 1.0) "${(km * 1000).toInt()} m" else "%.1f km".format(km)

    /** Closed ring of [segments] positions approximating a circle of [radiusMeters]. */
    private fun circlePositions(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        segments: Int = 64,
    ): List<Position> {
        val mPerDegLat = 111_320.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(centerLat))
        val ring = ArrayList<Position>(segments + 1)
        for (i in 0..segments) {
            val a = 2.0 * Math.PI * i / segments
            val dLat = radiusMeters * cos(a) / mPerDegLat
            val dLon = radiusMeters * Math.sin(a) / mPerDegLon
            ring.add(Position(centerLon + dLon, centerLat + dLat))
        }
        return ring
    }

    /**
     * Wraps all waypoints from visible tasks as [TaskWaypointCandidate]s
     * for the overlay prioritizer.
     */
    fun waypointCandidates(tasks: List<Task>): List<TaskWaypointCandidate> =
        tasks
            .filter { it.isVisible }
            .flatMap { task ->
                task.waypoints.map { TaskWaypointCandidate(it) }
            }
}
