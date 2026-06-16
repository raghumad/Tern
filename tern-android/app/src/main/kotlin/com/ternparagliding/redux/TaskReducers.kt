package com.ternparagliding.redux

import org.osmdroid.util.GeoPoint
import com.ternparagliding.model.LocationType

/**
 * Reducers for tasks, waypoints, interactive editing, task selection,
 * long-press map creation, and smart suggestions.
 *
 * Split out of MapReducers.kt (Phase 0c god-file split). The handlers
 * called by the `mapReducer` dispatcher are `internal` (same module) rather
 * than file-`private`; the helpers below them remain file-private.
 */

// Task Planning Constants
private const val DEFAULT_ROUTE_NAME_PREFIX = "Task"
private const val WAYPOINT_LABEL_PREFIX = "WP"
private const val WAYPOINT_LABEL_SEPARATOR = "-"
private const val FIRST_ROUTE_INDEX = 1

/**
 * Handle long press map action (Waypoint Creation / Smart Select)
 */
internal fun handleLongPressMap(state: MapState, action: MapAction.LongPressMap): MapState {
    // 1. Smart Select: Check for nearby waypoints
    val nearbyResult = findNearbyWaypoint(state.tasks, action.geoPoint, 0.05)
    if (nearbyResult != null) {
        val (task, waypoint) = nearbyResult
        return state.copy(
            selectedTaskId = task.id,
            selectedWaypoint = WaypointSelection(task.id, waypoint.id)
        )
    }

    // 2. Create New Waypoint/Task
    if (state.tasks.isEmpty()) {
        // Create first task
        val newTask = createFirstTask(action.geoPoint, 1, action.type, action.label)
        return state.copy(
            tasks = state.tasks + newTask,
            selectedTaskId = newTask.id,
            selectedWaypoint = WaypointSelection(newTask.id, newTask.waypoints.first().id)
        )
    } else {
        val selectedTaskId = state.selectedTaskId
        if (selectedTaskId != null) {
            // Add to selected task
            val selectedTask = state.tasks.find { it.id == selectedTaskId }
            if (selectedTask != null) {
                val (updatedTasks, newWaypointId) = addWaypointToTaskState(state.tasks, selectedTask, action.geoPoint, action.type, action.label)
                return state.copy(
                    tasks = updatedTasks,
                    selectedWaypoint = WaypointSelection(selectedTaskId, newWaypointId),
                    isTaskPanelExpanded = false // Strategic Auto-Minimize
                )
            }
        }

        // No task selected or selected task not found -> Create new task
        val newTaskIndex = state.tasks.size + 1
        val newTask = createFirstTask(action.geoPoint, newTaskIndex, action.type, action.label)
        return state.copy(
            tasks = state.tasks + newTask,
            selectedTaskId = newTask.id,
            selectedWaypoint = WaypointSelection(newTask.id, newTask.waypoints.first().id),
            isTaskPanelExpanded = false // Strategic Auto-Minimize
        )
    }
}

/**
 * Helper: Add waypoint to a task and return updated tasks list + new waypoint ID
 */
private fun addWaypointToTaskState(
    tasks: List<com.ternparagliding.model.Task>,
    targetTask: com.ternparagliding.model.Task,
    geoPoint: GeoPoint,
    type: LocationType = LocationType.TURNPOINT,
    label: String? = null
): Pair<List<com.ternparagliding.model.Task>, String> {
    val waypointNumber = targetTask.waypoints.size + 1
    val taskIndex = tasks.indexOf(targetTask) + 1
    val finalLabel = label ?: "$WAYPOINT_LABEL_PREFIX$taskIndex$WAYPOINT_LABEL_SEPARATOR$waypointNumber"
    val newWaypointId = java.util.UUID.randomUUID().toString()

    val updatedTasks = tasks.map { task ->
        if (task.id == targetTask.id) {
            task.addWaypoint(
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = type,
                label = finalLabel,
                id = newWaypointId
            )
        } else task
    }
    return Pair(updatedTasks, newWaypointId)
}

/**
 * Helper: Create the first task with a single waypoint
 */
private fun createFirstTask(
    geoPoint: GeoPoint,
    taskIndex: Int,
    type: LocationType = LocationType.TURNPOINT,
    label: String? = null
): com.ternparagliding.model.Task {
    val waypointLabel = label ?: "$WAYPOINT_LABEL_PREFIX$taskIndex$WAYPOINT_LABEL_SEPARATOR$FIRST_ROUTE_INDEX"
    val taskName = "$DEFAULT_ROUTE_NAME_PREFIX $taskIndex"

    return com.ternparagliding.model.Task(
        name = taskName,
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
    tasks: List<com.ternparagliding.model.Task>,
    geoPoint: GeoPoint,
    toleranceDegrees: Double
): Pair<com.ternparagliding.model.Task, com.ternparagliding.model.Waypoint>? {
    val targetHilbertIndex = com.ternparagliding.utils.cache.MapOverlayCacheUtils.computeHilbertIndex(geoPoint, 16)

    tasks.forEach { task ->
        task.waypoints.forEach { waypoint ->
            val dLat = waypoint.lat - geoPoint.latitude
            val dLon = waypoint.lon - geoPoint.longitude
            val distanceDegrees = kotlin.math.sqrt(dLat * dLat + dLon * dLon)

            if (distanceDegrees <= toleranceDegrees) {
                return Pair(task, waypoint)
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
 * Handle task management actions
 */
internal fun handleTaskActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.AddTask -> {
        val newTasks = state.tasks + action.task
        val limitedTasks = if (newTasks.size > TaskConstants.MAX_ROUTES) {
            newTasks.sortedByDescending { it.createdAt }.take(TaskConstants.MAX_ROUTES)
        } else newTasks

        val updatedSelection = updateSelectionAfterTaskChange(state.selectedWaypoint, limitedTasks, action.task.id)
        state.copy(tasks = limitedTasks, selectedWaypoint = updatedSelection)
    }
    is MapAction.RemoveTask -> {
        val newTasks = state.tasks.filter { it.id != action.taskId }
        val updatedSelection = state.selectedWaypoint?.takeIf { it.taskId != action.taskId }
        val updatedSelectedTaskId = state.selectedTaskId?.takeIf { it != action.taskId }
        state.copy(tasks = newTasks, selectedWaypoint = updatedSelection, selectedTaskId = updatedSelectedTaskId)
    }
    is MapAction.UpdateTask -> {
        val newTasks = state.tasks.map { if (it.id == action.task.id) action.task else it }
        val updatedSelection = updateSelectionAfterTaskChange(state.selectedWaypoint, newTasks, action.task.id)
        state.copy(tasks = newTasks, selectedWaypoint = updatedSelection)
    }
    is MapAction.ClearAllTasks -> state.copy(tasks = emptyList(), selectedTaskId = null, selectedWaypoint = null)
    // Merge nearby preplanned tasks (from the spatial TaskCache) into the
    // active set, skipping any already present. Existing/edited tasks win;
    // selection is untouched. Capped at MAX_ROUTES like AddTask.
    is MapAction.SurfaceNearbyTasks -> {
        val existingIds = state.tasks.map { it.id }.toSet()
        val toAdd = action.tasks.filter { it.id !in existingIds }
        if (toAdd.isEmpty()) state
        else {
            val merged = state.tasks + toAdd
            val limited = if (merged.size > TaskConstants.MAX_ROUTES) {
                merged.sortedByDescending { it.createdAt }.take(TaskConstants.MAX_ROUTES)
            } else merged
            state.copy(tasks = limited)
        }
    }
    else -> state
}

/**
 * Handle waypoint management actions
 */
internal fun handleWaypointActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.AddWaypointToTask -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) {
                task.addWaypoint(action.lat, action.lon, action.type, action.label, action.id)
            } else task
        }
        state.copy(tasks = newTasks)
    }
    is MapAction.AddLibraryWaypointsToTask -> {
        // Append the chosen library waypoints (in pick order), stamping the
        // libraryWaypointId link + carrying the code/name/alt across. Default role
        // TURNPOINT; the pilot sets roles/cylinders/gates in the per-point editor.
        val byId = state.waypointLibrary.associateBy { it.id }
        val picked = action.waypointIds.mapNotNull { byId[it] }
        val newTasks = state.tasks.map { task ->
            if (task.id != action.taskId) task
            else picked.fold(task) { acc, wp ->
                acc.addWaypoint(
                    lat = wp.lat,
                    lon = wp.lon,
                    type = LocationType.TURNPOINT,
                    label = wp.code,
                    description = wp.name,
                    alt = wp.alt,
                    libraryWaypointId = wp.id,
                )
            }
        }
        state.copy(tasks = newTasks)
    }
    is MapAction.RemoveWaypoint -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) task.removeWaypoint(action.waypointId) else task
        }
        val updatedSelection = state.selectedWaypoint?.takeIf {
            !(it.taskId == action.taskId && it.waypointId == action.waypointId)
        }
        state.copy(tasks = newTasks, selectedWaypoint = updatedSelection)
    }
    is MapAction.UpdateWaypoint -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) {
                task.updateWaypoint(action.waypointId, action.lat, action.lon, action.type)
            } else task
        }
        state.copy(tasks = newTasks)
    }
    is MapAction.UpdateWaypointType -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) {
                task.updateWaypoint(action.waypointId, null, null, action.type)
            } else task
        }
        state.copy(tasks = newTasks)
    }
    is MapAction.UpdateWaypointDescription -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) {
                task.updateWaypoint(action.waypointId, description = action.description ?: "")
            } else task
        }
        state.copy(tasks = newTasks)
    }
    is MapAction.UpdateWaypointRadius -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) {
                task.updateWaypoint(action.waypointId, radius = action.radius)
            } else task
        }
        state.copy(tasks = newTasks)
    }
    is MapAction.UpdateWaypointAltitude -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) {
                task.updateWaypoint(action.waypointId, alt = action.alt)
            } else task
        }
        state.copy(tasks = newTasks)
    }
    is MapAction.UpdateWaypointTimeGates -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) {
                task.updateWaypoint(action.waypointId, openTime = action.openTime, closeTime = action.closeTime)
            } else task
        }
        state.copy(tasks = newTasks)
    }
    is MapAction.ReorderWaypoint -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) {
                task.reorderWaypoint(action.fromIndex, action.toIndex)
            } else task
        }
        state.copy(tasks = newTasks)
    }
    else -> state
}

/**
 * Handle interactive editing actions
 */
internal fun handleInteractiveEditingActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SelectWaypoint -> state.copy(selectedWaypoint = WaypointSelection(
        taskId = action.taskId,
        waypointId = action.waypointId,
        isDragging = false
    ))
    MapAction.DeselectWaypoint -> state.copy(selectedWaypoint = null)
    is MapAction.StartWaypointDrag -> {
        val task = state.tasks.find { it.id == action.taskId }
        val waypoint = task?.waypoints?.find { it.id == action.waypointId }

        state.copy(selectedWaypoint = WaypointSelection(
            taskId = action.taskId,
            waypointId = action.waypointId,
            isDragging = true,
            originalLat = waypoint?.lat,
            originalLon = waypoint?.lon
        ))
    }
    is MapAction.UpdateWaypointDrag -> {
        val currentSelection = state.selectedWaypoint
        if (currentSelection?.isDragging == true) {
            val newTasks = state.tasks.map { task ->
                if (task.id == currentSelection.taskId) {
                    task.updateWaypoint(currentSelection.waypointId, action.lat, action.lon)
                } else task
            }
            state.copy(tasks = newTasks)
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
            val newTasks = state.tasks.map { task ->
                if (task.id == currentSelection.taskId) {
                    task.updateWaypoint(currentSelection.waypointId, currentSelection.originalLat, currentSelection.originalLon)
                } else task
            }
            state.copy(
                tasks = newTasks,
                selectedWaypoint = currentSelection.copy(isDragging = false)
            )
        } else state
    }
    else -> state
}

/**
 * Handle task selection actions
 */
internal fun handleTaskSelectionActions(state: MapState, action: MapAction): MapState = when (action) {
    is MapAction.SelectTask -> {
        // [RFC 005] Strategic Auto-Minimize: If zooming out to strategic level (< 11.0),
        // collapse the panel by default to show the whole task.
        val shouldExpand = state.zoom >= 11.0
        state.copy(
            selectedTaskId = action.taskId,
            selectedWaypoint = if (action.taskId == null) null else state.selectedWaypoint,
            isTaskPanelExpanded = if (action.taskId != null) shouldExpand else state.isTaskPanelExpanded
        )
    }
    MapAction.DeselectTask -> state.copy(selectedTaskId = null, selectedWaypoint = null)
    else -> state
}

/**
 * Update waypoint selection after task changes
 */
private fun updateSelectionAfterTaskChange(
    currentSelection: WaypointSelection?,
    tasks: List<com.ternparagliding.model.Task>,
    changedTaskId: String
): WaypointSelection? {
    if (currentSelection == null) return null

    val task = tasks.find { it.id == changedTaskId } ?: return null
    val waypointExists = task.waypoints.any { it.id == currentSelection.waypointId }

    return if (waypointExists) currentSelection else null
}
