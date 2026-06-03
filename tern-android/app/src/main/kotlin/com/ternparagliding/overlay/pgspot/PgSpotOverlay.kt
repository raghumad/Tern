package com.ternparagliding.overlay.pgspot

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
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraState
import org.osmdroid.util.GeoPoint

private const val TAG = "PgSpotOverlay"

/**
 * Minimum movement in km before re-querying the cache. Mirrors
 * [com.ternparagliding.overlay.airspace.AirspaceOverlay]: paragliding
 * speeds are ~30-50 km/h, so 2 km is a refresh roughly every 2-4 minutes
 * in flight — fresh enough without hammering the spatial index.
 */
private const val REQUERY_DISTANCE_KM = 2.0

/** Hilbert query radius — covers the relevant PG-spot horizon. */
private const val QUERY_RADIUS_KM = 200.0

/**
 * Composable bridge: reads pilot position from Redux, queries
 * [com.ternparagliding.utils.PGSpotCache] via Hilbert spatial index,
 * scores through [OverlayPrioritizer], converts to GeoJSON, and feeds
 * the result to [PgSpotLayer] for GPU rendering.
 *
 * This is the PG-spot analogue of `AirspaceOverlay`. PG-spot data is
 * downloaded alongside airspaces by `UniversalCountryCacheManager`, so
 * wherever airspaces have data this overlay does too.
 *
 * The computed [overlayFeaturesToGeoJson] result is published to Redux
 * via [MapAction.UpdatePgSpotGeoJson] — the single source of truth the
 * layer renders from (and the signal `waitForMapData` watches in tests).
 */
@Composable
fun PgSpotOverlay(
    store: MapStore,
    cameraState: CameraState,
) {
    val context = LocalContext.current.applicationContext
    val state by store.state.collectAsState()
    val center = state.center ?: return

    // Respect the pilot's PG-spots toggle.
    if (!state.overlayState.pgSpots.enabled) return

    val pgSpotCache = remember { CacheManager.pgSpotCache }
    val prioritizer = remember { OverlayPrioritizer() }

    var lastQueryCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var lastQueriedCountries by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Re-query when the pilot moves far enough OR a country's data finishes
    // downloading. PG spots and airspace are downloaded together by
    // UniversalCountryCacheManager, so state.airspaceCountries doubles as the
    // "a country just loaded" signal; it must bypass the distance guard (same
    // centre) or freshly-downloaded spots wouldn't appear until the next pan.
    LaunchedEffect(center, state.airspaceCountries) {
        val moved = lastQueryCenter?.let { prev ->
            prev.distanceToAsDouble(center) / 1000.0
        } ?: Double.MAX_VALUE
        val countriesChanged = state.airspaceCountries != lastQueriedCountries

        if (moved < REQUERY_DISTANCE_KM && !countriesChanged) return@LaunchedEffect

        val features = withContext(Dispatchers.IO) {
            queryAndScore(context, pgSpotCache, prioritizer, center)
        }

        store.dispatch(MapAction.UpdatePgSpotGeoJson(overlayFeaturesToGeoJson(features)))
        lastQueryCenter = center
        lastQueriedCountries = state.airspaceCountries
        Log.d(TAG, "PG-spot query: ${features.size} spots @ ${center.latitude},${center.longitude}")
    }

    PgSpotLayer(featureCollection = state.pgSpotGeoJson ?: EMPTY_PG_SPOT_COLLECTION)
}

/**
 * Query the spatial cache for the country near [center] and run the
 * result through the prioritizer so dense clusters stay within budget.
 */
private fun queryAndScore(
    context: android.content.Context,
    cache: com.ternparagliding.utils.PGSpotCache,
    prioritizer: OverlayPrioritizer,
    center: GeoPoint,
): List<com.ternparagliding.utils.MapOverlayCacheUtils.OverlayFeature> {
    val countryCode = com.ternparagliding.utils.CountryUtils.getCountryCodeFromCoordinates(
        context, center.latitude, center.longitude,
    ) ?: return emptyList()

    val radiusMiles = QUERY_RADIUS_KM / 1.60934
    val features = cache.queryNearbyPGSpots(countryCode, center, radiusMiles)

    val candidates = features.map { PgSpotCandidate(feature = it) }
    val pilotPos = Position(center.latitude, center.longitude)
    return prioritizer.prioritize(candidates, pilotPos)
        .filterIsInstance<PgSpotCandidate>()
        .map { it.feature }
}
