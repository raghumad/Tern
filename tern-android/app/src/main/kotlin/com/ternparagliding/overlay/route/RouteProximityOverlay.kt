package com.ternparagliding.overlay.route

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.cache.CacheManager
import kotlinx.coroutines.Dispatchers
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
    val center = state.center ?: return

    val routeCache = remember { CacheManager.routeCache }
    var lastQueryCenter by remember { mutableStateOf<GeoPoint?>(null) }

    LaunchedEffect(center) {
        val moved = lastQueryCenter?.let { it.distanceToAsDouble(center) / 1000.0 } ?: Double.MAX_VALUE
        if (moved < REQUERY_DISTANCE_KM) return@LaunchedEffect

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
