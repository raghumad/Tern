package com.madanala.tern.route

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.madanala.tern.model.Waypoint

/**
 * Simple in-memory waypoint store for Phase 1 MVP.
 * Will be migrated to Redux in Phase 2.
 */
object WaypointStore {
    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    fun add(waypoint: Waypoint) {
        _waypoints.value = _waypoints.value + waypoint
    }

    fun createAndAdd(lat: Double, lon: Double, type: Waypoint.Type = Waypoint.Type.TURNPOINT, label: String? = null, routeId: String? = null): Waypoint {
        val wp = Waypoint(lat = lat, lon = lon, type = type, label = label, routeId = routeId)
        add(wp)
        return wp
    }

    fun remove(id: String) {
        _waypoints.value = _waypoints.value.filterNot { it.id == id }
    }

    fun clear() {
        _waypoints.value = emptyList()
    }

    fun updateWaypointPosition(id: String, newLat: Double, newLon: Double) {
        val currentWaypoints = _waypoints.value
        val index = currentWaypoints.indexOfFirst { it.id == id }
        if (index != -1) {
            val updatedWaypoint = currentWaypoints[index].copy(lat = newLat, lon = newLon)
            _waypoints.value = currentWaypoints.toMutableList().apply {
                this[index] = updatedWaypoint
            }
        }
    }

    fun findWaypointById(id: String): Waypoint? {
        return _waypoints.value.find { it.id == id }
    }

    // Route-centric operations

    /**
     * Get all waypoints associated with a specific route
     */
    fun getWaypointsByRoute(routeId: String): List<Waypoint> {
        return _waypoints.value.filter { it.routeId == routeId }
    }

    /**
     * Get all waypoints not associated with any route (for backward compatibility)
     */
    fun getUnassignedWaypoints(): List<Waypoint> {
        return _waypoints.value.filter { it.routeId == null }
    }

    /**
     * Create a new waypoint and associate it with a specific route
     */
    fun createWaypointInRoute(routeId: String, lat: Double, lon: Double, type: Waypoint.Type = Waypoint.Type.TURNPOINT, label: String? = null): Waypoint {
        val waypoint = Waypoint(
            lat = lat,
            lon = lon,
            type = type,
            label = label,
            routeId = routeId
        )
        add(waypoint)
        return waypoint
    }

    /**
     * Move a waypoint from one route to another
     */
    fun moveWaypointToRoute(waypointId: String, targetRouteId: String) {
        val currentWaypoints = _waypoints.value.toMutableList()
        val index = currentWaypoints.indexOfFirst { it.id == waypointId }
        if (index != -1) {
            val updatedWaypoint = currentWaypoints[index].copy(routeId = targetRouteId)
            currentWaypoints[index] = updatedWaypoint
            _waypoints.value = currentWaypoints
        }
    }

    /**
     * Associate an existing waypoint with a route
     */
    fun assignWaypointToRoute(waypointId: String, routeId: String) {
        moveWaypointToRoute(waypointId, routeId)
    }

    /**
     * Remove a waypoint from its current route (make it unassigned)
     */
    fun unassignWaypointFromRoute(waypointId: String) {
        moveWaypointToRoute(waypointId, "")
    }

    /**
     * Get waypoints by type within a specific route
     */
    fun getWaypointsByTypeInRoute(routeId: String, type: Waypoint.Type): List<Waypoint> {
        return getWaypointsByRoute(routeId).filter { it.type == type }
    }

    /**
     * Update waypoint position (enhanced to maintain route association)
     */
    fun updateWaypointPositionAndRoute(id: String, newLat: Double, newLon: Double, routeId: String? = null) {
        val currentWaypoints = _waypoints.value
        val index = currentWaypoints.indexOfFirst { it.id == id }
        if (index != -1) {
            val currentWaypoint = currentWaypoints[index]
            val updatedWaypoint = currentWaypoint.copy(
                lat = newLat,
                lon = newLon,
                routeId = routeId ?: currentWaypoint.routeId
            )
            _waypoints.value = currentWaypoints.toMutableList().apply {
                this[index] = updatedWaypoint
            }
        }
    }
}
