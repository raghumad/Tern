package com.ternparagliding.redux

import android.content.Context
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Persists the pilot's unit preferences (temperature/distance/speed/altitude) to
 * SharedPreferences so a choice in Settings survives an app restart — the iOS
 * `MeasurementUnits.save()` equivalent. Without this the prefs live only in Redux
 * and snap back to defaults on every launch.
 *
 * Loads any saved prefs on start (dispatching them into the store), then observes
 * `state.settingsState` and writes through on every change. Observer, not
 * middleware, for the same reason as [TaskPersistence]: it must see the final
 * reduced state, not the pre-reducer value.
 */
object SettingsPersistence {
    private const val PREFS = "tern_unit_prefs"

    /** Suspends forever — launch from a `LaunchedEffect`/coroutine living as long as the store. */
    suspend fun observe(store: MapStore, context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Hydrate from disk first (only keys actually saved — absent keys keep defaults).
        sp.getString("temperature", null)?.let { store.dispatch(MapAction.SetUnitPreference("temperature", it)) }
        sp.getString("distance", null)?.let { store.dispatch(MapAction.SetUnitPreference("distance", it)) }
        sp.getString("speed", null)?.let { store.dispatch(MapAction.SetUnitPreference("speed", it)) }
        sp.getString("altitude", null)?.let { store.dispatch(MapAction.SetUnitPreference("altitude", it)) }

        // Write through on every change (the first emission re-saves the hydrated state — idempotent).
        store.state
            .map { it.settingsState }
            .distinctUntilChanged()
            .collect { s ->
                sp.edit()
                    .putString("temperature", s.temperatureUnit)
                    .putString("distance", s.distanceUnit)
                    .putString("speed", s.speedUnit)
                    .putString("altitude", s.altitudeUnit)
                    .apply()
            }
    }
}
