package com.ternparagliding.overlay.airspace

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.ternparagliding.overlay.priority.OverlayPrioritizer
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.cache.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
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
    if (state.center == null) return

    val airspaceCache = remember { CacheManager.airspaceCache }
    val prioritizer = remember { OverlayPrioritizer() }

    var featureCollection by remember { mutableStateOf(AirspaceGeoJson.empty()) }

    // Latest Redux state, read inside the long-lived collector without
    // re-keying it (which would tear the collector down on every pan).
    val latestState = rememberUpdatedState(state)

    // Single long-lived collector. The query+build is cancellable work; keying
    // a LaunchedEffect directly on `center` cancelled and relaunched it on every
    // ~30 ms pan tick, so during a continuous drag (or its inertial glide) the
    // ~250 ms query never survived to commit — the overlay starved for the whole
    // gesture and only caught up once the map fully settled (the "DC is 30 s
    // late" report). Instead we drive from a conflated snapshot flow: while a
    // query+build runs, intermediate centres collapse to the latest, and the
    // collector resumes with that newest centre once the current one commits.
    // Forward progress is guaranteed — the overlay refreshes every ~250 ms
    // *during* the drag instead of after it.
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
                // Re-query when the pilot moves far enough OR a country's data
                // finishes downloading (airspaceCountries changes, same centre —
                // must bypass the distance guard or freshly-downloaded airspace
                // would not appear until the next pan).
                if (moved < REQUERY_DISTANCE_KM && !countriesChanged) return@collect

                // Query (disk) AND build the GeoJSON entirely off the main
                // thread. Building the FeatureCollection here — not in
                // AirspaceLayer's composition — keeps the per-vertex parse of a
                // dense set (~370 ms for ~80 polygons) off the UI thread, so the
                // map never freezes while airspaces load.
                // DIAG: split the withContext(IO) round-trip into dispatch (time to
                // get an IO thread) / exec (queryAndScore) / resume (time to hop back
                // to this collector's dispatcher). At dense centres (DC) the total
                // balloons to ~26 s while exec stays ~75 ms — this tells us whether
                // it's IO-pool starvation (dispatch) or a frame-clock-gated resume.
                val tQuery = System.currentTimeMillis()
                var tIoStart = 0L
                var tIoEnd = 0L
                val newCandidates = withContext(Dispatchers.IO) {
                    tIoStart = System.currentTimeMillis()
                    val r = queryAndScore(airspaceCache, prioritizer, center)
                    tIoEnd = System.currentTimeMillis()
                    r
                }
                val tBuild = System.currentTimeMillis()
                val built = withContext(Dispatchers.Default) {
                    AirspaceGeoJson.toFeatureCollection(newCandidates)
                }
                Log.d(
                    TAG,
                    "query+build: ${newCandidates.size} candidates @ " +
                        "${center.latitude},${center.longitude} " +
                        "dispatch=${tIoStart - tQuery}ms exec=${tIoEnd - tIoStart}ms " +
                        "resume=${tBuild - tIoEnd}ms build=${System.currentTimeMillis() - tBuild}ms " +
                        "total=${tBuild - tQuery}ms",
                )

                featureCollection = built
                lastQueryCenter = center
                lastQueriedCountries = countries
            }
    }

    // Honour the Settings toggle. We keep the collector above running (so re-enabling is instant)
    // but feed the layer an empty collection when airspaces are switched off.
    val enabled = state.overlayState.airspaces.enabled
    AirspaceLayer(featureCollection = if (enabled) featureCollection else AirspaceGeoJson.empty())
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
