package com.ternparagliding.overlay.pgspot

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.ternparagliding.overlay.priority.OverlayPrioritizer
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.WeatherActions
import com.ternparagliding.utils.cache.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
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
 * [com.ternparagliding.utils.cache.PGSpotCache] via Hilbert spatial index,
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
    val state by store.state.collectAsState()

    // Respect the pilot's PG-spots toggle.
    if (!state.overlayState.pgSpots.enabled) return

    val pgSpotCache = remember { CacheManager.pgSpotCache }
    val prioritizer = remember { OverlayPrioritizer() }

    // Latest Redux state, read inside the long-lived collector without re-keying
    // it (which would tear the collector down on every pan).
    val latestState = rememberUpdatedState(state)

    // Single long-lived collector driven by a conflated snapshot flow. Keying a
    // LaunchedEffect directly on `center` cancelled and relaunched this on every
    // ~30 ms pan tick, so during a continuous drag (or its inertial glide) the
    // query never survived to commit and spots starved for the whole gesture.
    // conflate() collapses intermediate centres to the latest while a query
    // runs, so each query completes and the collector resumes with the newest
    // centre — forward progress, spots refresh *during* the drag. Mirrors
    // AirspaceOverlay. (See its comment for the full rationale.)
    LaunchedEffect(Unit) {
        var lastQueryCenter: GeoPoint? = null
        var lastQueriedCountries: Set<String> = emptySet()

        snapshotFlow { latestState.value.center to latestState.value.airspaceCountries }
            .conflate()
            .collect { (center, countries) ->
                if (center == null) return@collect

                val moved = lastQueryCenter?.let { prev ->
                    prev.distanceToAsDouble(center) / 1000.0
                } ?: Double.MAX_VALUE
                val countriesChanged = countries != lastQueriedCountries
                if (moved < REQUERY_DISTANCE_KM && !countriesChanged) return@collect

                val features = withContext(Dispatchers.IO) {
                    queryAndScore(pgSpotCache, prioritizer, center)
                }

                store.dispatch(MapAction.UpdatePgSpotGeoJson(overlayFeaturesToGeoJson(features)))
                lastQueryCenter = center
                lastQueriedCountries = countries
                Log.d(TAG, "PG-spot query: ${features.size} spots @ ${center.latitude},${center.longitude}")
            }
    }

    // A PG spot referenced by a *visible* task is already drawn by the task (its role
    // marker), so suppress the live teal marker for it — no double-render. The task's
    // captured spot carries the PG id in `sourceId` ("name|lat|lon").
    val hiddenPgIds = remember(state.tasks, state.waypointLibrary) {
        val pgById = state.waypointLibrary
            .filter { it.source == com.ternparagliding.model.SpotSource.PG_SPOT }
            .associateBy { it.id }
        state.tasks.filter { it.isVisible }
            .flatMap { it.waypoints }
            .mapNotNull { it.spotId }
            .mapNotNull { pgById[it]?.sourceId }
            .toSet()
    }
    val shownPgSpots = remember(state.pgSpotGeoJson, hiddenPgIds) {
        val fc = state.pgSpotGeoJson ?: EMPTY_PG_SPOT_COLLECTION
        if (hiddenPgIds.isEmpty()) fc else org.maplibre.spatialk.geojson.FeatureCollection(
            fc.features.filter { f ->
                val pt = f.geometry as? org.maplibre.spatialk.geojson.Point ?: return@filter true
                val name = (f.properties?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: return@filter true
                "$name|${pt.coordinates.latitude}|${pt.coordinates.longitude}" !in hiddenPgIds
            }
        )
    }

    PgSpotLayer(
        featureCollection = shownPgSpots,
        onSpotClick = { name, lat, lng, site ->
            // Open the weather/Flyability dialog immediately (loading), then kick the
            // on-demand fetch. The whole downstream chain already exists:
            // FetchWeatherForPGSpot → WeatherMiddleware → WeatherFetched →
            // spotWeathers[id], which the dialog reads to render the FlyabilityCard.
            // The launch geometry (site) rides along so the read is site-aware.
            val id = "$name|$lat|$lng"
            store.dispatch(WeatherActions.ShowWeatherDetails(id, name, null, site))
            store.dispatch(WeatherActions.FetchWeatherForPGSpot(id, lat, lng))
        },
    )
}

/**
 * Query the spatial cache for the country near [center] and run the
 * result through the prioritizer so dense clusters stay within budget.
 */
private fun queryAndScore(
    cache: com.ternparagliding.utils.cache.PGSpotCache,
    prioritizer: OverlayPrioritizer,
    center: GeoPoint,
): List<com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature> {
    // No reverse-geocode: render PG-spots already cached near the centre (across
    // all cached countries). The geocode was a slow network call that delayed
    // the overlay; the data is on disk, so paint it immediately.
    val radiusMiles = QUERY_RADIUS_KM / 1.60934
    val features = cache.queryAllCachedNearby(center, radiusMiles)

    val candidates = features.map { PgSpotCandidate(feature = it) }
    val pilotPos = Position(center.latitude, center.longitude)
    return prioritizer.prioritize(candidates, pilotPos)
        .filterIsInstance<PgSpotCandidate>()
        .map { it.feature }
}
