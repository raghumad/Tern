package com.ternparagliding.model

import com.ternparagliding.redux.TaskConstants
import java.time.Instant
import java.util.UUID

/**
 * A **task point** — a task's ordered *reference* to a [Spot], carrying the
 * per-task features that belong to the reference (role/[type], cylinder [radius],
 * time gates), not to the spot.
 *
 * Identity (position/code/name/alt) lives on the [Spot] named by [spotId]; the
 * [TaskResolver] overlays the live spot identity at read time so an edit to the
 * spot flows to every task that references it. The identity fields carried here
 * ([lat]/[lon]/[label]/[description]/[alt]) are a **denormalised snapshot** —
 * last-known-good, used as the fallback when the spot can't be resolved (deleted,
 * or its store not loaded). They are written on create/bind/import, never edited
 * in place; identity edits go to the spot (see `MapAction.UpdateSpot`).
 */
data class Waypoint(
    override val id: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lon: Double,
    override val type: LocationType = LocationType.TURNPOINT,
    val label: String? = null,
    /** Snapshot of the spot's human-readable name (e.g. "Gold's Point" for code
     *  "B4"). Shown in preference to the code wherever there's room. */
    val description: String? = null,
    /** Reference to the [Spot] this task point resolves against. Every task point
     *  references a spot (ad-hoc map drops auto-create a USER spot). The resolver
     *  prefers the spot's live identity; the fields above are the snapshot fallback.
     *  Null only for legacy/unmigrated points, which fly from the snapshot. */
    val spotId: String? = null,
    val createdAt: Instant = java.time.Instant.now(),
    val taskId: String? = null,
    val radius: Double? = com.ternparagliding.redux.TaskConstants.FAI_DEFAULT_RADIUS_METERS, // Default FAI cylinder radius in meters
    val alt: Double? = null, // Altitude in meters
    val openTime: String? = null, // HH:mm
    val closeTime: String? = null // HH:mm
) : UnifiedLocation {
    /** Best human-facing label: the description if set, else the terse code. */
    val displayName: String?
        get() = description?.takeIf { it.isNotBlank() } ?: label?.takeIf { it.isNotBlank() }

    override val coordinate: org.osmdroid.util.GeoPoint
        get() = org.osmdroid.util.GeoPoint(lat, lon)
    
    override val name: String?
        get() = label
        
    override val source: LocationSource
        get() = LocationSource.WAYPOINT
        
    override val altitude: Double?
        get() = alt
        
    override val metadata: Map<String, Any>
        get() = mapOf(
            "taskId" to (taskId ?: ""),
            "createdAt" to createdAt.toString(),
            "radius" to (radius ?: 0.0)
        )
}

/**
 * Task model for paragliding task planning.
 * Tasks own their waypoints with strong relationships.
 */
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Task",
    val waypoints: List<Waypoint> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val isVisible: Boolean = true
) {
    init {
        com.ternparagliding.utils.diagnostics.trackAllocation("Task", 128L + waypoints.size * 64L)
    }

    // Computed properties derived from waypoints
    val totalDistanceKm: Double
        get() = calculateTotalDistance()

    val estimatedFlightTimeMinutes: Int
        get() = (totalDistanceKm / 30.0 * 60).toInt()

    val legDistances: List<Double>
        get() = calculateLegDistances()

    val taskType: TaskType
        get() = calculateTaskType()

    val faiPoints: Double
        get() = calculateFaiPoints()

    /**
     * The bounding box encompassing all waypoints in this task.
     * Returns null if task is empty.
     */
    val extent: TernBoundingBox?
        get() {
            if (waypoints.isEmpty()) return null
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE

            waypoints.forEach { wp ->
                if (wp.lat < minLat) minLat = wp.lat
                if (wp.lat > maxLat) maxLat = wp.lat
                if (wp.lon < minLon) minLon = wp.lon
                if (wp.lon > maxLon) maxLon = wp.lon
            }

            return TernBoundingBox(minLat, minLon, maxLat, maxLon)
        }

    enum class TaskType { OPEN_DISTANCE, FLAT_TRIANGLE, FAI_TRIANGLE }

    /**
     * Add a waypoint to this task
     */
    fun addWaypoint(
        lat: Double,
        lon: Double,
        type: LocationType = LocationType.TURNPOINT,
        label: String? = null,
        id: String? = null,
        radius: Double? = com.ternparagliding.redux.TaskConstants.FAI_DEFAULT_RADIUS_METERS,
        alt: Double? = null,
        openTime: String? = null,
        closeTime: String? = null,
        description: String? = null,
        spotId: String? = null
    ): Task {
        val newWaypoint = Waypoint(
            lat = lat,
            lon = lon,
            type = type,
            label = label,
            description = description,
            spotId = spotId,
            taskId = this.id,
            id = id ?: UUID.randomUUID().toString(),
            radius = radius,
            alt = alt,
            openTime = openTime,
            closeTime = closeTime
        )
        return copy(
            waypoints = waypoints + newWaypoint,
            updatedAt = Instant.now()
        )
    }

    /**
     * Remove a waypoint from this task
     */
    fun removeWaypoint(waypointId: String): Task {
        return copy(
            waypoints = waypoints.filter { it.id != waypointId },
            updatedAt = Instant.now()
        )
    }

    /**
     * Update a waypoint in this task
     */
    fun updateWaypoint(
        waypointId: String,
        lat: Double? = null,
        lon: Double? = null,
        type: LocationType? = null,
        radius: Double? = null,
        alt: Double? = null,
        openTime: String? = null,
        closeTime: String? = null,
        description: String? = null
    ): Task {
        return copy(
            waypoints = waypoints.map {
                if (it.id == waypointId) {
                    it.copy(
                        lat = lat ?: it.lat,
                        lon = lon ?: it.lon,
                        type = type ?: it.type,
                        radius = radius ?: it.radius,
                        alt = alt ?: it.alt,
                        openTime = openTime ?: it.openTime,
                        closeTime = closeTime ?: it.closeTime,
                        description = description ?: it.description
                    )
                } else it
            },
            updatedAt = Instant.now()
        )
    }

    /** Set a point's role. A per-task feature — lives on the reference. */
    fun setPointRole(waypointId: String, role: LocationType): Task =
        mapPoint(waypointId) { it.copy(type = role) }

    /** Set a point's cylinder radius; null clears it (back to the default radius).
     *  Direct-set, not keep-on-null, so the gate/cylinder can actually be removed. */
    fun setPointRadius(waypointId: String, radius: Double?): Task =
        mapPoint(waypointId) { it.copy(radius = radius) }

    /** Set a point's time gates; null clears a gate. Direct-set so a gate can be
     *  removed (the old keep-on-null reducer made gates unclearable). */
    fun setPointGates(waypointId: String, openTime: String?, closeTime: String?): Task =
        mapPoint(waypointId) { it.copy(openTime = openTime, closeTime = closeTime) }

    private fun mapPoint(waypointId: String, f: (Waypoint) -> Waypoint): Task =
        copy(
            waypoints = waypoints.map { if (it.id == waypointId) f(it) else it },
            updatedAt = Instant.now(),
        )

    /**
     * Reorder a waypoint in this task
     */
    fun reorderWaypoint(fromIndex: Int, toIndex: Int): Task {
        if (fromIndex < 0 || fromIndex >= waypoints.size || toIndex < 0 || toIndex >= waypoints.size || fromIndex == toIndex) {
            return this
        }

        val mutableWaypoints = waypoints.toMutableList()
        val waypoint = mutableWaypoints.removeAt(fromIndex)
        mutableWaypoints.add(toIndex, waypoint)

        return copy(
            waypoints = mutableWaypoints,
            updatedAt = Instant.now()
        )
    }

    private fun calculateTotalDistance(): Double {
        if (waypoints.size < 2) return 0.0
        var distance = 0.0
        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            distance += calculateDistance(p1.lat, p1.lon, p2.lat, p2.lon)
        }
        return distance
    }

    private fun calculateLegDistances(): List<Double> {
        if (waypoints.size < 2) return emptyList()
        val legs = mutableListOf<Double>()
        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            legs.add(calculateDistance(p1.lat, p1.lon, p2.lat, p2.lon))
        }
        return legs
    }

    private fun calculateTaskType(): TaskType {
        val corners = triangleCorners() ?: return TaskType.OPEN_DISTANCE

        // The three triangle sides, corner-to-corner (the closing side included), then the
        // classic FAI rule: the shortest side must be ≥ 28% of the perimeter.
        val sides = listOf(
            calculateDistance(corners[0].lat, corners[0].lon, corners[1].lat, corners[1].lon),
            calculateDistance(corners[1].lat, corners[1].lon, corners[2].lat, corners[2].lon),
            calculateDistance(corners[2].lat, corners[2].lon, corners[0].lat, corners[0].lon),
        )
        val perimeter = sides.sum()
        if (perimeter <= 0.0) return TaskType.OPEN_DISTANCE
        val shortestFraction = sides.min() / perimeter
        return if (shortestFraction >= TaskConstants.FAI_MIN_LEG_FRACTION) {
            TaskType.FAI_TRIANGLE
        } else {
            TaskType.FLAT_TRIANGLE
        }
    }

    /**
     * The three distinct corners of a triangle task, or null if this isn't one.
     *
     * Robust to the real shapes a planned/competition triangle takes — not just the bare
     * 4-point `[A, B, C, A]`, but `[SSS, TP1, TP2, TP3, GOAL]` where the start cylinder is
     * snapped onto the first corner and the goal returns to the start. We collapse points
     * that coincide within [TaskConstants.TRIANGLE_CLOSURE_KM] (consecutive *and* the
     * loop-closing return), and call it a triangle only when exactly three corners remain on
     * a genuinely closed course.
     */
    private fun triangleCorners(): List<Waypoint>? {
        if (waypoints.size < 3) return null
        val closeKm = TaskConstants.TRIANGLE_CLOSURE_KM

        // Collapse consecutive near-duplicate points (e.g. a start cylinder snapped onto TP1).
        val collapsed = mutableListOf(waypoints.first())
        for (wp in waypoints.drop(1)) {
            val prev = collapsed.last()
            if (calculateDistance(prev.lat, prev.lon, wp.lat, wp.lon) >= closeKm) collapsed += wp
        }

        // Closed course: the final point returns to the start. Drop it so only the distinct
        // vertices remain. An open course (no return) is open distance, not a triangle.
        val first = collapsed.first()
        val last = collapsed.last()
        val closed = collapsed.size >= 2 &&
            calculateDistance(first.lat, first.lon, last.lat, last.lon) < closeKm
        if (!closed) return null
        val corners = collapsed.dropLast(1)
        return if (corners.size == 3) corners else null
    }

    private fun calculateFaiPoints(): Double {
        val dist = totalDistanceKm
        return when (taskType) {
            TaskType.FAI_TRIANGLE -> dist * 2.0
            TaskType.FLAT_TRIANGLE -> dist * 1.5
            TaskType.OPEN_DISTANCE -> dist
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    companion object {
        fun fromWaypoints(name: String, waypoints: List<Waypoint>): Task {
            val taskId = UUID.randomUUID().toString()
            val taskWaypoints = waypoints.map { it.copy(taskId = taskId) }
            return Task(
                id = taskId,
                name = name,
                waypoints = taskWaypoints
            )
        }
    }
}
