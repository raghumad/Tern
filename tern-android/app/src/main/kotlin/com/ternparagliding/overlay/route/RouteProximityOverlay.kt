package com.ternparagliding.overlay.route

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.cache.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

private const val TAG = "RouteProximityOverlay"

/** Routes are large; requery only after the pilot moves a few km. */
private const val REQUERY_DISTANCE_KM = 5.0

/** How far around the pilot to surface preplanned routes from. */
private const val QUERY_RADIUS_KM = 100.0

/**
 * Surfaces preplanned routes from the offline spatial [com.ternparagliding.utils.cache.RouteCache]
 * when the pilot is near them — the "fly to Europe and your routes there show
 * up" behavior. Mirrors `AirspaceOverlay`'s query pattern: read pilot center
 * from Redux, query the cache by centroid proximity on IO, and merge results
 * into `state.routes` (via [MapAction.SurfaceNearbyRoutes]) so the existing
 * `RouteLayer` draws them.
 *
 * Emits no map layer itself — it only feeds state — so it can live anywhere
 * in the composition (it isn't a MaplibreMap child).
 */
@Composable
fun RouteProximityOverlay(store: MapStore) {
    val state by store.state.collectAsState()

    val routeCache = remember { CacheManager.routeCache }

    // Latest Redux state, read inside the long-lived collector without re-keying.
    val latestState = rememberUpdatedState(state)

    // Conflated snapshot flow rather than LaunchedEffect(center): keying on
    // `center` cancelled the IO query on every ~30 ms pan tick, so a continuous
    // drag could finish before any query committed and nearby routes wouldn't
    // surface until the map settled. conflate() guarantees forward progress —
    // each query completes, then the collector resumes with the latest centre.
    // (See AirspaceOverlay for the full rationale.)
    LaunchedEffect(Unit) {
        var lastQueryCenter: GeoPoint? = null

        snapshotFlow { latestState.value.center }
            .conflate()
            .collect { center ->
                if (center == null) return@collect

                val moved = lastQueryCenter?.let { it.distanceToAsDouble(center) / 1000.0 }
                    ?: Double.MAX_VALUE
                if (moved < REQUERY_DISTANCE_KM) return@collect

                val nearby = withContext(Dispatchers.IO) {
                    routeCache.queryNearbyRoutes(center, QUERY_RADIUS_KM / 1.60934)
                }
                lastQueryCenter = center

                if (nearby.isNotEmpty()) {
                    Log.d(TAG, "Surfacing ${nearby.size} nearby route(s) @ ${center.latitude},${center.longitude}")
                    store.dispatch(MapAction.SurfaceNearbyRoutes(nearby))
                }
            }
    }
}
