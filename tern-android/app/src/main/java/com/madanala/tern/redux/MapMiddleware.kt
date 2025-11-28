package com.madanala.tern.redux

import android.content.Context
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.CountryUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * Middleware for handling async side effects in MapStore
 */
interface Middleware {
    suspend fun process(action: Any, store: MapStore)
}

class MapMiddleware(private val context: Context) : Middleware {

    override suspend fun process(action: Any, store: MapStore) {
        when (action) {
            is MapAction.CheckSmartSuggestion -> {
                handleCheckSmartSuggestion(action, store)
            }
        }
    }

    private fun handleCheckSmartSuggestion(action: MapAction.CheckSmartSuggestion, store: MapStore) {
        // Run on IO dispatcher
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val geoPoint = action.geoPoint
                val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, geoPoint)
                
                if (countryCode != null) {
                    val nearbySpots = CacheManager.pgSpotCache.queryNearbyPGSpots(countryCode, geoPoint, 15.5)
                    
                    if (nearbySpots.isNotEmpty()) {
                        val closest = nearbySpots.minByOrNull { it.centroid.distanceToAsDouble(geoPoint) }
                        if (closest != null) {
                            store.dispatch(MapAction.SetSmartSuggestion(closest, geoPoint))
                            return@launch
                        }
                    }
                }
                
                // No spot found, clear suggestion (or do nothing? Original logic called onNoNearby)
                // If we want to strictly follow original logic, we might need another action or just let UI handle it.
                // But here, if no spot found, we probably want to ensure no suggestion is set.
                // However, the UI logic was: if no nearby, dispatch LongPressMap immediately.
                // So we should dispatch LongPressMap if no suggestion found?
                
                // Wait, the original logic was:
                // mapViewModel.checkForSmartSuggestion(..., onNoNearby = { store.dispatch(MapAction.LongPressMap(geoPoint)) })
                
                // So if found -> SetSmartSuggestion (UI shows dialog)
                // If NOT found -> LongPressMap (Create waypoint immediately)
                
                store.dispatch(MapAction.LongPressMap(geoPoint))
                
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback
                store.dispatch(MapAction.LongPressMap(action.geoPoint))
            }
        }
    }
}
