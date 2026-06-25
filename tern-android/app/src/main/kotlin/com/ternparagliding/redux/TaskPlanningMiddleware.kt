package com.ternparagliding.redux

import android.content.Context
import android.util.Log
import com.ternparagliding.utils.geo.ThermalHotspotService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import com.ternparagliding.utils.geo.SpatialSafetyUtils
import com.ternparagliding.utils.cache.CacheManager
import com.ternparagliding.utils.geo.CountryUtils

/**
 * Middleware handling side-effects for Task Planning.
 * Port of iOS TaskPlannerModel logic for Android Redux.
 * Handles:
 * - Automatic Thermal Hotspot fetching around launch/waypoints.
 * - [PLANNED] Automatic pre-caching of task corridor (Hilbert tiles).
 * - [PLANNED] Airspace analytics triggers.
 */
class TaskPlanningMiddleware(
    private val context: Context,
    private val thermalService: ThermalHotspotService = ThermalHotspotService(context),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
) : Middleware {

    // Spots whose elevation is being fetched / has been resolved — so the backfill never
    // double-fetches or loops on a genuine sea-level (0 m) reading.
    private val elevInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val elevResolved = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Offline corridor tiles: one debounced, dedup'd download per task. MapLibre's
    // OfflineManager callbacks land on the main thread, so the cache job runs on Main
    // (mirroring CacheTilesButton). cachedCorridorSig remembers the geometry we've handled
    // this session; tileJobs lets a burst of edits coalesce into a single download.
    private val tileCacher by lazy { com.ternparagliding.offline.TaskTileCacher(context) }
    private val tileScope = CoroutineScope(Dispatchers.Main + Job())
    private val tileJobs = java.util.Collections.synchronizedMap(mutableMapOf<String, Job>())
    private val cachedCorridorSig = java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())

    override suspend fun process(action: TernAction, store: MapStore) {
        val state = store.state.value
        when (action) {
            is MapAction.SelectTask -> {
                zoomToTask(state, action.taskId, store)
                fetchThermalHotspotsForTask(state, action.taskId)
                triggerTaskCorridorSync(state, action.taskId, store)
                store.dispatch(WeatherActions.FetchWeatherForTask(action.taskId))
                backfillElevations(store)
            }
            // Ad-hoc map drops + spot moves create/relocate USER spots that lack a real
            // ground elevation; backfill it so the soaring sounding can run.
            is MapAction.LongPressMap -> backfillElevations(store)
            is MapAction.CommitSpotMove -> backfillElevations(store)
            is MapAction.ZoomToTask -> {
                zoomToTask(state, action.taskId, store)
            }
            is MapAction.AddTask -> {
                fetchThermalHotspotsForTask(state, action.task.id)
                triggerTaskCorridorSync(state, action.task.id, store)
                store.dispatch(WeatherActions.FetchWeatherForTask(action.task.id))
                // [RFC 005] Strategic Auto-Minimize: Collapse panel on new task creation
                store.dispatch(MapAction.SetTaskPanelExpanded(false))
            }
            is MapAction.AddWaypointToTask -> {
                fetchThermalHotspotsForPoint(GeoPoint(action.lat, action.lon))
                triggerTaskCorridorSync(state, action.taskId, store)
                store.dispatch(WeatherActions.FetchWeatherForTask(action.taskId))
                backfillElevations(store)
                // [RFC 005] Strategic Auto-Minimize: If adding points, ensure panel is minimized
                // to show the evolving trajectory on the map.
                store.dispatch(MapAction.SetTaskPanelExpanded(false))
            }
            is MapAction.UpdateWaypoint -> {
                if (action.lat != null && action.lon != null) {
                    fetchThermalHotspotsForPoint(GeoPoint(action.lat, action.lon))
                    triggerTaskCorridorSync(state, action.taskId, store)
                    store.dispatch(WeatherActions.FetchWeatherForTask(action.taskId))
                }
            }
            // Explicit deletion clears the persisted task(s). Task *writes*
            // are handled by TaskPersistence (an observer), not here — a
            // middleware can't see post-edit state. But deletes carry their
            // id in the action, so they're safe to handle inline.
            is MapAction.RemoveTask -> {
                coroutineScope.launch {
                    CacheManager.taskCache.clearCacheForTask(action.taskId)
                }
            }
            is MapAction.ClearAllTasks -> {
                coroutineScope.launch {
                    CacheManager.taskCache.clearCache()
                }
            }
        }
    }

    private fun fetchThermalHotspotsForTask(state: MapState, taskId: String) {
        val task = state.tasks.find { it.id == taskId } ?: return
        val firstWaypoint = task.waypoints.firstOrNull() ?: return
        
        Log.i("TaskPlanningMiddleware", "Task context detected. Pre-fetching thermal hotspots around launch.")
        fetchThermalHotspotsForPoint(GeoPoint(firstWaypoint.lat, firstWaypoint.lon))
    }

    private fun fetchThermalHotspotsForPoint(point: GeoPoint) {
        coroutineScope.launch {
            try {
                val hotspots = thermalService.getHotspots(point)
                if (hotspots.isNotEmpty()) {
                    Log.d("TaskPlanningMiddleware", "Background Sync: Secured ${hotspots.size} thermal hotspots for offline use.")
                }
            } catch (e: Exception) {
                Log.e("TaskPlanningMiddleware", "Aviation Sync Error (Thermal): ${e.message}")
            }
        }
    }

    /**
     * Fill real terrain elevation for USER (ad-hoc dropped) spots that still lack it, so
     * the Skew-T sounding can root its parcel ascent. Runs in a coroutine that reads
     * post-reduce state; idempotent (in-flight + resolved sets guard against repeats and
     * a sea-level retry loop). Imported / PG spots already carry elevation and are skipped.
     */
    private fun backfillElevations(store: MapStore) {
        coroutineScope.launch {
            val need = store.state.value.waypointLibrary.filter { spot ->
                spot.source == com.ternparagliding.model.SpotSource.USER &&
                    (spot.alt == null || spot.alt == 0.0) &&
                    spot.id !in elevResolved &&
                    elevInFlight.add(spot.id) // atomically claim
            }
            if (need.isEmpty()) return@launch
            try {
                val elevs = com.ternparagliding.utils.io.ElevationApi.fetch(need.map { it.lat to it.lon })
                need.forEachIndexed { i, spot ->
                    val e = elevs.getOrNull(i)
                    if (e != null) {
                        elevResolved.add(spot.id) // got a reading — don't re-fetch
                        if (e.isFinite() && e != 0.0) {
                            store.dispatch(MapAction.UpdateSpot(spot.id, alt = e))
                        }
                    }
                }
            } finally {
                need.forEach { elevInFlight.remove(it.id) }
            }
        }
    }

    private fun zoomToTask(state: MapState, taskId: String, store: MapStore) {
        val task = state.tasks.find { it.id == taskId } ?: return
        task.extent?.let { extent ->
            Log.i("TaskPlanningMiddleware", "Auto-zooming to task: ${task.name}")
            // Fit the cylinders, not just the points. Padding by the largest cylinder
            // radius (+ a small margin) keeps the FAI rings on screen; fitting only the
            // point bounds drops the camera *inside* the cylinders for tightly-spaced
            // tasks, so they read as a screen-fill wash instead of visible rings.
            val maxRadiusKm = (task.waypoints.maxOfOrNull { it.radius ?: 0.0 } ?: 0.0) / 1000.0
            val padded = extent.withPaddingKm(maxRadiusKm + 0.5)
            store.dispatch(MapAction.UpdateBoundingBox(padded))

            // [RFC 005] Strategic Auto-Minimize: If zooming to a task extent, we are likely in "Strategic" mode.
            // Collapse the panel by default to show the whole task.
            store.dispatch(MapAction.SetTaskPanelExpanded(false))
        }
    }

    private fun triggerTaskCorridorSync(state: MapState, taskId: String, store: MapStore) {
        // Offline basemap: pre-download the corridor's tiles (debounced + dedup'd).
        prefetchCorridorTiles(taskId, store)

        val task = state.tasks.find { it.id == taskId } ?: return
        coroutineScope.launch {
            try {
                Log.i("TaskPlanningMiddleware", "Corridor safety sync for: ${task.name}")
                
                // [Aviation-Grade Truth] Perform real collision detection along the task
                val waypoints = task.waypoints.map { GeoPoint(it.lat, it.lon) }
                if (waypoints.isNotEmpty()) {
                    val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, waypoints.first()) ?: "US"
                    val nearbyAirspaces = CacheManager.airspaceCache.queryNearbyFeatures(countryCode, waypoints.first(), 50.0)
                    
                    // Segment-aware: flags a leg that *crosses* controlled airspace even when
                    // both its waypoints sit outside — and names which airspaces are crossed.
                    val conflicts = SpatialSafetyUtils.taskAirspaceConflicts(waypoints, nearbyAirspaces)
                    store.dispatch(MapAction.SetAirspaceCollision(conflicts.isNotEmpty(), conflicts))
                    // Weather risk along the task is now synthesised in the UI from the
                    // per-waypoint forecasts (assessTaskFlightRisk) — no separate storm flag.
                }

                Log.d("TaskPlanningMiddleware", "Corridor safety sync done (airspace) for ${task.name}")
            } catch (e: Exception) {
                Log.e("TaskPlanningMiddleware", "Corridor Sync Failed: ${e.message}")
            }
        }
    }

    /**
     * Pre-download the basemap tiles along a task's corridor so it flies fully offline
     * ("storage is free — download aggressively"). Debounced so a burst of waypoint edits
     * coalesces into one download, and dedup'd: a corridor already handled this session is
     * skipped; on the first touch this session it skips when a complete offline region is
     * already on disk (restart-safe); a corridor that genuinely changes mid-session re-caches
     * to cover the new extent. Reads post-reduce state (the middleware runs before the reducer)
     * so it always caches the *latest* corridor, not the pre-edit one.
     */
    private fun prefetchCorridorTiles(taskId: String, store: MapStore) {
        tileJobs.remove(taskId)?.cancel()
        tileJobs[taskId] = tileScope.launch {
            delay(TILE_DEBOUNCE_MS) // let a burst of edits settle into a single download
            val task = store.state.value.tasks.find { it.id == taskId } ?: return@launch
            if (task.extent == null) return@launch // no corridor yet (e.g. an empty task)
            val sig = corridorSignature(task)
            val prevSig = cachedCorridorSig.put(taskId, sig)
            if (prevSig == sig) return@launch // identical corridor already handled this session
            try {
                // First touch this session: skip if a complete region is already on disk.
                // A changed corridor (prevSig != null) always re-caches the new extent.
                if (prevSig == null && tileCacher.isTaskCached(task)) {
                    Log.d("TaskPlanningMiddleware", "Corridor tiles already cached for ${task.name}")
                    return@launch
                }
                Log.i("TaskPlanningMiddleware", "Pre-caching corridor tiles for ${task.name}")
                tileCacher.cacheTask(task).collect { p ->
                    if (p.isComplete) Log.i(
                        "TaskPlanningMiddleware",
                        "Corridor tiles cached for ${task.name}: ${p.completedResources} resources, ${p.completedBytes / 1024} KB",
                    )
                }
            } catch (e: Exception) {
                cachedCorridorSig.remove(taskId) // allow a retry on the next trigger
                Log.e("TaskPlanningMiddleware", "Corridor tile cache failed for ${task.name}: ${e.message}")
            }
        }
    }

    /** Stable hash of the corridor geometry (rounded coords + radius) — changes only when the route does. */
    private fun corridorSignature(task: com.ternparagliding.model.Task): Int =
        task.waypoints.joinToString("|") {
            "${"%.4f".format(it.lat)},${"%.4f".format(it.lon)}:${it.radius?.toInt() ?: 0}"
        }.hashCode()

    private companion object {
        /** Coalesce a burst of task edits into one corridor download. */
        private const val TILE_DEBOUNCE_MS = 1500L
    }
}

