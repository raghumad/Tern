package com.ternparagliding.utils.cache

import android.util.Log
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.redux.TaskConstants
import org.osmdroid.util.GeoPoint

/**
 * Pure (Context-free) codec between a [Task] and its persisted overlay-feature
 * shape. Split out of [TaskCache] so the round-trip is unit-testable without
 * Android, and so the persisted schema lives in one place.
 *
 * **Schema.** A task serialises to a single LineString feature; its points live in
 * `properties.waypoints`. v0 (absent `schemaVersion`) carried no `spotId`/
 * `description`. v1 (the spot-reference model) carries `spotId` + the identity
 * snapshot (lat/lon/label/description/alt) — persisting `spotId` is the fix for
 * "Stage B links die on restart". v0 features still read: their points come back
 * with `spotId = null` (snapshot-only, still flyable), and are re-bound by code on
 * load (see `SurfaceNearbyTasks`).
 */
internal object TaskCacheCodec {

    const val TASK_SCHEMA_VERSION = 1
    private const val TAG = "TaskCacheCodec"
    private const val HILBERT_BITS_PRECISION = 32

    fun reconstructTaskFromFeatures(taskId: String, features: List<MapOverlayCacheUtils.OverlayFeature>): Task? {
        try {
            if (features.isEmpty()) return null

            // Expect a single LineString feature containing all task data
            val feature = features.first()
            val properties = feature.feature["properties"] as? Map<*, *>
            val taskName = properties?.get("taskName") as? String ?: "Reconstructed Task"
            val isVisible = properties?.get("isVisible") as? Boolean ?: true
            // Use the task id stored in the feature, not the [taskId] param:
            // SpatialDiskCache uppercases the region key, so queryNearbyTasks
            // passes an uppercased id. The stored property preserves the
            // original case so a round-tripped task keeps its identity.
            val realTaskId = properties?.get("taskId") as? String ?: taskId

            val waypointsList = properties?.get("waypoints") as? List<*>

            if (waypointsList != null) {
                val waypoints = waypointsList.mapNotNull { wpObj ->
                    if (wpObj is Map<*, *>) {
                        val id = wpObj["id"] as? String
                        val lat = (wpObj["lat"] as? Number)?.toDouble()
                        val lon = (wpObj["lon"] as? Number)?.toDouble()
                        val typeStr = wpObj["type"] as? String
                        val label = wpObj["label"] as? String
                        // v1 fields; absent (empty) on v0-cached tasks → null, which
                        // makes the point a snapshot-only legacy point (still flyable).
                        val spotId = (wpObj["spotId"] as? String)?.takeIf { it.isNotBlank() }
                        val description = (wpObj["description"] as? String)?.takeIf { it.isNotBlank() }
                        val radius = (wpObj["radius"] as? Number)?.toDouble()
                        val alt = (wpObj["alt"] as? Number)?.toDouble()
                        val openTime = wpObj["openTime"] as? String
                        val closeTime = wpObj["closeTime"] as? String

                        if (id != null && lat != null && lon != null) {
                            val type = try {
                                LocationType.valueOf(typeStr ?: "TURNPOINT")
                            } catch (e: Exception) {
                                LocationType.TURNPOINT
                            }

                            Waypoint(
                                id = id,
                                lat = lat,
                                lon = lon,
                                type = type,
                                label = label,
                                description = description,
                                spotId = spotId,
                                radius = radius,
                                alt = alt,
                                openTime = openTime,
                                closeTime = closeTime,
                                taskId = realTaskId
                            )
                        } else null
                    } else null
                }

                if (waypoints.isNotEmpty()) {
                    return Task(
                        id = realTaskId,
                        name = taskName,
                        waypoints = waypoints,
                        isVisible = isVisible
                    )
                }
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error reconstructing task from features: ${e.message}")
            return null
        }
    }

    fun convertTaskToFeatures(task: Task): List<MapOverlayCacheUtils.OverlayFeature> {
        if (task.waypoints.isEmpty()) return emptyList()

        // Create a single LineString feature for the entire task
        val coordinates = task.waypoints.map { listOf(it.lon, it.lat) }

        // Serialize waypoints metadata. The identity fields (lat/lon/label/
        // description/alt) are the snapshot; spotId is the reference the resolver
        // re-links against on load — persisting it is the fix for "Stage B links
        // die on restart".
        val waypointsMetadata = task.waypoints.map { wp ->
            mapOf(
                "id" to wp.id,
                "spotId" to (wp.spotId ?: ""),
                "lat" to wp.lat,
                "lon" to wp.lon,
                "type" to wp.type.name,
                "label" to (wp.label ?: ""),
                "description" to (wp.description ?: ""),
                "radius" to (wp.radius ?: TaskConstants.FAI_DEFAULT_RADIUS_METERS),
                "alt" to (wp.alt ?: 0.0),
                "openTime" to (wp.openTime ?: ""),
                "closeTime" to (wp.closeTime ?: "")
            )
        }

        val featureData = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "LineString",
                "coordinates" to coordinates
            ),
            "properties" to mapOf(
                "schemaVersion" to TASK_SCHEMA_VERSION,
                "taskId" to task.id,
                "taskName" to task.name,
                "isVisible" to task.isVisible,
                "waypoints" to waypointsMetadata
            )
        )

        // Compute centroid of the LineString for indexing
        @Suppress("UNCHECKED_CAST")
        val centroid = OverlayGeoJsonParser.computeCentroid(featureData["geometry"] as Map<String, Any>)
            ?: GeoPoint(task.waypoints[0].lat, task.waypoints[0].lon) // Fallback to first point

        val hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, HILBERT_BITS_PRECISION)

        return listOf(
            MapOverlayCacheUtils.OverlayFeature(
                internalId = task.id,
                feature = featureData,
                centroid = centroid,
                hilbertIndex = hilbertIndex,
                overlayType = "task"
            )
        )
    }
}
