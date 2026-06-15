package com.ternparagliding.overlay.thermal

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.geo.ThermalHotspotService
import kotlinx.coroutines.flow.conflate
import org.maplibre.compose.camera.CameraState
import org.osmdroid.util.GeoPoint

private const val TAG = "ThermalHotspotOverlay"

/** Hotspots are regional (kk7 caches ~0.5° tiles) — only re-fetch after a meaningful move. */
private const val REQUERY_DISTANCE_KM = 5.0

/**
 * Composable bridge for kk7 thermal hotspots — the analogue of
 * [com.ternparagliding.overlay.pgspot.PgSpotOverlay]. Reads pilot position from Redux, fetches
 * hotspots via [ThermalHotspotService] (network + SpatialDiskCache, so it's offline-warm), converts
 * to GeoJSON, publishes via [MapAction.UpdateThermalHotspotGeoJson], and renders [ThermalHotspotLayer].
 *
 * Gated on the pilot's "Hotspots" toggle. Fetching only runs while enabled; flipping it off hides
 * the layer immediately.
 */
@Composable
fun ThermalHotspotOverlay(
    store: MapStore,
    cameraState: CameraState,
) {
    val state by store.state.collectAsState()
    if (!state.overlayState.thermalHotspots.enabled) return

    val context = LocalContext.current
    val service = remember { ThermalHotspotService(context.applicationContext) }
    val latestState = rememberUpdatedState(state)

    // Single long-lived collector driven by a conflated centre flow (mirrors PgSpotOverlay): keying
    // on `center` directly would relaunch the fetch on every pan tick and never settle.
    LaunchedEffect(Unit) {
        var lastQueryCenter: GeoPoint? = null
        snapshotFlow { latestState.value.center }
            .conflate()
            .collect { center ->
                if (center == null) return@collect
                val moved = lastQueryCenter?.let { it.distanceToAsDouble(center) / 1000.0 } ?: Double.MAX_VALUE
                if (moved < REQUERY_DISTANCE_KM) return@collect

                val features = service.getHotspots(center)
                lastQueryCenter = center
                // getHotspots returns empty while a background download is in flight; don't clobber
                // a good collection with an empty one in that transient case.
                if (features.isNotEmpty() || state.thermalHotspotGeoJson == null) {
                    store.dispatch(MapAction.UpdateThermalHotspotGeoJson(thermalFeaturesToGeoJson(features)))
                    Log.d(TAG, "thermal query: ${features.size} hotspots @ ${center.latitude},${center.longitude}")
                }
            }
    }

    ThermalHotspotLayer(featureCollection = state.thermalHotspotGeoJson ?: EMPTY_THERMAL_COLLECTION)
}
