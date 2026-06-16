package com.ternparagliding.overlay.waypoint

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.WeatherActions
import com.ternparagliding.weather.SiteContext

/**
 * Puts the standalone **waypoint library** on the map and makes each waypoint
 * behave like a PG spot: tap it for a site-aware weather / Flyability read. Reuses
 * the PG-spot weather path (it's keyed by an arbitrary id + lat/lon), so the whole
 * fetch → dialog chain is shared.
 *
 * Waypoints already drawn by a *visible task* are skipped here so they don't render
 * twice (the task draws them with their role colour + cylinder).
 *
 * Call inside the `MaplibreMap { ... }` content lambda.
 */
@Composable
fun WaypointLibraryOverlay(store: MapStore) {
    val state by store.state.collectAsState()
    if (!state.overlayState.waypoints.enabled) return
    val library = state.waypointLibrary
    if (library.isEmpty()) return

    val inVisibleTask = remember(state.tasks) {
        state.tasks.filter { it.isVisible }
            .flatMap { it.waypoints }
            .mapNotNull { it.libraryWaypointId }
            .toSet()
    }
    val shown = remember(library, inVisibleTask) { library.filter { it.id !in inVisibleTask } }

    WaypointLibraryLayer(
        waypoints = shown,
        onClick = { code, lat, lon, altM ->
            // Same flow as a PG-spot tap: open the dialog (loading), then fetch.
            // A waypoint has elevation but no launch orientation, so the read is
            // cloudbase-aware but not wind-vs-hill (correct for a turnpoint).
            val id = "wp|$code|$lat|$lon"
            store.dispatch(WeatherActions.ShowWeatherDetails(id, code, null, SiteContext(elevationM = altM)))
            store.dispatch(WeatherActions.FetchWeatherForPGSpot(id, lat, lon))
        },
    )
}
