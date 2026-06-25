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
        // The pilot's chosen vario (MAC + name) — so Tern reconnects only to *their* device,
        // and (unless paused) auto-connects on launch.
        sp.getString("vario_mac", null)?.let {
            store.dispatch(MapAction.SetRememberedVario(it, sp.getString("vario_name", null)))
            if (sp.getBoolean("vario_paused", false)) store.dispatch(MapAction.SetVarioPaused(true))
        }
        // The pilot's current Mezulla team (intent) — re-shown on launch and re-applied to the board
        // on connect. team_applied_link is restored separately so we don't rewrite an unchanged channel.
        //
        // Only hydrate when no team is set in memory yet. observe() re-runs on every Activity
        // (re)creation, and a team-join deep link (tern://team) is handled in onCreate BEFORE this
        // composes — so without this guard the disk value would clobber a team the pilot just joined
        // (the join would silently revert to the previously-saved team).
        if (store.state.value.settingsState.teamName == null) {
            sp.getString("team_name", null)?.let {
                store.dispatch(MapAction.SetTeam(it, sp.getString("team_link", null), sp.getString("team_source", null)))
                sp.getString("team_applied_link", null)?.let { applied ->
                    store.dispatch(MapAction.SetTeamApplied(applied))
                }
            }
        }

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
                    .putString("vario_mac", s.rememberedVarioMac)
                    .putString("vario_name", s.rememberedVarioName)
                    .putBoolean("vario_paused", s.varioPaused)
                    .putString("team_name", s.teamName)
                    .putString("team_link", s.teamShareLink)
                    .putString("team_source", s.teamSource)
                    .putString("team_applied_link", s.teamAppliedLink)
                    .apply()
            }
    }
}
