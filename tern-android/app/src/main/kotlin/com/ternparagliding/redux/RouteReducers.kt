package com.ternparagliding.redux

import org.osmdroid.util.GeoPoint
import com.ternparagliding.model.LocationType

/**
 * Reducers for routes, waypoints, interactive editing, route selection,
 * long-press map creation, and smart suggestions.
 *
 * Split out of MapReducers.kt (Phase 0c god-file split). The handlers
 * called by the `mapReducer` dispatcher are `internal` (same module) rather
 * than file-`private`; the helpers below them remain file-private.
 */

// Route Planning Constants
private const val DEFAULT_ROUTE_NAME_PREFIX = "Route"
private const val WAYPOINT_LABEL_PREFIX = "WP"
private const val WAYPOINT_LABEL_SEPARATOR = "-"
private const val FIRST_ROUTE_INDEX = 1

/**
 * Handle long press map action (Waypoint Creation / Smart Select)
 */
internal fun handleLongPressMap(state: MapState, action: MapAction.LongPressMap): MapState {
    // 1. Smart Select: Check for nearby waypoints
    val nearbyResult = findNearbyWaypoint(state.routes, action.geoPoint, 0.05)
    if (nearbyResult != null) {
        val (route, waypoint) = nearbyResult
        return state.copy(
            selectedRouteId = route.id,
            selectedWaypoint = WaypointSelection(route.id, waypoint.id)
        )
    }

    // 2. Create New Waypoint/Route
    if (state.routes.isEmpty()) {
        // Create first route
        val newRoute = createFirstRoute(action.geoPoint, 1, action.type, action.label)
        return state.copy(
            routes = state.routes + newRoute,
            selectedRouteId = newRoute.id,
            selectedWaypoint = WaypointSelection(newRoute.id, newRoute.waypoints.first().id)
        )
    } else {
        val selectedRouteId = state.selectedRouteId
        if (selectedRouteId != null) {
            // Add to selected route
            val selectedRoute = state.routes.find { it.id == selectedRouteId }
            if (selectedRoute != null) {
                val (updatedRoutes, newWaypointId) = addWaypointToRouteState(state.routes, selectedRoute, action.geoPoint, action.type, action.label)
                return state.copy(
                    routes = updatedRoutes,
                    selectedWaypoint = WaypointSelection(selectedRouteId, newWaypointId),
                    isRoutePanelExpanded = false // Strategic Auto-Minimize
                )
            }
        }

        // No route selected or selected route not found -> Create new route
        val newRouteIndex = state.routes.size + 1
        val newRoute = createFirstRoute(action.geoPoint, newRouteIndex, action.type, action.label)
        return state.copy(
            routes = state.routes + newRoute,
            selectedRouteId = newRoute.id,
            selectedWaypoint = WaypointSelection(newRoute.id, newRoute.waypoints.first().id),
            isRoutePanelExpanded = false // Strategic Auto-Minimize
        )
    }
}

/**
 * Helper: Add waypoint to a route and return updated routes list + new waypoint ID
 */
private fun addWaypointToRouteState(
    routes: List<com.ternparagliding.model.Route>,
    targetRoute: com.ternparagliding.model.Route,
    geoPoint: GeoPoint,
    type: LocationType = LocationType.TURNPOINT,
    label: String? = null
): Pair<List<com.ternparagliding.model.Route>, String> {
    val waypointNumber = targetRoute.waypoints.size + 1
    val routeIndex = routes.indexOf(targetRoute) + 1
    val finalLabel = label ?: "$WAYPOINT_LABEL_PREFIX$routeIndex$WAYPOINT_LABEL_SEPARATOR$waypointNumber"
    val newWaypointId = java.util.UUID.randomUUID().toString()

    val updatedRoutes = routes.map { route ->
        if (route.id == targetRoute.id) {
            route.addWaypoint(
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = type,
                label = finalLabel,
                id = newWaypointId
            )
        } else route
    }
    return Pair(updatedRoutes, newWaypointId)
}

/**
 * Helper: Create the first route with a single waypoint
 */
private fun createFirstRoute(
    geoPoint: GeoPoint,
    routeIndex: Int,
    type: LocationType = LocationType.TURNPOINT,
    label: String? = null
): com.ternparagliding.model.Route {
    val waypointLabel = label ?: "$WAYPOINT_LABEL_PREFIX$routeIndex$WAYPOINT_LABEL_SEPARATOR$FIRST_ROUTE_INDEX"
    val routeName = "$DEFAULT_ROUTE_NAME_PREFIX $routeIndex"

    return com.ternparagliding.model.Route(
        name = routeName,
        waypoints = listOf(
            com.ternparagliding.model.Waypoint(
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = type,
                label = waypointLabel
            )
        )
    )
}

/**
 * Helper: Find existing waypoint within tolerance distance
 */
private fun findNearbyWaypoint(
    routes: List<com.ternparagliding.model.Route>,
    geoPoint: GeoPoint,
    toleranceDegrees: Double
): Pair<com.ternparagliding.model.Route, com.ternparagliding.model.Waypoint>? {
    val targetHilbertIndex = com.ternparagliding.utils.cache.MapOverlayCacheUtils.computeHilbertIndex(geoPoint, 16)

    routes.forEach { route ->
        route.waypoints.forEach { waypoint ->
            val dLat = waypoint.lat - geoPoint.latitude
            val dLon = waypoint.lon - geoPoint.longitude
            val distanceDegrees = kotlin.math.sqrt(dLat * dLat + dLon * dLon)

            if (distanceDegrees <= toleranceDegrees) {
                return Pair(route, waypoint)
            }
        }
    }
    return null
}

/**
 * Handle smart suggestion actions
 */
internal fun handleSmartSuggestionActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SetSmartSuggestion -> state.copy(smartSuggestionState = SmartSuggestionState(
        nearbyPGSpot = action.nearbyPGSpot,
        pendingWaypointCreation = action.pendingWaypointCreation
    ))
    MapAction.ClearSmartSuggestion -> state.copy(smartSuggestionState = SmartSuggestionState(
        nearbyPGSpot = null,
        pendingWaypointCreation = null
    ))
    is MapAction.CheckSmartSuggestion -> state
    else -> state
}

/**
 * Handle route management actions
 */
internal fun handleRouteActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.AddRoute -> {
        val newRoutes = state.routes + action.route
        val limitedRoutes = if (newRoutes.size > RouteConstants.MAX_ROUTES) {
            newRoutes.sortedByDescending { it.createdAt }.take(RouteConstants.MAX_ROUTES)
        } else newRoutes

        val updatedSelection = updateSelectionAfterRouteChange(state.selectedWaypoint, limitedRoutes, action.route.id)
        state.copy(routes = limitedRoutes, selectedWaypoint = updatedSelection)
    }
    is MapAction.RemoveRoute -> {
        val newRoutes = state.routes.filter { it.id != action.routeId }
        val updatedSelection = state.selectedWaypoint?.takeIf { it.routeId != action.routeId }
        val updatedSelectedRouteId = state.selectedRouteId?.takeIf { it != action.routeId }
        state.copy(routes = newRoutes, selectedWaypoint = updatedSelection, selectedRouteId = updatedSelectedRouteId)
    }
    is MapAction.UpdateRoute -> {
        val newRoutes = state.routes.map { if (it.id == action.route.id) action.route else it }
        val updatedSelection = updateSelectionAfterRouteChange(state.selectedWaypoint, newRoutes, action.route.id)
        state.copy(routes = newRoutes, selectedWaypoint = updatedSelection)
    }
    is MapAction.ClearAllRoutes -> state.copy(routes = emptyList(), selectedRouteId = null, selectedWaypoint = null)
    // Merge nearby preplanned routes (from the spatial RouteCache) into the
    // active set, skipping any already present. Existing/edited routes win;
    // selection is untouched. Capped at MAX_ROUTES like AddRoute.
    is MapAction.SurfaceNearbyRoutes -> {
        val existingIds = state.routes.map { it.id }.toSet()
        val toAdd = action.routes.filter { it.id !in existingIds }
        if (toAdd.isEmpty()) state
        else {
            val merged = state.routes + toAdd
            val limited = if (merged.size > RouteConstants.MAX_ROUTES) {
                merged.sortedByDescending { it.createdAt }.take(RouteConstants.MAX_ROUTES)
            } else merged
            state.copy(routes = limited)
        }
    }
    else -> state
}

/**
 * Handle waypoint management actions
 */
internal fun handleWaypointActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.AddWaypointToRoute -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.addWaypoint(action.lat, action.lon, action.type, action.label, action.id)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.RemoveWaypoint -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) route.removeWaypoint(action.waypointId) else route
        }
        val updatedSelection = state.selectedWaypoint?.takeIf {
            !(it.routeId == action.routeId && it.waypointId == action.waypointId)
        }
        state.copy(routes = newRoutes, selectedWaypoint = updatedSelection)
    }
    is MapAction.UpdateWaypoint -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, action.lat, action.lon, action.type)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.UpdateWaypointType -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, null, null, action.type)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.UpdateWaypointRadius -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, radius = action.radius)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.UpdateWaypointAltitude -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, alt = action.alt)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.UpdateWaypointTimeGates -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.updateWaypoint(action.waypointId, openTime = action.openTime, closeTime = action.closeTime)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    is MapAction.ReorderWaypoint -> {
        val newRoutes = state.routes.map { route ->
            if (route.id == action.routeId) {
                route.reorderWaypoint(action.fromIndex, action.toIndex)
            } else route
        }
        state.copy(routes = newRoutes)
    }
    else -> state
}

/**
 * Handle interactive editing actions
 */
internal fun handleInteractiveEditingActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SelectWaypoint -> state.copy(selectedWaypoint = WaypointSelection(
        routeId = action.routeId,
        waypointId = action.waypointId,
        isDragging = false
    ))
    MapAction.DeselectWaypoint -> state.copy(selectedWaypoint = null)
    is MapAction.StartWaypointDrag -> {
        val route = state.routes.find { it.id == action.routeId }
        val waypoint = route?.waypoints?.find { it.id == action.waypointId }

        state.copy(selectedWaypoint = WaypointSelection(
            routeId = action.routeId,
            waypointId = action.waypointId,
            isDragging = true,
            originalLat = waypoint?.lat,
            originalLon = waypoint?.lon
        ))
    }
    is MapAction.UpdateWaypointDrag -> {
        val currentSelection = state.selectedWaypoint
        if (currentSelection?.isDragging == true) {
            val newRoutes = state.routes.map { route ->
                if (route.id == currentSelection.routeId) {
                    route.updateWaypoint(currentSelection.waypointId, action.lat, action.lon)
                } else route
            }
            state.copy(routes = newRoutes)
        } else state
    }
    MapAction.EndWaypointDrag -> {
        val currentSelection = state.selectedWaypoint
        if (currentSelection?.isDragging == true) {
            state.copy(selectedWaypoint = currentSelection.copy(isDragging = false))
        } else state
    }
    MapAction.CancelWaypointDrag -> {
        val currentSelection = state.selectedWaypoint
        if (currentSelection?.isDragging == true && currentSelection.originalLat != null && currentSelection.originalLon != null) {
            // Restore waypoint to original position
            val newRoutes = state.routes.map { route ->
                if (route.id == currentSelection.routeId) {
                    route.updateWaypoint(currentSelection.waypointId, currentSelection.originalLat, currentSelection.originalLon)
                } else route
            }
            state.copy(
                routes = newRoutes,
                selectedWaypoint = currentSelection.copy(isDragging = false)
            )
        } else state
    }
    else -> state
}

/**
 * Handle route selection actions
 */
internal fun handleRouteSelectionActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SelectRoute -> {
        // [RFC 005] Strategic Auto-Minimize: If zooming out to strategic level (< 11.0),
        // collapse the panel by default to show the whole route.
        val shouldExpand = state.zoom >= 11.0
        state.copy(
            selectedRouteId = action.routeId,
            selectedWaypoint = if (action.routeId == null) null else state.selectedWaypoint,
            isRoutePanelExpanded = if (action.routeId != null) shouldExpand else state.isRoutePanelExpanded
        )
    }
    MapAction.DeselectRoute -> state.copy(selectedRouteId = null, selectedWaypoint = null)
    else -> state
}

/**
 * Update waypoint selection after route changes
 */
private fun updateSelectionAfterRouteChange(
    currentSelection: WaypointSelection?,
    routes: List<com.ternparagliding.model.Route>,
    changedRouteId: String
): WaypointSelection? {
    if (currentSelection == null) return null

    val route = routes.find { it.id == changedRouteId } ?: return null
    val waypointExists = route.waypoints.any { it.id == currentSelection.waypointId }

    return if (waypointExists) currentSelection else null
}
