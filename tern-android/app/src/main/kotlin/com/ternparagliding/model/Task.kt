package com.ternparagliding.model

import com.ternparagliding.redux.TaskConstants
import java.time.Instant
import java.util.UUID

/**
 * Waypoint model for paragliding task planning.
 */
data class Waypoint(
    override val id: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lon: Double,
    override val type: LocationType = LocationType.TURNPOINT,
    val label: String? = null,
    /** Human-readable name for a cryptic code. Tasks ship terse codes ("B4");
     *  the description is the place ("Gold's Point"). Shown in preference to the
     *  code wherever there's room (e.g. the next-waypoint indicator). */
    val description: String? = null,
    /** Link to a [LibraryWaypoint] this task point came from (Stage B reference
     *  model). When set, the resolver prefers the library's identity (position,
     *  code, name, alt); null = an ad-hoc point (map long-press) that owns its
     *  own coordinates. */
    val libraryWaypointId: String? = null,
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
        libraryWaypointId: String? = null
    ): Task {
        val newWaypoint = Waypoint(
            lat = lat,
            lon = lon,
            type = type,
            label = label,
            description = description,
            libraryWaypointId = libraryWaypointId,
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
        if (waypoints.size < 3) return TaskType.OPEN_DISTANCE
        
        val start = waypoints.first()
        val end = waypoints.last()
        val gap = calculateDistance(start.lat, start.lon, end.lat, end.lon)
        val isClosedLoop = gap < 0.4

        if (isClosedLoop && waypoints.size == 4) {
             val legs = legDistances
             if (legs.size < 3) return TaskType.OPEN_DISTANCE
             
             val totalTriDist = legs.sum()
             val shortest = legs.minOrNull() ?: 0.0
             if (shortest >= 0.28 * totalTriDist) {
                 return TaskType.FAI_TRIANGLE
             } else {
                 return TaskType.FLAT_TRIANGLE
             }
        }
        return TaskType.OPEN_DISTANCE
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
