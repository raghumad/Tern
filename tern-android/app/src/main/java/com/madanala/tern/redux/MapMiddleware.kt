package com.madanala.tern.redux

import android.content.Context
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.CountryUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import com.madanala.tern.utils.SpatialSafetyUtils
import android.util.Log

/**
 * Middleware for handling async side effects in MapStore
 */
interface Middleware {
    suspend fun process(action: TernAction, store: MapStore)
}

class MapMiddleware(private val context: Context) : Middleware {

    override suspend fun process(action: TernAction, store: MapStore) {
        when (action) {
            is MapAction.CheckSmartSuggestion -> {
                handleCheckSmartSuggestion(action, store)
            }
            is MapAction.UpdateUserLocation -> {
                handleUserLocationUpdate(action, store)
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

    private fun handleUserLocationUpdate(action: MapAction.UpdateUserLocation, store: MapStore) {
        val point = action.location ?: return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // [Aviation-Grade Truth] Determine country code for spatial index lookup
                val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, point) ?: "US"
                
                // Query nearby airspaces (10 miles radius for safety buffer)
                val nearbyAirspaces = CacheManager.airspaceCache.queryNearbyFeatures(countryCode, point, 10.0)
                
                // Perform truthful point-in-polygon collision check
                val hasCollision = SpatialSafetyUtils.checkAirspaceCollision(point, nearbyAirspaces)
                
                // Dispatch truthful result to state
                store.dispatch(MapAction.SetAirspaceCollision(hasCollision))
                
                // Also check for storm risk at current location
                val weatherState = store.state.value.weatherState
                val firstForecast = weatherState.waypointWeathers.values.firstOrNull()
                val hasStorm = SpatialSafetyUtils.checkStormRisk(point, firstForecast)
                store.dispatch(WeatherActions.SetStormRisk(hasStorm))
                
            } catch (e: Exception) {
                Log.e("MapMiddleware", "Safety check failed: ${e.message}")
            }
        }
    }
}
