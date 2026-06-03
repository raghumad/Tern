package com.ternparagliding.overlay.airspace

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ternparagliding.overlay.priority.OverlayPrioritizer
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.CacheManager
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
    val context = LocalContext.current.applicationContext
    val state by store.state.collectAsState()
    val center = state.center ?: return

    val airspaceCache = remember { CacheManager.airspaceCache }
    val prioritizer = remember { OverlayPrioritizer() }

    var candidates by remember { mutableStateOf<List<AirspaceCandidate>>(emptyList()) }
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

        val newCandidates = withContext(Dispatchers.IO) {
            queryAndScore(context, airspaceCache, prioritizer, center)
        }

        candidates = newCandidates
        lastQueryCenter = center
        lastQueriedCountries = state.airspaceCountries
        Log.d(TAG, "Airspace query: ${newCandidates.size} candidates @ ${center.latitude},${center.longitude}")
    }

    AirspaceLayer(candidates = candidates)
}

/**
 * Query the spatial cache for all countries that have data near
 * [center], resolve airspace classes, filter out Class G, and
 * run the result through the prioritizer.
 */
private fun queryAndScore(
    context: android.content.Context,
    cache: com.ternparagliding.utils.AirspaceCache,
    prioritizer: OverlayPrioritizer,
    center: GeoPoint,
): List<AirspaceCandidate> {
    // Determine which country's cache to query. The AirspaceCache
    // is keyed by country code. For now, query the country the
    // pilot is in. The UniversalCountryCacheManager handles
    // multi-country queries at a higher level — this composable
    // works with whatever data the cache already holds.
    val countryCode = com.ternparagliding.utils.CountryUtils.getCountryCodeFromCoordinates(
        context, center.latitude, center.longitude,
    ) ?: return emptyList()

    val radiusMiles = QUERY_RADIUS_KM / 1.60934
    val features = cache.queryNearbyFeatures(countryCode, center, radiusMiles)

    val allCandidates = features.mapNotNull { feature ->
        val cls = AirspaceGeoJson.resolveAirspaceClass(feature)
        if (AirspaceGeoJson.isUnrestricted(cls)) return@mapNotNull null
        AirspaceCandidate(feature = feature, airspaceClass = cls)
    }

    val pilotPos = Position(center.latitude, center.longitude)
    return prioritizer.prioritize(allCandidates, pilotPos)
        .filterIsInstance<AirspaceCandidate>()
}
