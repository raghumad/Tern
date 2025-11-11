package com.madanala.tern.ui.components

import android.util.Log
import com.madanala.tern.model.Waypoint
import com.madanala.tern.route.RouteStore
import com.madanala.tern.route.WaypointStore
import com.madanala.tern.route.WaypointOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Manages waypoint creation logic for the map view.
 * Handles the complex waypoint creation workflow including route management and marker placement.
 */
class WaypointCreationManager(
    private val mapView: MapView,
    private val coroutineScope: CoroutineScope
) {

    /**
     * Creates a waypoint at the specified location with the given type.
     * Handles route creation/management, waypoint storage, and marker placement.
     */
    fun createWaypoint(coordinate: GeoPoint, type: Waypoint.Type) {
        Log.d(TAG, "Creating waypoint at: ${coordinate.latitude}, ${coordinate.longitude} with type: $type")

        // Move waypoint creation to background thread for aviation safety
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Create waypoint directly using route-centric approach
                val newWaypoint = Waypoint(
                    lat = coordinate.latitude,
                    lon = coordinate.longitude,
                    type = type
                )

                // Add waypoint to route using RouteStore (single source of truth)
                val currentRoute = RouteStore.getCurrentRoute() ?: RouteStore.createRoute("Flight Route")
                RouteStore.setCurrentRoute(currentRoute.id)

                // Synchronize with WaypointStore for marker management (Phase 1 compatibility)
                val waypointWithRouteId = newWaypoint.copy(routeId = currentRoute.id)
                WaypointStore.add(waypointWithRouteId)

                RouteStore.addWaypointToRoute(currentRoute.id, newWaypoint)

                Log.d(TAG, "Waypoint created with ID: ${newWaypoint.id}")

                // Add marker for the waypoint (only once) - must be on main thread
                withContext(Dispatchers.Main) {
                    WaypointOverlay.addMarker(mapView, waypointWithRouteId, WaypointStore) { isDragging ->
                        // Handle dragging state if needed
                        Log.d(TAG, "Waypoint dragging state: $isDragging")
                    }
                    Log.d(TAG, "Waypoint marker added for: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create waypoint", e)
            }
        }
    }

    companion object {
        private const val TAG = "WaypointCreationManager"
    }
}
