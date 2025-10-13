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

    fun createAndAdd(lat: Double, lon: Double, type: Waypoint.Type = Waypoint.Type.TURNPOINT, label: String? = null): Waypoint {
        val wp = Waypoint(lat = lat, lon = lon, type = type, label = label)
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
}
