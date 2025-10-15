package com.madanala.tern.redux

import com.madanala.tern.model.Waypoint
import com.madanala.tern.route.Route
import com.madanala.tern.route.RouteColor

/**
 * Redux actions for route management - for Phase 2 Redux migration
 * Follows the same patterns as MapAction with sealed class structure
 */
sealed class RouteActions {

    // Route lifecycle actions
    data class CreateRoute(
        val name: String,
        val waypoints: List<Waypoint> = emptyList()
    ) : RouteActions()

    data class AddRoute(val route: Route) : RouteActions()
    data class UpdateRoute(val routeId: String, val route: Route) : RouteActions()
    data class RemoveRoute(val routeId: String) : RouteActions()

    // Route selection actions
    data class SetCurrentRoute(val routeId: String?) : RouteActions()
    object ClearCurrentRoute : RouteActions()

    // Waypoint management actions
    data class AddWaypointToRoute(
        val routeId: String,
        val waypoint: Waypoint
    ) : RouteActions()

    data class RemoveWaypointFromRoute(
        val routeId: String,
        val waypointId: String
    ) : RouteActions()

    data class UpdateWaypointInRoute(
        val routeId: String,
        val waypointId: String,
        val lat: Double,
        val lon: Double
    ) : RouteActions()

    // Route metadata actions
    data class UpdateRouteMetadata(
        val routeId: String,
        val name: String? = null,
        val description: String? = null,
        val tags: List<String>? = null
    ) : RouteActions()

    data class SetRouteVisibility(
        val routeId: String,
        val visible: Boolean
    ) : RouteActions()

    data class SetRouteStyling(
        val routeId: String,
        val color: RouteColor
    ) : RouteActions()

    // Bulk operations
    object ClearAllRoutes : RouteActions()

    data class UpdateMultipleRoutes(
        val updates: List<Pair<String, Route>>
    ) : RouteActions()

    // Route validation and optimization
    data class ValidateRoute(val routeId: String) : RouteActions()
    data class OptimizeRouteOrder(val routeId: String) : RouteActions()

    // Route import/export actions (for future features)
    data class ImportRoute(val routeData: String, val format: RouteImportFormat) : RouteActions()
    data class ExportRoute(val routeId: String, val format: RouteExportFormat) : RouteActions()

    // Route synchronization (for multi-device support)
    data class SyncRoutes(val routes: List<Route>) : RouteActions()
    object RequestRouteSync : RouteActions()
}

/**
 * Supported import formats for route data
 */
enum class RouteImportFormat {
    GPX, XCTSK, CUP, KML, GEOJSON
}

/**
 * Supported export formats for route data
 */
enum class RouteExportFormat {
    GPX, XCTSK, CUP, KML, GEOJSON, QR_CODE
}