package com.madanala.tern.redux

import com.madanala.tern.route.Route
import com.madanala.tern.route.RouteColor

/**
 * Redux state for route management - for Phase 2 Redux migration
 * Follows the same immutable data class pattern as MapState
 */
data class RouteState(
    // Core route data
    val routes: List<Route> = emptyList(),

    // Route selection and focus
    val currentRouteId: String? = null,
    val selectedWaypointIds: Set<String> = emptySet(),

    // UI state for route editing
    val isEditingMode: Boolean = false,
    val showWaypointTypes: Boolean = true,
    val showRouteStats: Boolean = true,

    // Route creation state
    val isCreatingRoute: Boolean = false,
    val pendingRouteName: String = "",
    val pendingRouteDescription: String = "",

    // Route import/export state
    val isImportingRoute: Boolean = false,
    val isExportingRoute: Boolean = false,
    val lastImportFormat: RouteImportFormat? = null,
    val lastExportFormat: RouteExportFormat? = null,

    // Route validation state
    val routeValidationErrors: Map<String, List<String>> = emptyMap(),
    val isValidatingRoutes: Boolean = false,

    // Performance and optimization state
    val visibleRoutesOnly: Boolean = true,
    val maxVisibleRoutes: Int = 5,

    // Synchronization state (for multi-device support)
    val isSyncing: Boolean = false,
    val lastSyncTimestamp: Long? = null,
    val syncConflicts: List<RouteSyncConflict> = emptyList(),

    // Route statistics and analytics
    val totalDistance: Double = 0.0,
    val totalWaypoints: Int = 0,
    val routeCountByType: Map<WaypointType, Int> = emptyMap(),

    // User preferences for route display
    val defaultRouteColor: RouteColor = RouteColor.DEFAULT,
    val showRouteDistances: Boolean = true,
    val showWaypointLabels: Boolean = true,
    val snapToGrid: Boolean = false,

    // Route optimization state
    val isOptimizingRoutes: Boolean = false,
    val optimizationResults: Map<String, RouteOptimizationResult> = emptyMap()
)

/**
 * Waypoint type enumeration for analytics
 */
enum class WaypointType {
    LAUNCH, TURNPOINT, LANDING, UNKNOWN
}

/**
 * Route synchronization conflict information
 */
data class RouteSyncConflict(
    val routeId: String,
    val localRoute: Route,
    val remoteRoute: Route,
    val conflictType: SyncConflictType,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Types of synchronization conflicts
 */
enum class SyncConflictType {
    WAYPOINT_COUNT_MISMATCH,
    WAYPOINT_POSITION_DIFFERENCE,
    METADATA_DIFFERENCE,
    DELETION_CONFLICT
}

/**
 * Result of route optimization operation
 */
data class RouteOptimizationResult(
    val routeId: String,
    val originalDistance: Double,
    val optimizedDistance: Double,
    val waypointsReordered: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Extension functions for RouteState
 */
fun RouteState.getCurrentRoute(): Route? {
    return currentRouteId?.let { routeId ->
        routes.find { route -> route.id == routeId }
    }
}

fun RouteState.getVisibleRoutes(): List<Route> {
    return if (visibleRoutesOnly) {
        routes.filter { route -> route.isVisible }
    } else {
        routes
    }
}

fun RouteState.getRoutesWithErrors(): List<Route> {
    return routes.filter { route ->
        routeValidationErrors.containsKey(route.id)
    }
}

fun RouteState.getTotalVisibleRoutes(): Int {
    return getVisibleRoutes().size
}

fun RouteState.isRouteSelected(routeId: String): Boolean {
    return currentRouteId == routeId
}

fun RouteState.getWaypointCount(routeId: String): Int {
    return routes.find { route -> route.id == routeId }?.waypoints?.size ?: 0
}

fun RouteState.hasRoutes(): Boolean {
    return routes.isNotEmpty()
}

fun RouteState.isEmpty(): Boolean {
    return routes.isEmpty()
}
