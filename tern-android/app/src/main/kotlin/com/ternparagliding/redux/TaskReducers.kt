package com.ternparagliding.redux

import org.osmdroid.util.GeoPoint
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Spot
import com.ternparagliding.model.SpotSource

/**
 * Reducers for tasks, waypoints, interactive editing, task selection,
 * long-press map creation, and smart suggestions.
 *
 * Split out of MapReducers.kt (Phase 0c god-file split). The handlers
 * called by the `mapReducer` dispatcher are `internal` (same module) rather
 * than file-`private`; the helpers below them remain file-private.
 */

// Task Planning Constants
private const val DEFAULT_TASK_NAME_PREFIX = "Task"
private const val WAYPOINT_LABEL_PREFIX = "WP"
private const val WAYPOINT_LABEL_SEPARATOR = "-"
private const val FIRST_TASK_INDEX = 1

/**
 * Ground-distance snap radius. A drop within this many metres of an existing spot
 * (a library waypoint or a PG spot) reuses that spot instead of minting a near-duplicate
 * — when a pilot drops this close they almost always mean "that one". Real metres, so it's
 * zoom-independent (unlike the screen-px snap the add-from-map crosshair also does).
 * ~150 m ≈ 500 ft, comfortably inside one FAI cylinder.
 */
private const val SNAP_DISTANCE_M = 150.0

/**
 * Handle long press map action (Waypoint Creation / Smart Select)
 */
internal fun handleLongPressMap(state: MapState, action: MapAction.LongPressMap): MapState {
    // 0. Ground-distance snap: if the drop lands within SNAP_DISTANCE_M of an existing spot,
    //    reference that library waypoint (or capture that PG spot) instead of creating a
    //    near-duplicate. Runs even for a forced add-from-map drop — "Add point here" placed
    //    over a marker should pick it, not duplicate it. Zoom-independent (real metres).
    snapToNearbySpot(state, action.geoPoint)?.let { return it }

    // 1. Smart Select: Check for nearby waypoints (skipped when the drop is forced —
    //    add-from-map mode wants a new point even atop an existing one).
    val nearbyResult = if (action.forceCreate) null else findNearbyWaypoint(state.tasks, action.geoPoint, 0.05)
    if (nearbyResult != null) {
        val (task, waypoint) = nearbyResult
        return state.copy(
            selectedTaskId = task.id,
            selectedWaypoint = WaypointSelection(task.id, waypoint.id)
        )
    }

    // 2. Create New Waypoint/Task. Every ad-hoc drop auto-creates a USER spot in
    //    the library; the new task point then references it (spotId). Library and
    //    tasks update in one state.copy so a point never references a missing spot.
    if (state.tasks.isEmpty()) {
        // Create first task
        val label = action.label ?: "${WAYPOINT_LABEL_PREFIX}1$WAYPOINT_LABEL_SEPARATOR$FIRST_TASK_INDEX"
        val spot = makeUserSpot(action.geoPoint, label)
        val newTask = createFirstTask(action.geoPoint, 1, action.type, spot)
        return state.copy(
            tasks = state.tasks + newTask,
            waypointLibrary = state.waypointLibrary + spot,
            selectedTaskId = newTask.id,
            selectedWaypoint = WaypointSelection(newTask.id, newTask.waypoints.first().id, isNew = true)
        )
    } else {
        val selectedTaskId = state.selectedTaskId
        if (selectedTaskId != null) {
            // Add to selected task
            val selectedTask = state.tasks.find { it.id == selectedTaskId }
            if (selectedTask != null) {
                val taskIndex = state.tasks.indexOf(selectedTask) + 1
                val waypointNumber = selectedTask.waypoints.size + 1
                val label = action.label ?: "$WAYPOINT_LABEL_PREFIX$taskIndex$WAYPOINT_LABEL_SEPARATOR$waypointNumber"
                val spot = makeUserSpot(action.geoPoint, label)
                val (updatedTasks, newWaypointId) = addWaypointToTaskState(state.tasks, selectedTask, action.geoPoint, action.type, spot)
                return state.copy(
                    tasks = updatedTasks,
                    waypointLibrary = state.waypointLibrary + spot,
                    selectedWaypoint = WaypointSelection(selectedTaskId, newWaypointId, isNew = true),
                    isTaskPanelExpanded = false // Strategic Auto-Minimize
                )
            }
        }

        // No task selected or selected task not found -> Create new task
        val newTaskIndex = state.tasks.size + 1
        val label = action.label ?: "$WAYPOINT_LABEL_PREFIX$newTaskIndex$WAYPOINT_LABEL_SEPARATOR$FIRST_TASK_INDEX"
        val spot = makeUserSpot(action.geoPoint, label)
        val newTask = createFirstTask(action.geoPoint, newTaskIndex, action.type, spot)
        return state.copy(
            tasks = state.tasks + newTask,
            waypointLibrary = state.waypointLibrary + spot,
            selectedTaskId = newTask.id,
            selectedWaypoint = WaypointSelection(newTask.id, newTask.waypoints.first().id, isNew = true),
            isTaskPanelExpanded = false // Strategic Auto-Minimize
        )
    }
}

/** Build a USER spot for an ad-hoc map drop. The pilot can rename it later in the
 *  library; the edit then flows to every task referencing it. */
private fun makeUserSpot(geoPoint: GeoPoint, code: String): Spot =
    Spot(
        id = java.util.UUID.randomUUID().toString(),
        code = code,
        name = null,
        lat = geoPoint.latitude,
        lon = geoPoint.longitude,
        source = SpotSource.USER,
    )

/**
 * Helper: Add waypoint to a task and return updated tasks list + new waypoint ID
 */
private fun addWaypointToTaskState(
    tasks: List<com.ternparagliding.model.Task>,
    targetTask: com.ternparagliding.model.Task,
    geoPoint: GeoPoint,
    type: LocationType = LocationType.TURNPOINT,
    spot: Spot,
): Pair<List<com.ternparagliding.model.Task>, String> {
    val newWaypointId = java.util.UUID.randomUUID().toString()

    val updatedTasks = tasks.map { task ->
        if (task.id == targetTask.id) {
            task.addWaypoint(
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = type,
                label = spot.code,
                id = newWaypointId,
                spotId = spot.id,
            )
        } else task
    }
    return Pair(updatedTasks, newWaypointId)
}

/**
 * Helper: Create the first task with a single waypoint referencing [spot].
 */
private fun createFirstTask(
    geoPoint: GeoPoint,
    taskIndex: Int,
    type: LocationType = LocationType.TURNPOINT,
    spot: Spot,
): com.ternparagliding.model.Task {
    val taskName = "$DEFAULT_TASK_NAME_PREFIX $taskIndex"

    return com.ternparagliding.model.Task(
        name = taskName,
        waypoints = listOf(
            com.ternparagliding.model.Waypoint(
                lat = geoPoint.latitude,
                lon = geoPoint.longitude,
                type = type,
                label = spot.code,
                spotId = spot.id,
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
 * If [geoPoint] lands within [SNAP_DISTANCE_M] of an existing spot — a library waypoint or a
 * (possibly uncaptured) PG spot — return a state that references/captures the nearest one into
 * the selected task (or a new task if none is selected). Returns null when nothing is close
 * enough, so the caller proceeds to mint a fresh USER spot.
 */
private fun snapToNearbySpot(state: MapState, geoPoint: GeoPoint): MapState? {
    val spot = nearestSnapSpot(state, geoPoint) ?: return null
    // The PG branch may mint a fresh PG_SPOT spot; library hits are already in the library.
    val library = if (state.waypointLibrary.any { it.id == spot.id }) state.waypointLibrary
                  else state.waypointLibrary + spot

    val selectedTask = state.selectedTaskId?.let { id -> state.tasks.find { it.id == id } }
    return if (selectedTask != null) {
        val newWaypointId = java.util.UUID.randomUUID().toString()
        val updatedTasks = state.tasks.map { task ->
            if (task.id != selectedTask.id) task
            else task.addWaypoint(
                lat = spot.lat, lon = spot.lon, type = LocationType.TURNPOINT,
                label = spot.code, id = newWaypointId, description = spot.name,
                alt = spot.alt, spotId = spot.id,
            )
        }
        state.copy(
            tasks = updatedTasks,
            waypointLibrary = library,
            selectedWaypoint = WaypointSelection(selectedTask.id, newWaypointId),
            isTaskPanelExpanded = false, // Strategic Auto-Minimize
        )
    } else {
        // No task in focus → start one whose first point references the existing spot.
        val taskName = "$DEFAULT_TASK_NAME_PREFIX ${state.tasks.size + 1}"
        val newTask = com.ternparagliding.model.Task(
            name = taskName,
            waypoints = listOf(
                com.ternparagliding.model.Waypoint(
                    lat = spot.lat, lon = spot.lon, type = LocationType.TURNPOINT,
                    label = spot.code, description = spot.name, alt = spot.alt, spotId = spot.id,
                )
            ),
        )
        state.copy(
            tasks = state.tasks + newTask,
            waypointLibrary = library,
            selectedTaskId = newTask.id,
            selectedWaypoint = WaypointSelection(newTask.id, newTask.waypoints.first().id),
            isTaskPanelExpanded = false, // Strategic Auto-Minimize
        )
    }
}

/**
 * Nearest existing spot within [SNAP_DISTANCE_M] of [geoPoint], or null. Considers library
 * spots (returned as-is) and PG spots (find-or-create the PG_SPOT spot by provenance so the
 * caller can reference it). Whichever is closest in real metres wins.
 */
private fun nearestSnapSpot(state: MapState, geoPoint: GeoPoint): Spot? {
    val hits = mutableListOf<Pair<Spot, Double>>()
    state.waypointLibrary.forEach { sp ->
        val d = GeoPoint(sp.lat, sp.lon).distanceToAsDouble(geoPoint)
        if (d <= SNAP_DISTANCE_M) hits += sp to d
    }
    com.ternparagliding.overlay.pgspot.pgSpotPoints(state.pgSpotGeoJson).forEach { pg ->
        val d = GeoPoint(pg.lat, pg.lon).distanceToAsDouble(geoPoint)
        if (d > SNAP_DISTANCE_M) return@forEach
        // A captured PG spot is already a PG_SPOT library spot (matched above); reuse it by
        // provenance so we never create a second copy.
        val existing = state.waypointLibrary.find {
            it.source == SpotSource.PG_SPOT && it.sourceId == pg.id
        }
        val spot = existing ?: Spot(
            id = java.util.UUID.randomUUID().toString(),
            code = pg.name, name = pg.name, lat = pg.lat, lon = pg.lon, alt = pg.alt,
            source = SpotSource.PG_SPOT, sourceId = pg.id,
        )
        hits += spot to d
    }
    return hits.minByOrNull { it.second }?.first
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
        val limitedTasks = if (newTasks.size > TaskConstants.MAX_TASKS) {
            newTasks.sortedByDescending { it.createdAt }.take(TaskConstants.MAX_TASKS)
        } else newTasks

        val updatedSelection = updateSelectionAfterTaskChange(state.selectedWaypoint, limitedTasks, action.task.id)
        state.copy(tasks = limitedTasks, selectedWaypoint = updatedSelection)
    }
    is MapAction.AddImportedTask -> {
        // Seed the library with IMPORTED spots derived from the task's points (so a
        // task loaded on its own still references spots), merging by code with any
        // separately-imported waypoint set, then bind the points to those spots.
        val derived = com.ternparagliding.overlay.task.TaskResolver.spotsFromTask(action.task)
        val incoming = derived.associateBy { it.id }
        val mergedLibrary = state.waypointLibrary.map { incoming[it.id] ?: it } +
            derived.filter { d -> state.waypointLibrary.none { it.id == d.id } }
        val bound = com.ternparagliding.overlay.task.TaskResolver.bindToLibrary(action.task, mergedLibrary)
        val newTasks = state.tasks + bound
        val limitedTasks = if (newTasks.size > TaskConstants.MAX_TASKS) {
            newTasks.sortedByDescending { it.createdAt }.take(TaskConstants.MAX_TASKS)
        } else newTasks
        val updatedSelection = updateSelectionAfterTaskChange(state.selectedWaypoint, limitedTasks, bound.id)
        state.copy(tasks = limitedTasks, waypointLibrary = mergedLibrary, selectedWaypoint = updatedSelection)
    }
    is MapAction.RemoveTask -> {
        val newTasks = state.tasks.filter { it.id != action.taskId }
        val updatedSelection = state.selectedWaypoint?.takeIf { it.taskId != action.taskId }
        val updatedSelectedTaskId = state.selectedTaskId?.takeIf { it != action.taskId }
        // Don't leave in-flight nav pointing at a deleted task's waypoints.
        val removedWpIds = state.tasks.find { it.id == action.taskId }?.waypoints?.map { it.id }?.toSet() ?: emptySet()
        state.copy(
            tasks = newTasks,
            selectedWaypoint = updatedSelection,
            selectedTaskId = updatedSelectedTaskId,
            activeWaypointId = state.activeWaypointId?.takeIf { it !in removedWpIds },
            taggedWaypointIds = state.taggedWaypointIds - removedWpIds,
        )
    }
    is MapAction.UpdateTask -> {
        val newTasks = state.tasks.map { if (it.id == action.task.id) action.task else it }
        val updatedSelection = updateSelectionAfterTaskChange(state.selectedWaypoint, newTasks, action.task.id)
        state.copy(tasks = newTasks, selectedWaypoint = updatedSelection)
    }
    is MapAction.ClearAllTasks -> state.copy(
        tasks = emptyList(), selectedTaskId = null, selectedWaypoint = null,
        activeWaypointId = null, taggedWaypointIds = emptySet(),
    )
    // Merge nearby preplanned tasks (from the spatial TaskCache) into the
    // active set, skipping any already present. Existing/edited tasks win;
    // selection is untouched. Capped at MAX_TASKS like AddTask.
    is MapAction.SurfaceNearbyTasks -> {
        val existingIds = state.tasks.map { it.id }.toSet()
        // Re-bind surfaced tasks to the library by code: recovers spot references
        // for legacy (v0-cached) tasks whose spotId predates persistence.
        val toAdd = action.tasks
            .filter { it.id !in existingIds }
            .map { com.ternparagliding.overlay.task.TaskResolver.bindToLibrary(it, state.waypointLibrary) }
        if (toAdd.isEmpty()) state
        else {
            val merged = state.tasks + toAdd
            val limited = if (merged.size > TaskConstants.MAX_TASKS) {
                merged.sortedByDescending { it.createdAt }.take(TaskConstants.MAX_TASKS)
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
        val target = state.tasks.find { it.id == action.taskId }
        if (target == null) state
        else {
            // Auto-create a USER spot for this ad-hoc point and reference it.
            val taskIndex = state.tasks.indexOf(target) + 1
            val waypointNumber = target.waypoints.size + 1
            val label = action.label ?: "$WAYPOINT_LABEL_PREFIX$taskIndex$WAYPOINT_LABEL_SEPARATOR$waypointNumber"
            val spot = makeUserSpot(GeoPoint(action.lat, action.lon), label)
            val newTasks = state.tasks.map { task ->
                if (task.id == action.taskId) {
                    task.addWaypoint(action.lat, action.lon, action.type, spot.code, action.id, spotId = spot.id)
                } else task
            }
            state.copy(tasks = newTasks, waypointLibrary = state.waypointLibrary + spot)
        }
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
                    spotId = wp.id,
                )
            }
        }
        state.copy(tasks = newTasks)
    }
    is MapAction.AddPgSpotToTask -> {
        val target = state.tasks.find { it.id == action.taskId }
        if (target == null) state
        else {
            // Find-or-create a PG_SPOT spot (by provenance), then reference it.
            val existing = state.waypointLibrary.find {
                it.source == SpotSource.PG_SPOT && it.sourceId == action.pgSpotId
            }
            val spot = existing ?: Spot(
                id = java.util.UUID.randomUUID().toString(),
                code = action.code,
                name = action.name,
                lat = action.lat,
                lon = action.lon,
                alt = action.alt,
                source = SpotSource.PG_SPOT,
                sourceId = action.pgSpotId,
            )
            val newLibrary = if (existing != null) state.waypointLibrary else state.waypointLibrary + spot
            val newTasks = state.tasks.map { task ->
                if (task.id != action.taskId) task
                else task.addWaypoint(
                    lat = spot.lat, lon = spot.lon, type = LocationType.TURNPOINT,
                    label = spot.code, description = spot.name, alt = spot.alt, spotId = spot.id,
                )
            }
            state.copy(tasks = newTasks, waypointLibrary = newLibrary)
        }
    }
    is MapAction.RemoveWaypoint -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) task.removeWaypoint(action.waypointId) else task
        }
        val updatedSelection = state.selectedWaypoint?.takeIf {
            !(it.taskId == action.taskId && it.waypointId == action.waypointId)
        }
        // Clear in-flight nav if it pointed at the removed point.
        state.copy(
            tasks = newTasks,
            selectedWaypoint = updatedSelection,
            activeWaypointId = state.activeWaypointId?.takeIf { it != action.waypointId },
            taggedWaypointIds = state.taggedWaypointIds - action.waypointId,
        )
    }
    // Position is identity → edit the spot (flows to every task using it);
    // role is a per-task feature → edit the point.
    is MapAction.UpdateWaypoint -> {
        var s = state
        if (action.lat != null && action.lon != null) {
            s = editPointIdentity(s, action.taskId, action.waypointId,
                editSpot = { it.copy(lat = action.lat, lon = action.lon) },
                editSnapshot = { it.updateWaypoint(action.waypointId, action.lat, action.lon) })
        }
        if (action.type != null) {
            s = s.copy(tasks = s.tasks.map { if (it.id == action.taskId) it.setPointRole(action.waypointId, action.type) else it })
        }
        s
    }
    is MapAction.UpdateWaypointType -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) task.setPointRole(action.waypointId, action.type) else task
        }
        state.copy(tasks = newTasks)
    }
    // Name is identity → edit the spot. Blank clears the spot's name (falls back to code).
    is MapAction.UpdateWaypointDescription -> editPointIdentity(state, action.taskId, action.waypointId,
        editSpot = { it.copy(name = action.description?.takeIf { d -> d.isNotBlank() }) },
        editSnapshot = { it.updateWaypoint(action.waypointId, description = action.description ?: "") })
    is MapAction.UpdateWaypointRadius -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) task.setPointRadius(action.waypointId, action.radius) else task
        }
        state.copy(tasks = newTasks)
    }
    // Altitude is identity → edit the spot.
    is MapAction.UpdateWaypointAltitude -> editPointIdentity(state, action.taskId, action.waypointId,
        editSpot = { it.copy(alt = action.alt) },
        editSnapshot = { it.updateWaypoint(action.waypointId, alt = action.alt) })
    is MapAction.UpdateWaypointTimeGates -> {
        val newTasks = state.tasks.map { task ->
            if (task.id == action.taskId) task.setPointGates(action.waypointId, action.openTime, action.closeTime) else task
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
        // Capture the original from the spot (the position authority) when linked.
        val spot = waypoint?.spotId?.let { id -> state.waypointLibrary.find { it.id == id } }

        state.copy(selectedWaypoint = WaypointSelection(
            taskId = action.taskId,
            waypointId = action.waypointId,
            isDragging = true,
            originalLat = spot?.lat ?: waypoint?.lat,
            originalLon = spot?.lon ?: waypoint?.lon
        ))
    }
    is MapAction.UpdateWaypointDrag -> {
        val sel = state.selectedWaypoint
        // Dragging moves the spot, so the change shows (and flows to every task
        // using it) instead of being painted over by the resolver.
        if (sel?.isDragging == true) {
            editPointIdentity(state, sel.taskId, sel.waypointId,
                editSpot = { it.copy(lat = action.lat, lon = action.lon) },
                editSnapshot = { it.updateWaypoint(sel.waypointId, action.lat, action.lon) })
        } else state
    }
    MapAction.EndWaypointDrag -> {
        val currentSelection = state.selectedWaypoint
        if (currentSelection?.isDragging == true) {
            state.copy(selectedWaypoint = currentSelection.copy(isDragging = false))
        } else state
    }
    MapAction.CancelWaypointDrag -> {
        val sel = state.selectedWaypoint
        if (sel?.isDragging == true && sel.originalLat != null && sel.originalLon != null) {
            // Restore the spot to its pre-drag position.
            val restored = editPointIdentity(state, sel.taskId, sel.waypointId,
                editSpot = { it.copy(lat = sel.originalLat, lon = sel.originalLon) },
                editSnapshot = { it.updateWaypoint(sel.waypointId, sel.originalLat, sel.originalLon) })
            restored.copy(selectedWaypoint = sel.copy(isDragging = false))
        } else state
    }
    else -> state
}

/**
 * Identity edits (position/name/alt) target the [Spot] the point references, so the
 * change flows to every task using it (and isn't painted over by the resolver). A
 * legacy/ad-hoc point without a resolvable spot edits its own snapshot instead.
 */
private fun editPointIdentity(
    state: MapState,
    taskId: String,
    waypointId: String,
    editSpot: (Spot) -> Spot,
    editSnapshot: (com.ternparagliding.model.Task) -> com.ternparagliding.model.Task,
): MapState {
    val wp = state.tasks.find { it.id == taskId }?.waypoints?.find { it.id == waypointId }
    val spotId = wp?.spotId
    return if (spotId != null && state.waypointLibrary.any { it.id == spotId }) {
        state.copy(waypointLibrary = state.waypointLibrary.map { if (it.id == spotId) editSpot(it) else it })
    } else {
        state.copy(tasks = state.tasks.map { if (it.id == taskId) editSnapshot(it) else it })
    }
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
