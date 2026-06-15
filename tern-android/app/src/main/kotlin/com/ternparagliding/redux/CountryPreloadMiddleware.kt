package com.ternparagliding.redux

import android.content.Context
import android.util.Log
import com.ternparagliding.utils.cache.CacheManager
import com.ternparagliding.utils.geo.CountryUtils

/**
 * Drives the (previously orphaned) [com.ternparagliding.utils.cache.UniversalCountryCacheManager]
 * from map movement, so airspace + PG-spot data actually downloads as the
 * pilot moves the map across regions and borders.
 *
 * Before this, nothing instantiated or fed the country cache manager, so the
 * overlay caches were never populated in production — the overlays only ever
 * showed data in tests that injected it.
 *
 * Hooked to BOTH map-centre actions: [MapAction.UpdateCenter] (programmatic
 * moves — GPS first-fix recenter, task framing) AND
 * [MapAction.UpdateMapMovement] (the gesture→Redux feedback dispatched when the
 * pilot *drags* the map). The latter is essential: dragging never produces an
 * UpdateCenter, so keying only on UpdateCenter meant panning across a border
 * (e.g. US → France) never triggered a download and the new country's overlays
 * never appeared. The manager itself throttles to ≥0.5 km moves, dedups
 * in-flight downloads, caps cached countries (LRU), and preloads adjacent
 * countries near borders — so this middleware only needs to forward the centre.
 * We read the centre from the action payload, so the store's pre-batch
 * stale-state behaviour for middleware doesn't matter here.
 *
 * No-ops under test (a pinned test country code) so injected-data instrumented
 * tests don't fire real "TEST"-country network downloads.
 */
class CountryPreloadMiddleware(
    @Suppress("unused") private val appContext: Context,
) : Middleware {

    @Volatile
    private var listenerRegistered = false

    override suspend fun process(action: TernAction, store: MapStore) {
        if (CountryUtils.isTestMode()) return

        // Both programmatic centre changes AND finger-drag camera feedback —
        // dragging dispatches UpdateMapMovement, not UpdateCenter.
        val center = when (action) {
            is MapAction.UpdateCenter -> action.center
            is MapAction.UpdateMapMovement -> action.center
            else -> null
        } ?: return

        ensureCountryLoadedListener(store)
        try {
            CacheManager.countryCacheManager.onLocationChanged(center)
        } catch (e: Exception) {
            Log.e(TAG, "country preload failed for $center: ${e.message}")
        }
    }

    /**
     * When a country's caches finish downloading, the overlay composables won't
     * re-query on their own (their LaunchedEffect is keyed on the map centre,
     * which hasn't changed). Dispatching [MapAction.AddAirspaceCountry] bumps
     * `state.airspaceCountries`, which the airspace/PG-spot overlays key on, so
     * the freshly-downloaded data renders immediately. Registered once.
     */
    private fun ensureCountryLoadedListener(store: MapStore) {
        if (listenerRegistered) return
        listenerRegistered = true
        CacheManager.countryCacheManager.onCountryLoadedListeners.add { country ->
            Log.i(TAG, "country loaded → refreshing overlays: $country")
            store.dispatch(MapAction.AddAirspaceCountry(country.uppercase()))
        }
    }

    private companion object {
        const val TAG = "CountryPreloadMW"
    }
}
