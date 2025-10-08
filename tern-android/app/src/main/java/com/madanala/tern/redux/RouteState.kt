package com.madanala.tern.redux

import org.osmdroid.util.GeoPoint
import java.util.UUID

/**
 * Waypoint type enumeration for different route elements
 */
enum class WaypointType {
    LAUNCH,      // Takeoff site with elevation and conditions
    TURNPOINT,   // FAI competition cylinder (400m default radius)
    LANDING,     // Safe landing zone with terrain analysis
    INTERMEDIATE, // Route waypoints for navigation
    THERMAL      // Known thermal areas for soaring
}

/**
 * FAI turnpoint cylinder shape options
 */
enum class CylinderShape {
    CIRCLE,      // Standard circular cylinder
    SECTOR_90,   // 90-degree sector for competition tasks
    SECTOR_180,  // 180-degree sector
    LINE         // Line for speed tasks
}

/**
 * Individual waypoint in a route
 */
data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val location: GeoPoint,
    val waypointType: WaypointType,
    val elevation: Double? = null, // meters MSL
    val cylinderRadius: Double = 400.0, // meters (FAI standard)
    val cylinderShape: CylinderShape = CylinderShape.CIRCLE,
    val isRequired: Boolean = true, // Must be reached in competition
    val customNotes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Safety checklist categories for pre-flight validation
 */
enum class SafetyCategory {
    AVIATION,     // Wing, harness, reserve checks
    RESOURCES,    // Water, food, emergency cash
    ELECTRONICS,  // Phone, radio, GPS, lights
    EMERGENCY     // Contacts, flight plan, retrieve driver
}

/**
 * Individual safety checklist item
 */
data class SafetyChecklistItem(
    val id: String = UUID.randomUUID().toString(),
    val category: SafetyCategory,
    val title: String,
    val description: String,
    val isRequired: Boolean = true,
    val isCompleted: Boolean = false,
    val userNotes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Complete safety checklist for route planning
 */
data class SafetyChecklist(
    val items: List<SafetyChecklistItem> = emptyList(),
    val isValid: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val completionPercentage: Double
        get() = if (totalCount > 0) (completedCount.toDouble() / totalCount) * 100.0 else 0.0

    val isComplete: Boolean
        get() = isValid && completedCount == totalCount
}

/**
 * Cache status for route data
 */
enum class CacheStatus {
    EMPTY,       // No data cached
    DOWNLOADING, // Currently downloading
    PARTIAL,     // Some data cached
    COMPLETE,    // All data cached
    ERROR        // Cache error occurred
}

/**
 * Route cache information
 */
data class RouteCacheInfo(
    val routeLineTiles: CacheStatus = CacheStatus.EMPTY,
    val airspaceData: CacheStatus = CacheStatus.EMPTY,
    val terrainData: CacheStatus = CacheStatus.EMPTY,
    val weatherData: CacheStatus = CacheStatus.EMPTY,
    val pgSpotsData: CacheStatus = CacheStatus.EMPTY,
    val lastUpdated: Long = System.currentTimeMillis(),
    val downloadProgress: Float = 0.0f // 0.0 to 1.0
) {
    val overallStatus: CacheStatus
        get() = when {
            routeLineTiles == CacheStatus.ERROR || airspaceData == CacheStatus.ERROR ||
            terrainData == CacheStatus.ERROR || weatherData == CacheStatus.ERROR ||
            pgSpotsData == CacheStatus.ERROR -> CacheStatus.ERROR
            routeLineTiles == CacheStatus.COMPLETE && airspaceData == CacheStatus.COMPLETE &&
            terrainData == CacheStatus.COMPLETE && weatherData == CacheStatus.COMPLETE &&
            pgSpotsData == CacheStatus.COMPLETE -> CacheStatus.COMPLETE
            routeLineTiles == CacheStatus.EMPTY && airspaceData == CacheStatus.EMPTY &&
            terrainData == CacheStatus.EMPTY && weatherData == CacheStatus.EMPTY &&
            pgSpotsData == CacheStatus.EMPTY -> CacheStatus.EMPTY
            else -> CacheStatus.PARTIAL
        }

    val isReadyForOffline: Boolean
        get() = overallStatus == CacheStatus.COMPLETE
}

/**
 * Route statistics for display
 */
data class RouteStatistics(
    val totalDistance: Double = 0.0, // meters
    val legDistances: List<Double> = emptyList(), // meters per leg
    val totalElevationGain: Double = 0.0, // meters
    val estimatedDuration: Double = 0.0, // seconds
    val thermalOpportunities: Int = 0,
    val riskFactors: Int = 0,
    val lastCalculated: Long = System.currentTimeMillis()
)

/**
 * Complete route definition
 */
data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val waypoints: List<Waypoint> = emptyList(),
    val isCompetitionRoute: Boolean = false,
    val competitionType: String? = null, // Race to Goal, Elapsed Time, etc.
    val safetyChecklist: SafetyChecklist = SafetyChecklist(),
    val cacheInfo: RouteCacheInfo = RouteCacheInfo(),
    val statistics: RouteStatistics = RouteStatistics(),
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isValid: Boolean
        get() = waypoints.size >= 2 && waypoints.first().waypointType == WaypointType.LAUNCH

    val hasRequiredWaypoints: Boolean
        get() = waypoints.any { it.isRequired }
}

/**
 * Redux state for route planner feature
 */
data class RouteState(
    // Route management
    val routes: List<Route> = emptyList(),
    val selectedRouteId: String? = null,
    val isEditMode: Boolean = false,

    // Current route being edited
    val currentRoute: Route? = null,
    val currentWaypoints: List<Waypoint> = emptyList(),

    // UI state
    val isCreatingWaypoint: Boolean = false,
    val selectedWaypointIndex: Int? = null,
    val waypointEditMode: Boolean = false,

    // Cache state
    val cacheStatus: CacheStatus = CacheStatus.EMPTY,
    val currentCacheInfo: RouteCacheInfo = RouteCacheInfo(),

    // Safety state
    val safetyChecklist: SafetyChecklist = SafetyChecklist(),

    // Export state
    val isExporting: Boolean = false,
    val exportProgress: Float = 0.0f,
    val lastExportFormat: String? = null,

    // Performance state
    val isCalculatingRoute: Boolean = false,
    val routeCalculationProgress: Float = 0.0f,

    // Error state
    val error: String? = null,
    val lastErrorTime: Long = 0
) {

    val selectedRoute: Route?
        get() = routes.find { it.id == selectedRouteId } ?: currentRoute

    val hasRoutes: Boolean
        get() = routes.isNotEmpty() || currentRoute != null

    val isRouteValid: Boolean
        get() = selectedRoute?.isValid == true

    val canExportRoute: Boolean
        get() = isRouteValid && !isExporting

    val isReadyForOffline: Boolean
        get() = selectedRoute?.cacheInfo?.isReadyForOffline == true

    val safetyCompletionPercentage: Double
        get() = safetyChecklist.completionPercentage
}