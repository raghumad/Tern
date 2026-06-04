package com.ternparagliding.redux

import android.util.Log
import com.ternparagliding.model.Route
import com.ternparagliding.utils.cache.RouteCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Persists routes to [RouteCache] — the offline-first spatial route store —
 * whenever they change in Redux, so a pilot's plans survive app restarts and
 * can later be surfaced near their location (see RouteProximityOverlay).
 *
 * **Write-only by design.** A route is removed from the cache only on an
 * explicit `RemoveRoute` / `ClearAllRoutes` (handled in
 * [RoutePlanningMiddleware]) — never just because it left `state.routes`.
 * Otherwise nearby preplanned routes surfaced by proximity would be deleted
 * the moment the pilot panned away from them.
 *
 * **Why an observer, not a middleware.** [MapStore] runs middleware BEFORE
 * the reducer and exposes only the pre-batch state to it, so a middleware
 * can't see the post-edit route (it would persist the stale, pre-drag
 * position). Observing `state.routes` always sees the final, reduced state.
 */
object RoutePersistence {
    private const val TAG = "RoutePersistence"

    /**
     * Collects route changes and writes new/changed routes to [routeCache].
     * Suspends forever — launch it from a `LaunchedEffect`/coroutine that
     * lives as long as the store.
     */
    suspend fun observe(store: MapStore, routeCache: RouteCache) {
        var lastById = emptyMap<String, Route>()
        store.state
            .map { it.routes }
            .distinctUntilChanged()
            .collect { routes ->
                withContext(Dispatchers.IO) {
                    for (route in routes) {
                        if (route.waypoints.isEmpty()) continue
                        val prev = lastById[route.id]
                        if (prev == null || prev != route) {
                            routeCache.cacheRoute(route)
                            Log.d(TAG, "Persisted route ${route.id} (${route.waypoints.size} wp)")
                        }
                    }
                    lastById = routes.associateBy { it.id }
                }
            }
    }
}
