package com.ternparagliding.redux

import android.util.Log
import com.ternparagliding.model.Task
import com.ternparagliding.utils.cache.TaskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Persists tasks to [TaskCache] — the offline-first spatial task store —
 * whenever they change in Redux, so a pilot's plans survive app restarts and
 * can later be surfaced near their location (see TaskProximityOverlay).
 *
 * **Write-only by design.** A task is removed from the cache only on an
 * explicit `RemoveTask` / `ClearAllTasks` (handled in
 * [TaskPlanningMiddleware]) — never just because it left `state.tasks`.
 * Otherwise nearby preplanned tasks surfaced by proximity would be deleted
 * the moment the pilot panned away from them.
 *
 * **Why an observer, not a middleware.** [MapStore] runs middleware BEFORE
 * the reducer and exposes only the pre-batch state to it, so a middleware
 * can't see the post-edit task (it would persist the stale, pre-drag
 * position). Observing `state.tasks` always sees the final, reduced state.
 *
 * **Startup restore.** On launch this also hydrates *every* cached task, not
 * just the ones the [TaskProximityOverlay] later finds near the map centre —
 * so a pilot's plans are all present the moment the app opens, even if it opens
 * far from where they were planned.
 */
object TaskPersistence {
    private const val TAG = "TaskPersistence"

    /**
     * Hydrates all cached tasks on launch, then writes new/changed tasks to
     * [taskCache] on every change. Suspends forever — launch it from a
     * `LaunchedEffect`/coroutine that lives as long as the store.
     */
    suspend fun observe(store: MapStore, taskCache: TaskCache) {
        // Restore every persisted task up front. SurfaceNearbyTasks is the right
        // "load these" action: it merges (skips ids already present), re-binds each
        // to the waypoint library, and caps at MAX_TASKS (most-recent wins). It does
        // not select any task — restored plans render but nothing is force-focused.
        val restored = withContext(Dispatchers.IO) { taskCache.getAllCachedTasks() }
            .filter { it.waypoints.isNotEmpty() }
        if (restored.isNotEmpty()) {
            store.dispatch(MapAction.SurfaceNearbyTasks(restored))
            Log.d(TAG, "Restored ${restored.size} cached task(s) on launch")
        }

        // Seed the de-dup map with what we just restored so the first observed
        // emission doesn't immediately re-write unchanged tasks back to disk.
        var lastById = restored.associateBy { it.id }
        store.state
            .map { it.tasks }
            .distinctUntilChanged()
            .collect { tasks ->
                withContext(Dispatchers.IO) {
                    for (task in tasks) {
                        if (task.waypoints.isEmpty()) continue
                        val prev = lastById[task.id]
                        if (prev == null || prev != task) {
                            taskCache.cacheTask(task)
                            Log.d(TAG, "Persisted task ${task.id} (${task.waypoints.size} wp)")
                        }
                    }
                    lastById = tasks.associateBy { it.id }
                }
            }
    }
}
