package com.madanala.tern.redux

import kotlin.math.max
import org.osmdroid.util.GeoPoint

/**
 * Redux reducers for route planner state management
 */
object RouteReducers {

    fun reduce(state: RouteState, action: RouteAction): RouteState {
        return when (action) {
            // Route Management
            is RouteAction.CreateNewRoute -> handleCreateNewRoute(state, action)
            is RouteAction.LoadRoute -> handleLoadRoute(state, action)
            is RouteAction.SaveRoute -> handleSaveRoute(state, action)
            is RouteAction.UpdateRoute -> handleUpdateRoute(state, action)
            is RouteAction.DeleteRoute -> handleDeleteRoute(state, action)
            is RouteAction.DuplicateRoute -> handleDuplicateRoute(state, action)
            is RouteAction.SetFavoriteRoute -> handleSetFavoriteRoute(state, action)

            // Waypoint Management
            is RouteAction.AddWaypoint -> handleAddWaypoint(state, action)
            is RouteAction.UpdateWaypoint -> handleUpdateWaypoint(state, action)
            is RouteAction.RemoveWaypoint -> handleRemoveWaypoint(state, action)
            is RouteAction.MoveWaypoint -> handleMoveWaypoint(state, action)
            is RouteAction.ReorderWaypoints -> handleReorderWaypoints(state, action)

            // Waypoint Creation
            is RouteAction.StartCreatingWaypoint -> handleStartCreatingWaypoint(state, action)
            is RouteAction.CancelCreatingWaypoint -> handleCancelCreatingWaypoint(state)
            is RouteAction.ConfirmWaypointCreation -> handleConfirmWaypointCreation(state, action)

            // Waypoint Editing
            is RouteAction.SelectWaypoint -> handleSelectWaypoint(state, action)
            is RouteAction.StartEditingWaypoint -> handleStartEditingWaypoint(state, action)
            is RouteAction.CancelEditingWaypoint -> handleCancelEditingWaypoint(state)
            is RouteAction.ConfirmWaypointEdit -> handleConfirmWaypointEdit(state, action)

            // Route Calculation
            is RouteAction.CalculateRouteStatistics -> handleCalculateRouteStatistics(state, action)
            is RouteAction.CalculateOptimalRoute -> handleCalculateOptimalRoute(state, action)
            is RouteAction.UpdateRouteCalculationProgress -> handleUpdateRouteCalculationProgress(state, action)

            // Cache Management
            is RouteAction.StartCachingRoute -> handleStartCachingRoute(state, action)
            is RouteAction.UpdateCacheProgress -> handleUpdateCacheProgress(state, action)
            is RouteAction.CompleteCacheOperation -> handleCompleteCacheOperation(state, action)
            is RouteAction.CancelCacheOperation -> handleCancelCacheOperation(state)
            is RouteAction.ClearRouteCache -> handleClearRouteCache(state, action)

            // Safety Checklist
            is RouteAction.InitializeSafetyChecklist -> handleInitializeSafetyChecklist(state, action)
            is RouteAction.UpdateSafetyChecklistItem -> handleUpdateSafetyChecklistItem(state, action)
            is RouteAction.ResetSafetyChecklist -> handleResetSafetyChecklist(state)
            is RouteAction.ValidateSafetyChecklist -> handleValidateSafetyChecklist(state)

            // Export
            is RouteAction.StartExport -> handleStartExport(state, action)
            is RouteAction.UpdateExportProgress -> handleUpdateExportProgress(state, action)
            is RouteAction.CompleteExport -> handleCompleteExport(state, action)
            is RouteAction.CancelExport -> handleCancelExport(state)

            // UI State
            is RouteAction.SetEditMode -> handleSetEditMode(state, action)
            is RouteAction.SetCurrentRoute -> handleSetCurrentRoute(state, action)
            is RouteAction.ClearError -> handleClearError(state)

            // Batch Operations
            is RouteAction.ImportRoutes -> handleImportRoutes(state, action)
            is RouteAction.ExportMultipleRoutes -> handleExportMultipleRoutes(state, action)

            // Error Handling
            is RouteAction.SetError -> handleSetError(state, action)
            is RouteAction.ClearRouteState -> handleClearRouteState(state)
        }
    }

    // Route Management Handlers
    private fun handleCreateNewRoute(state: RouteState, action: RouteAction.CreateNewRoute): RouteState {
        val newRoute = Route(
            name = action.name,
            description = action.description
        )
        return state.copy(
            currentRoute = newRoute,
            currentWaypoints = newRoute.waypoints,
            isEditMode = true,
            error = null
        )
    }

    private fun handleLoadRoute(state: RouteState, action: RouteAction.LoadRoute): RouteState {
        val route = state.routes.find { it.id == action.routeId }
        return if (route != null) {
            state.copy(
                selectedRouteId = action.routeId,
                currentRoute = route,
                currentWaypoints = route.waypoints,
                safetyChecklist = route.safetyChecklist,
                error = null
            )
        } else {
            state.copy(error = "Route not found: ${action.routeId}")
        }
    }

    private fun handleSaveRoute(state: RouteState, action: RouteAction.SaveRoute): RouteState {
        val updatedRoutes = state.routes.toMutableList()
        val existingIndex = updatedRoutes.indexOfFirst { it.id == action.route.id }

        val updatedRoute = action.route.copy(updatedAt = System.currentTimeMillis())

        if (existingIndex >= 0) {
            updatedRoutes[existingIndex] = updatedRoute
        } else {
            updatedRoutes.add(updatedRoute)
        }

        return state.copy(
            routes = updatedRoutes,
            currentRoute = updatedRoute,
            selectedRouteId = updatedRoute.id,
            error = null
        )
    }

    private fun handleUpdateRoute(state: RouteState, action: RouteAction.UpdateRoute): RouteState {
        return state.copy(
            currentRoute = action.route.copy(updatedAt = System.currentTimeMillis()),
            currentWaypoints = action.route.waypoints,
            error = null
        )
    }

    private fun handleDeleteRoute(state: RouteState, action: RouteAction.DeleteRoute): RouteState {
        val updatedRoutes = state.routes.filterNot { it.id == action.routeId }
        val newSelectedRouteId = if (state.selectedRouteId == action.routeId) null else state.selectedRouteId

        return state.copy(
            routes = updatedRoutes,
            selectedRouteId = newSelectedRouteId,
            currentRoute = if (newSelectedRouteId != null) {
                updatedRoutes.find { it.id == newSelectedRouteId }
            } else null,
            currentWaypoints = if (newSelectedRouteId != null) {
                updatedRoutes.find { it.id == newSelectedRouteId }?.waypoints ?: emptyList()
            } else emptyList()
        )
    }

    private fun handleDuplicateRoute(state: RouteState, action: RouteAction.DuplicateRoute): RouteState {
        val originalRoute = state.routes.find { it.id == action.routeId } ?: return state
        val duplicatedRoute = originalRoute.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = action.newName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        return state.copy(
            routes = state.routes + duplicatedRoute,
            selectedRouteId = duplicatedRoute.id,
            currentRoute = duplicatedRoute,
            currentWaypoints = duplicatedRoute.waypoints
        )
    }

    private fun handleSetFavoriteRoute(state: RouteState, action: RouteAction.SetFavoriteRoute): RouteState {
        val updatedRoutes = state.routes.map {
            if (it.id == action.routeId) it.copy(isFavorite = action.isFavorite, updatedAt = System.currentTimeMillis())
            else it
        }
        return state.copy(routes = updatedRoutes)
    }

    // Waypoint Management Handlers
    private fun handleAddWaypoint(state: RouteState, action: RouteAction.AddWaypoint): RouteState {
        val updatedWaypoints = state.currentWaypoints + action.waypoint
        val updatedRoute = state.currentRoute?.copy(
            waypoints = updatedWaypoints,
            updatedAt = System.currentTimeMillis()
        )
        return state.copy(
            currentWaypoints = updatedWaypoints,
            currentRoute = updatedRoute,
            error = null
        )
    }

    private fun handleUpdateWaypoint(state: RouteState, action: RouteAction.UpdateWaypoint): RouteState {
        if (action.index !in state.currentWaypoints.indices) return state

        val updatedWaypoints = state.currentWaypoints.toMutableList()
        updatedWaypoints[action.index] = action.waypoint

        val updatedRoute = state.currentRoute?.copy(
            waypoints = updatedWaypoints,
            updatedAt = System.currentTimeMillis()
        )

        return state.copy(
            currentWaypoints = updatedWaypoints,
            currentRoute = updatedRoute,
            error = null
        )
    }

    private fun handleRemoveWaypoint(state: RouteState, action: RouteAction.RemoveWaypoint): RouteState {
        if (action.index !in state.currentWaypoints.indices) return state

        val updatedWaypoints = state.currentWaypoints.toMutableList()
        updatedWaypoints.removeAt(action.index)

        val updatedRoute = state.currentRoute?.copy(
            waypoints = updatedWaypoints,
            updatedAt = System.currentTimeMillis()
        )

        return state.copy(
            currentWaypoints = updatedWaypoints,
            currentRoute = updatedRoute,
            selectedWaypointIndex = null,
            error = null
        )
    }

    private fun handleMoveWaypoint(state: RouteState, action: RouteAction.MoveWaypoint): RouteState {
        if (action.fromIndex !in state.currentWaypoints.indices ||
            action.toIndex !in state.currentWaypoints.indices) return state

        val updatedWaypoints = state.currentWaypoints.toMutableList()
        val waypoint = updatedWaypoints.removeAt(action.fromIndex)
        updatedWaypoints.add(action.toIndex, waypoint)

        val updatedRoute = state.currentRoute?.copy(
            waypoints = updatedWaypoints,
            updatedAt = System.currentTimeMillis()
        )

        return state.copy(
            currentWaypoints = updatedWaypoints,
            currentRoute = updatedRoute,
            error = null
        )
    }

    private fun handleReorderWaypoints(state: RouteState, action: RouteAction.ReorderWaypoints): RouteState {
        if (action.waypointIndices.size != state.currentWaypoints.size) return state

        val reorderedWaypoints = action.waypointIndices.map { index ->
            state.currentWaypoints.getOrNull(index) ?: return state
        }

        val updatedRoute = state.currentRoute?.copy(
            waypoints = reorderedWaypoints,
            updatedAt = System.currentTimeMillis()
        )

        return state.copy(
            currentWaypoints = reorderedWaypoints,
            currentRoute = updatedRoute,
            error = null
        )
    }

    // Waypoint Creation Handlers
    private fun handleStartCreatingWaypoint(state: RouteState, action: RouteAction.StartCreatingWaypoint): RouteState {
        return state.copy(
            isCreatingWaypoint = true,
            error = null
        )
    }

    private fun handleCancelCreatingWaypoint(state: RouteState): RouteState {
        return state.copy(
            isCreatingWaypoint = false,
            error = null
        )
    }

    private fun handleConfirmWaypointCreation(state: RouteState, action: RouteAction.ConfirmWaypointCreation): RouteState {
        return state.copy(
            currentWaypoints = state.currentWaypoints + action.waypoint,
            isCreatingWaypoint = false,
            error = null
        )
    }

    // Waypoint Editing Handlers
    private fun handleSelectWaypoint(state: RouteState, action: RouteAction.SelectWaypoint): RouteState {
        return state.copy(
            selectedWaypointIndex = action.index,
            waypointEditMode = action.index != null,
            error = null
        )
    }

    private fun handleStartEditingWaypoint(state: RouteState, action: RouteAction.StartEditingWaypoint): RouteState {
        return state.copy(
            waypointEditMode = true,
            error = null
        )
    }

    private fun handleCancelEditingWaypoint(state: RouteState): RouteState {
        return state.copy(
            waypointEditMode = false,
            selectedWaypointIndex = null,
            error = null
        )
    }

    private fun handleConfirmWaypointEdit(state: RouteState, action: RouteAction.ConfirmWaypointEdit): RouteState {
        if (action.index !in state.currentWaypoints.indices) return state

        val updatedWaypoints = state.currentWaypoints.toMutableList()
        updatedWaypoints[action.index] = action.waypoint

        val updatedRoute = state.currentRoute?.copy(
            waypoints = updatedWaypoints,
            updatedAt = System.currentTimeMillis()
        )

        return state.copy(
            currentWaypoints = updatedWaypoints,
            currentRoute = updatedRoute,
            waypointEditMode = false,
            error = null
        )
    }

    // Route Calculation Handlers
    private fun handleCalculateRouteStatistics(state: RouteState, action: RouteAction.CalculateRouteStatistics): RouteState {
        return state.copy(
            isCalculatingRoute = true,
            routeCalculationProgress = 0.0f,
            error = null
        )
    }

    private fun handleCalculateOptimalRoute(state: RouteState, action: RouteAction.CalculateOptimalRoute): RouteState {
        return state.copy(
            isCalculatingRoute = true,
            routeCalculationProgress = 0.0f,
            error = null
        )
    }

    private fun handleUpdateRouteCalculationProgress(state: RouteState, action: RouteAction.UpdateRouteCalculationProgress): RouteState {
        return state.copy(
            routeCalculationProgress = action.progress.coerceIn(0.0f, 1.0f),
            isCalculatingRoute = action.progress < 1.0f,
            error = null
        )
    }

    // Cache Management Handlers
    private fun handleStartCachingRoute(state: RouteState, action: RouteAction.StartCachingRoute): RouteState {
        return state.copy(
            cacheStatus = CacheStatus.DOWNLOADING,
            currentCacheInfo = state.currentCacheInfo.copy(
                routeLineTiles = CacheStatus.DOWNLOADING,
                lastUpdated = System.currentTimeMillis()
            ),
            error = null
        )
    }

    private fun handleUpdateCacheProgress(state: RouteState, action: RouteAction.UpdateCacheProgress): RouteState {
        return state.copy(
            currentCacheInfo = state.currentCacheInfo.copy(
                downloadProgress = action.progress.coerceIn(0.0f, 1.0f)
            ),
            error = null
        )
    }

    private fun handleCompleteCacheOperation(state: RouteState, action: RouteAction.CompleteCacheOperation): RouteState {
        return state.copy(
            cacheStatus = action.cacheInfo.overallStatus,
            currentCacheInfo = action.cacheInfo,
            error = null
        )
    }

    private fun handleCancelCacheOperation(state: RouteState): RouteState {
        return state.copy(
            cacheStatus = CacheStatus.EMPTY,
            currentCacheInfo = RouteCacheInfo(),
            error = null
        )
    }

    private fun handleClearRouteCache(state: RouteState, action: RouteAction.ClearRouteCache): RouteState {
        return state.copy(
            cacheStatus = CacheStatus.EMPTY,
            currentCacheInfo = RouteCacheInfo(),
            error = null
        )
    }

    // Safety Checklist Handlers
    private fun handleInitializeSafetyChecklist(state: RouteState, action: RouteAction.InitializeSafetyChecklist): RouteState {
        val defaultItems = createDefaultSafetyChecklistItems(action.category)
        val checklist = SafetyChecklist(items = defaultItems)
        return state.copy(
            safetyChecklist = checklist,
            error = null
        )
    }

    private fun handleUpdateSafetyChecklistItem(state: RouteState, action: RouteAction.UpdateSafetyChecklistItem): RouteState {
        val updatedItems = state.safetyChecklist.items.map { item ->
            if (item.id == action.itemId) {
                item.copy(
                    isCompleted = action.isCompleted,
                    userNotes = action.userNotes,
                    createdAt = if (item.createdAt == 0L) System.currentTimeMillis() else item.createdAt
                )
            } else item
        }

        val completedCount = updatedItems.count { it.isCompleted }
        val isValid = updatedItems.filter { it.isRequired }.all { it.isCompleted }

        val updatedChecklist = state.safetyChecklist.copy(
            items = updatedItems,
            isValid = isValid,
            completedCount = completedCount,
            totalCount = updatedItems.size,
            lastUpdated = System.currentTimeMillis()
        )

        return state.copy(
            safetyChecklist = updatedChecklist,
            error = null
        )
    }

    private fun handleResetSafetyChecklist(state: RouteState): RouteState {
        val resetItems = state.safetyChecklist.items.map { it.copy(isCompleted = false, userNotes = "") }
        val checklist = state.safetyChecklist.copy(
            items = resetItems,
            isValid = false,
            completedCount = 0,
            lastUpdated = System.currentTimeMillis()
        )
        return state.copy(safetyChecklist = checklist)
    }

    private fun handleValidateSafetyChecklist(state: RouteState): RouteState {
        val isValid = state.safetyChecklist.items.filter { it.isRequired }.all { it.isCompleted }
        val checklist = state.safetyChecklist.copy(
            isValid = isValid,
            lastUpdated = System.currentTimeMillis()
        )
        return state.copy(safetyChecklist = checklist)
    }

    // Export Handlers
    private fun handleStartExport(state: RouteState, action: RouteAction.StartExport): RouteState {
        return state.copy(
            isExporting = true,
            exportProgress = 0.0f,
            error = null
        )
    }

    private fun handleUpdateExportProgress(state: RouteState, action: RouteAction.UpdateExportProgress): RouteState {
        return state.copy(
            exportProgress = action.progress.coerceIn(0.0f, 1.0f),
            error = null
        )
    }

    private fun handleCompleteExport(state: RouteState, action: RouteAction.CompleteExport): RouteState {
        return state.copy(
            isExporting = false,
            exportProgress = 1.0f,
            lastExportFormat = action.format.name,
            error = null
        )
    }

    private fun handleCancelExport(state: RouteState): RouteState {
        return state.copy(
            isExporting = false,
            exportProgress = 0.0f,
            error = null
        )
    }

    // UI State Handlers
    private fun handleSetEditMode(state: RouteState, action: RouteAction.SetEditMode): RouteState {
        return state.copy(
            isEditMode = action.isEditMode,
            error = null
        )
    }

    private fun handleSetCurrentRoute(state: RouteState, action: RouteAction.SetCurrentRoute): RouteState {
        return state.copy(
            currentRoute = action.route,
            currentWaypoints = action.route?.waypoints ?: emptyList(),
            selectedRouteId = action.route?.id,
            error = null
        )
    }

    private fun handleClearError(state: RouteState): RouteState {
        return state.copy(error = null)
    }

    // Batch Operations Handlers
    private fun handleImportRoutes(state: RouteState, action: RouteAction.ImportRoutes): RouteState {
        val updatedRoutes = state.routes + action.routes
        return state.copy(
            routes = updatedRoutes,
            error = null
        )
    }

    private fun handleExportMultipleRoutes(state: RouteState, action: RouteAction.ExportMultipleRoutes): RouteState {
        return state.copy(
            isExporting = true,
            exportProgress = 0.0f,
            error = null
        )
    }

    // Error Handling
    private fun handleSetError(state: RouteState, action: RouteAction.SetError): RouteState {
        return state.copy(
            error = action.error,
            lastErrorTime = System.currentTimeMillis()
        )
    }

    private fun handleClearRouteState(state: RouteState): RouteState {
        return RouteState()
    }

    // Helper Functions
    private fun createDefaultSafetyChecklistItems(category: SafetyCategory): List<SafetyChecklistItem> {
        return when (category) {
            SafetyCategory.AVIATION -> listOf(
                SafetyChecklistItem(
                    category = SafetyCategory.AVIATION,
                    title = "Wing Inspection",
                    description = "Check wing for damage, line condition, and airworthiness"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.AVIATION,
                    title = "Harness Check",
                    description = "Verify harness integrity and carabiner security"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.AVIATION,
                    title = "Reserve Parachute",
                    description = "Confirm reserve parachute is properly packed and current"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.AVIATION,
                    title = "Weather Assessment",
                    description = "Review current conditions and forecast"
                )
            )
            SafetyCategory.RESOURCES -> listOf(
                SafetyChecklistItem(
                    category = SafetyCategory.RESOURCES,
                    title = "Water Supply",
                    description = "Carry minimum 2L water for XC flights"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.RESOURCES,
                    title = "Food & Energy",
                    description = "Pack snacks and energy food for sustained flight"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.RESOURCES,
                    title = "Emergency Cash",
                    description = "Carry €50-100 emergency cash for retrieve"
                )
            )
            SafetyCategory.ELECTRONICS -> listOf(
                SafetyChecklistItem(
                    category = SafetyCategory.ELECTRONICS,
                    title = "Phone Battery",
                    description = "Ensure phone is fully charged with backup battery"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.ELECTRONICS,
                    title = "Radio Check",
                    description = "Test radio functionality and charge"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.ELECTRONICS,
                    title = "GPS Device",
                    description = "Verify GPS functionality and track logging"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.ELECTRONICS,
                    title = "Headlamp",
                    description = "Pack headlamp or flashlight for low light"
                )
            )
            SafetyCategory.EMERGENCY -> listOf(
                SafetyChecklistItem(
                    category = SafetyCategory.EMERGENCY,
                    title = "Emergency Contacts",
                    description = "Prepare list of emergency contacts"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.EMERGENCY,
                    title = "Flight Plan",
                    description = "Share flight plan with someone on ground"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.EMERGENCY,
                    title = "Retrieve Driver",
                    description = "Arrange retrieve driver for XC flights"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.EMERGENCY,
                    title = "Local Frequencies",
                    description = "Note local radio frequencies for area"
                ),
                SafetyChecklistItem(
                    category = SafetyCategory.EMERGENCY,
                    title = "Hospital Locations",
                    description = "Cache locations of nearby hospitals"
                )
            )
        }
    }
}