package com.madanala.tern.route

import com.madanala.tern.model.Waypoint
import java.time.Instant
import java.util.UUID

/**
 * Route model representing a collection of waypoints with styling and visibility control.
 * Part of the route-centric architecture for managing multiple routes simultaneously.
 */
data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val waypoints: List<Waypoint> = emptyList(),
    val isVisible: Boolean = true,
    val color: RouteColor = RouteColor.DEFAULT,
    val description: String? = null,
    val createdAt: Instant = Instant.now(),
    val lastModified: Instant = Instant.now(),
    val isLocked: Boolean = false,
    val tags: List<String> = emptyList()
) {

    /**
     * Create a copy of this route with updated waypoints
     */
    fun withWaypoints(newWaypoints: List<Waypoint>): Route {
        return copy(
            waypoints = newWaypoints,
            lastModified = Instant.now()
        )
    }

    /**
     * Create a copy of this route with a new waypoint added
     */
    fun withAddedWaypoint(waypoint: Waypoint): Route {
        return copy(
            waypoints = waypoints + waypoint,
            lastModified = Instant.now()
        )
    }

    /**
     * Create a copy of this route with a waypoint removed
     */
    fun withRemovedWaypoint(waypointId: String): Route {
        return copy(
            waypoints = waypoints.filterNot { it.id == waypointId },
            lastModified = Instant.now()
        )
    }

    /**
     * Create a copy of this route with updated visibility
     */
    fun withVisibility(visible: Boolean): Route {
        return copy(isVisible = visible)
    }

    /**
     * Create a copy of this route with updated styling
     */
    fun withStyling(color: RouteColor): Route {
        return copy(color = color)
    }

    /**
     * Create a copy of this route with updated metadata
     */
    fun withMetadata(name: String? = null, description: String? = null, tags: List<String>? = null): Route {
        return copy(
            name = name ?: this.name,
            description = description ?: this.description,
            tags = tags ?: this.tags,
            lastModified = Instant.now()
        )
    }

    /**
     * Get the total distance of the route (placeholder - would need distance calculation logic)
     */
    fun getTotalDistance(): Double {
        // TODO: Implement distance calculation between waypoints
        return 0.0
    }

    /**
     * Get the number of waypoints in this route
     */
    fun getWaypointCount(): Int = waypoints.size

    /**
     * Check if this route has any waypoints
     */
    fun isEmpty(): Boolean = waypoints.isEmpty()

    /**
     * Get waypoints of a specific type
     */
    fun getWaypointsByType(type: Waypoint.Type): List<Waypoint> {
        return waypoints.filter { it.type == type }
    }

    /**
     * Validate that the route meets basic requirements
     */
    fun isValid(): Boolean {
        return name.isNotBlank() && waypoints.isNotEmpty()
    }
}