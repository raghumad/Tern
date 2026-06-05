package com.ternparagliding.overlay.airspace

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ternparagliding.overlay.priority.OverlayPrioritizer
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.cache.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraState
import org.osmdroid.util.GeoPoint

private const val TAG = "AirspaceOverlay"

/**
 * Minimum movement in km before re-querying the cache. Paragliding
 * speeds are ~30-50 km/h; 2 km means a refresh roughly every 2-4
 * minutes in flight, which is a good balance between freshness and
 * not hammering the spatial index.
 */
private const val REQUERY_DISTANCE_KM = 2.0

/** Hilbert query radius — covers the relevant airspace horizon. */
private const val QUERY_RADIUS_KM = 200.0

/**
 * Composable bridge: reads pilot position from Redux, queries
 * [AirspaceCache] via Hilbert spatial index, filters and scores
 * through [OverlayPrioritizer], and feeds surviving candidates
 * to [AirspaceLayer] for GPU rendering.
 */
@Composable
fun AirspaceOverlay(
    store: MapStore,
    cameraState: CameraState,
) {
    val state by store.state.collectAsState()
    val center = state.center ?: return

    val airspaceCache = remember { CacheManager.airspaceCache }
    val prioritizer = remember { OverlayPrioritizer() }

    var featureCollection by remember { mutableStateOf(AirspaceGeoJson.empty()) }
    var lastQueryCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var lastQueriedCountries by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Re-query when the pilot moves far enough OR a country's data finishes
    // downloading (state.airspaceCountries changes via CountryPreloadMiddleware) —
    // the latter has the same centre, so it must bypass the distance guard or the
    // freshly-downloaded airspace would not appear until the next pan.
    LaunchedEffect(center, state.airspaceCountries) {
        val moved = lastQueryCenter?.let { prev ->
            prev.distanceToAsDouble(center) / 1000.0
        } ?: Double.MAX_VALUE
        val countriesChanged = state.airspaceCountries != lastQueriedCountries

        if (moved < REQUERY_DISTANCE_KM && !countriesChanged) return@LaunchedEffect

        // Query (disk) AND build the GeoJSON entirely off the main thread.
        // Building the FeatureCollection here — not in AirspaceLayer's
        // composition — keeps the per-vertex parse of a dense set (~370 ms for
        // ~80 polygons) off the UI thread, so the map never freezes while
        // airspaces load. AirspaceLayer just hands the finished collection to
        // the GPU source.
        val newCandidates = withContext(Dispatchers.IO) {
            queryAndScore(airspaceCache, prioritizer, center)
        }
        Log.d(TAG, "Airspace query: ${newCandidates.size} candidates @ ${center.latitude},${center.longitude}")
        val built = withContext(Dispatchers.Default) {
            AirspaceGeoJson.toFeatureCollection(newCandidates)
        }

        featureCollection = built
        lastQueryCenter = center
        lastQueriedCountries = state.airspaceCountries
    }

    AirspaceLayer(featureCollection = featureCollection)
}

/**
 * Query the spatial cache for airspace near [center] across ALL cached
 * countries, resolve airspace classes, filter out Class G, and run the result
 * through the prioritizer.
 *
 * Note: deliberately does NOT reverse-geocode the centre to pick a single
 * country. That geocode is a slow network call that gated every render and made
 * airspaces appear seconds late when panning. The data is already on disk, so
 * we paint whatever is cached near the centre immediately (and both countries'
 * airspace near a border). Country detection still drives the *download* path
 * (UniversalCountryCacheManager) for fetching not-yet-cached countries.
 */
private fun queryAndScore(
    cache: com.ternparagliding.utils.cache.AirspaceCache,
    prioritizer: OverlayPrioritizer,
    center: GeoPoint,
): List<AirspaceCandidate> {
    val radiusMiles = QUERY_RADIUS_KM / 1.60934
    val features = cache.queryAllCachedNearby(center, radiusMiles)

    val allCandidates = features.mapNotNull { feature ->
        val cls = AirspaceGeoJson.resolveAirspaceClass(feature)
        if (AirspaceGeoJson.isUnrestricted(cls)) return@mapNotNull null
        AirspaceCandidate(feature = feature, airspaceClass = cls)
    }

    val pilotPos = Position(center.latitude, center.longitude)
    return prioritizer.prioritize(allCandidates, pilotPos)
        .filterIsInstance<AirspaceCandidate>()
}
