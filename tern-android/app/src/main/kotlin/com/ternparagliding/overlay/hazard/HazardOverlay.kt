package com.ternparagliding.overlay.hazard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.ternparagliding.redux.MapStore

/**
 * Composable bridge: reads task waypoints and their weather forecasts
 * from Redux, classifies each into a [HazardLevel], and feeds the
 * hazardous ones to [HazardLayer] for GPU rendering.
 *
 * Unlike `AirspaceOverlay` / `PgSpotOverlay`, there's no spatial-cache
 * query — the weather middleware already keeps `waypointWeathers` in
 * Redux, so this is a pure state-derived layer. Hazards are always on
 * (no toggle): suppressing a storm warning in flight is a safety
 * regression, not a decluttering choice.
 */
@Composable
fun HazardOverlay(
    store: MapStore,
) {
    val state by store.state.collectAsState()

    val sites = remember(state.tasks, state.weatherState.waypointWeathers) {
        hazardSitesFrom(state.tasks, state.weatherState.waypointWeathers)
    }

    HazardLayer(featureCollection = hazardSitesToGeoJson(sites))
}
