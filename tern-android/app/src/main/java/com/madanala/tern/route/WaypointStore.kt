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

    fun remove(id: String) {
        _waypoints.value = _waypoints.value.filterNot { it.id == id }
    }

    fun clear() {
        _waypoints.value = emptyList()
    }
}
