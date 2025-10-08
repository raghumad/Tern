package com.madanala.tern.redux

import org.osmdroid.util.GeoPoint

/**
 * Redux actions for route planner functionality
 */
sealed class RouteAction {

    // Route Management Actions
    data class CreateNewRoute(val name: String, val description: String = "") : RouteAction()
    data class LoadRoute(val routeId: String) : RouteAction()
    data class SaveRoute(val route: Route) : RouteAction()
    data class UpdateRoute(val route: Route) : RouteAction()
    data class DeleteRoute(val routeId: String) : RouteAction()
    data class DuplicateRoute(val routeId: String, val newName: String) : RouteAction()
    data class SetFavoriteRoute(val routeId: String, val isFavorite: Boolean) : RouteAction()

    // Waypoint Management Actions
    data class AddWaypoint(val waypoint: Waypoint) : RouteAction()
    data class UpdateWaypoint(val index: Int, val waypoint: Waypoint) : RouteAction()
    data class RemoveWaypoint(val index: Int) : RouteAction()
    data class MoveWaypoint(val fromIndex: Int, val toIndex: Int) : RouteAction()
    data class ReorderWaypoints(val waypointIndices: List<Int>) : RouteAction()

    // Waypoint Creation Actions
    data class StartCreatingWaypoint(val location: GeoPoint, val waypointType: WaypointType) : RouteAction()
    data object CancelCreatingWaypoint : RouteAction()
    data class ConfirmWaypointCreation(val waypoint: Waypoint) : RouteAction()

    // Waypoint Editing Actions
    data class SelectWaypoint(val index: Int?) : RouteAction()
    data class StartEditingWaypoint(val index: Int) : RouteAction()
    data object CancelEditingWaypoint : RouteAction()
    data class ConfirmWaypointEdit(val index: Int, val waypoint: Waypoint) : RouteAction()

    // Route Calculation Actions
    data class CalculateRouteStatistics(val routeId: String) : RouteAction()
    data class CalculateOptimalRoute(val startPoint: GeoPoint, val endPoint: GeoPoint) : RouteAction()
    data class UpdateRouteCalculationProgress(val progress: Float) : RouteAction()

    // Cache Management Actions
    data class StartCachingRoute(val routeId: String) : RouteAction()
    data class UpdateCacheProgress(val progress: Float) : RouteAction()
    data class CompleteCacheOperation(val cacheInfo: RouteCacheInfo) : RouteAction()
    data object CancelCacheOperation : RouteAction()
    data class ClearRouteCache(val routeId: String) : RouteAction()

    // Safety Checklist Actions
    data class InitializeSafetyChecklist(val category: SafetyCategory) : RouteAction()
    data class UpdateSafetyChecklistItem(
        val itemId: String,
        val isCompleted: Boolean,
        val userNotes: String = ""
    ) : RouteAction()
    data object ResetSafetyChecklist : RouteAction()
    data object ValidateSafetyChecklist : RouteAction()

    // Export Actions
    data class StartExport(val routeId: String, val format: ExportFormat) : RouteAction()
    data class UpdateExportProgress(val progress: Float) : RouteAction()
    data class CompleteExport(val filePath: String, val format: ExportFormat) : RouteAction()
    data object CancelExport : RouteAction()

    // UI State Actions
    data class SetEditMode(val isEditMode: Boolean) : RouteAction()
    data class SetCurrentRoute(val route: Route?) : RouteAction()
    data object ClearError : RouteAction()

    // Batch Operations
    data class ImportRoutes(val routes: List<Route>) : RouteAction()
    data class ExportMultipleRoutes(val routeIds: List<String>, val format: ExportFormat) : RouteAction()

    // Error Handling
    data class SetError(val error: String) : RouteAction()
    data object ClearRouteState : RouteAction()
}

/**
 * Export format enumeration for route sharing
 */
enum class ExportFormat {
    XCTSK,  // XCTrack competition format (iOS-tested)
    GPX,    // GPS Exchange Format
    KML,    // Google Earth format
    CUP,    // GPS device format
    IGC,    // FAI official track format
    QR_CODE // QR code for instant sharing
}

/**
 * Competition task types for FAI compliance
 */
enum class CompetitionTaskType {
    RACE_TO_GOAL,   // Race to Goal task
    ELAPSED_TIME,   // Elapsed Time task
    OPEN_DISTANCE,  // Open Distance task
    TRIANGLE,       // Triangle task
    CUSTOM          // Custom task definition
}

/**
 * Route validation result
 */
data class RouteValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)

/**
 * Export result information
 */
data class ExportResult(
    val success: Boolean,
    val filePath: String? = null,
    val format: ExportFormat,
    val fileSize: Long = 0,
    val error: String? = null,
    val exportedAt: Long = System.currentTimeMillis()
)

/**
 * Cache operation result
 */
data class CacheOperationResult(
    val success: Boolean,
    val cacheInfo: RouteCacheInfo? = null,
    val error: String? = null,
    val completedAt: Long = System.currentTimeMillis()
)

/**
 * Route calculation result
 */
data class RouteCalculationResult(
    val success: Boolean,
    val statistics: RouteStatistics? = null,
    val error: String? = null,
    val calculatedAt: Long = System.currentTimeMillis()
)