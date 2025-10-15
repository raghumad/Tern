package com.madanala.tern.route

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.madanala.tern.model.Waypoint
import java.util.UUID

/**
 * Simple in-memory route store for Phase 1 MVP with StateFlow management for multiple routes.
 * Supports multiple routes simultaneously with reactive state management.
 * Will be migrated to Redux in Phase 2.
 */
object RouteStore {
    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _currentRouteId = MutableStateFlow<String?>(null)
    val currentRouteId: StateFlow<String?> = _currentRouteId.asStateFlow()

    /**
     * Get the currently active route, if any
     */
    fun getCurrentRoute(): Route? {
        val currentId = _currentRouteId.value
        return currentId?.let { findRouteById(it) }
    }

    /**
     * Set the currently active route
     */
    fun setCurrentRoute(routeId: String?) {
        _currentRouteId.value = routeId
    }

    /**
     * Create a new route and add it to the store
     */
    fun createRoute(name: String, waypoints: List<Waypoint> = emptyList()): Route {
        val route = Route(
            name = name,
            waypoints = waypoints.toMutableList()
        )
        add(route)
        return route
    }

    /**
     * Add an existing route to the store
     */
    fun add(route: Route) {
        val currentRoutes = _routes.value.toMutableList()
        // Remove existing route with same ID if it exists (for updates)
        currentRoutes.removeAll { it.id == route.id }
        currentRoutes.add(route)
        _routes.value = currentRoutes
    }

    /**
     * Update an existing route
     */
    fun update(routeId: String, update: (Route) -> Route) {
        val currentRoutes = _routes.value.toMutableList()
        val index = currentRoutes.indexOfFirst { it.id == routeId }
        if (index != -1) {
            currentRoutes[index] = update(currentRoutes[index])
            _routes.value = currentRoutes
        }
    }

    /**
     * Remove a route from the store
     */
    fun remove(routeId: String) {
        val currentRoutes = _routes.value.filterNot { it.id == routeId }
        _routes.value = currentRoutes

        // If the removed route was current, clear current selection
        if (_currentRouteId.value == routeId) {
            _currentRouteId.value = null
        }
    }

    /**
     * Find a route by ID
     */
    fun findRouteById(routeId: String): Route? {
        return _routes.value.find { route -> route.id == routeId }
    }

    /**
     * Get all routes
     */
    fun getAllRoutes(): List<Route> {
        return _routes.value
    }

    /**
     * Add a waypoint to a specific route
     */
    fun addWaypointToRoute(routeId: String, waypoint: Waypoint) {
        update(routeId) { route ->
            route.withAddedWaypoint(waypoint)
        }
    }

    /**
     * Remove a waypoint from a specific route
     */
    fun removeWaypointFromRoute(routeId: String, waypointId: String) {
        update(routeId) { route ->
            route.withRemovedWaypoint(waypointId)
        }
    }

    /**
     * Update waypoint position in a specific route
     */
    fun updateWaypointInRoute(routeId: String, waypointId: String, lat: Double, lon: Double) {
        update(routeId) { route ->
            val updatedWaypoints = route.waypoints.map { waypoint ->
                if (waypoint.id == waypointId) {
                    waypoint.copy(lat = lat, lon = lon)
                } else {
                    waypoint
                }
            }
            route.copy(waypoints = updatedWaypoints, lastModified = java.time.Instant.now())
        }
    }

    /**
     * Update route metadata
     */
    fun updateRouteMetadata(routeId: String, name: String? = null, description: String? = null, tags: List<String>? = null) {
        update(routeId) { route ->
            route.withMetadata(name, description, tags)
        }
    }

    /**
     * Update route visibility
     */
    fun setRouteVisibility(routeId: String, visible: Boolean) {
        update(routeId) { route ->
            route.withVisibility(visible)
        }
    }

    /**
     * Update route styling
     */
    fun setRouteStyling(routeId: String, color: RouteColor) {
        update(routeId) { route ->
            route.withStyling(color)
        }
    }

    /**
     * Clear all routes
     */
    fun clear() {
        _routes.value = emptyList()
        _currentRouteId.value = null
    }

    /**
     * Get routes by visibility
     */
    fun getVisibleRoutes(): List<Route> {
        return _routes.value.filter { it.isVisible }
    }

    /**
     * Get routes by waypoint type
     */
    fun getRoutesWithWaypointType(type: Waypoint.Type): List<Route> {
        return _routes.value.filter { route ->
            route.waypoints.any { it.type == type }
        }
    }

    /**
     * Get total waypoint count across all routes
     */
    fun getTotalWaypointCount(): Int {
        return _routes.value.sumOf { it.waypoints.size }
    }

    /**
     * Check if any routes exist
     */
    fun hasRoutes(): Boolean {
        return _routes.value.isNotEmpty()
    }

    /**
     * Get route statistics for debugging
     */
    fun getRouteStats(): Map<String, Any> {
        val allRoutes = _routes.value
        return mapOf(
            "total_routes" to allRoutes.size,
            "current_route_id" to (_currentRouteId.value ?: "none"),
            "total_waypoints" to getTotalWaypointCount(),
            "visible_routes" to getVisibleRoutes().size
        )
    }
}