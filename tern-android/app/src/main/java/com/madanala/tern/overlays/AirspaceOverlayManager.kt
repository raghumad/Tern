package com.madanala.tern.overlays

import android.content.Context
import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.ui.components.MapViewModel.Companion.MAX_VISIBLE_AIRSPACES
import com.madanala.tern.utils.AirspaceCache
import com.madanala.tern.utils.CountryUtils
import com.madanala.tern.utils.GeoJsonUtils
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Overlay manager for airspace data
 * Handles loading, caching, and rendering of airspace polygons
 */
class AirspaceOverlayManager(
    private val applicationContext: Context,
    mapStore: MapStore
) : BaseOverlayManager(OverlayType.AIRSPACE, mapStore) {

    private val airspaceCache = AirspaceCache(applicationContext)

    // State management (extracted from MapViewModel)
    private var currentCountryCode: String? = null
    private var lastCheckLocation: GeoPoint? = null
    private var loadingJob: Job? = null

    // Track currently rendered airspaces for incremental updates
    private val currentlyRenderedAirspaces = mutableMapOf<String, org.osmdroid.views.overlay.Polygon>()

    // Thresholds for loading (from MapViewModel constants)
    private val checkDistanceKm = 5.0
    private val filterRadiusMiles = 300.0
    private val reFilterDistanceKm = 80.0

    // Performance limits for resource management - configurable value imported

    override fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "AirspaceOverlayManager setEnabled: $enabled")
    }

    override fun updateConfig(config: com.madanala.tern.redux.OverlayConfig) {
        Log.d(TAG, "AirspaceOverlayManager updateConfig: $config")
    }

    override fun onOverlayAttached() {
        Log.d(TAG, "Airspace overlay manager attached")
    }

    override fun onOverlayDetached() {
        Log.d(TAG, "Airspace overlay manager detached")

        // Cancel any ongoing loading jobs
        loadingJob?.cancel()
        loadingJob = null

        // Clear state and tracking
        currentCountryCode = null
        lastCheckLocation = null
        currentlyRenderedAirspaces.clear()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        if (!isEnabled()) {
            Log.d(TAG, "Airspaces disabled, skipping map move handling")
            return
        }

        // Check if we've moved far enough to warrant a reload
        lastCheckLocation?.let { lastLocation ->
            val distance = lastLocation.distanceToAsDouble(center)
            val distanceKm = distance / 1000.0

            if (distanceKm < checkDistanceKm) {
                Log.d(TAG, String.format("Not moved far enough (%.1f km < %.1f km), skipping reload",
                    distanceKm, checkDistanceKm))
                return
            }
        }

        Log.d(TAG, "Map moved significantly, checking airspace state")
        checkAndLoadAirspaceData(center)
    }

    override fun onViewportChangedInternal(viewport: BoundingBox) {
        Log.d(TAG, "Viewport changed: $viewport")
        manageViewportAirspaces(viewport)
    }

    override fun onReduxStateChanged(state: MapState) {
        val enabled = isEnabled()
        Log.d(TAG, "Redux state changed, airspaces enabled: $enabled")

        if (!enabled) {
            // Clear airspaces if disabled
            clearOverlays()
            currentCountryCode = null
            lastCheckLocation = null
        } else if (mapView != null) {
            // If enabled and we have a map view, trigger loading
            val center = mapView!!.mapCenter as GeoPoint
            checkAndLoadAirspaceData(center)
        }
    }

    override fun clearOverlays() {
        // Clear ONLY airspace overlays (not all GeoJSON)
        mapView?.overlays?.removeAll { overlay ->
            // Remove Polygon overlays that represent airspaces
            // Note: This is a simplified approach - in production we'd add metadata
            // to distinguish overlay types more reliably
            overlay is org.osmdroid.views.overlay.Polygon
        }
        mapView?.invalidate()

        // Clear tracking map
        currentlyRenderedAirspaces.clear()

        Log.d(TAG, "Cleared airspace overlays")
    }

    /**
     * Check and load airspace data for the given location
     */
    private fun checkAndLoadAirspaceData(center: GeoPoint) {
        if (mapView == null) {
            Log.w(TAG, "No map view available for airspace loading")
            return
        }

        // Cancel any existing loading job
        loadingJob?.cancel()

        // Start new loading job
        loadingJob = coroutineScope.launch {
            loadAirspaceForLocation(applicationContext, center)
        }
    }

    /**
     * Load airspace data for a specific location (extracted from MapViewModel)
     */
    private suspend fun loadAirspaceForLocation(context: Context, center: GeoPoint) {
        try {
            Log.d(TAG, "Loading airspaces for location: ${center.latitude}, ${center.longitude}")

            // Get country code for current location
            val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, center)
            if (countryCode == null) {
                Log.w(TAG, "Could not determine country code for location: $center")
                return
            }

            Log.d(TAG, "Country code: $countryCode")

            // Check if we already have this country's data loaded
            if (countryCode == currentCountryCode) {
                // Check if we've moved far enough to warrant re-filtering
                lastCheckLocation?.let { lastLocation ->
                    val distance = lastLocation.distanceToAsDouble(center)
                    val distanceKm = distance / 1000.0

                    if (distanceKm > reFilterDistanceKm) {
                        Log.d(TAG, String.format("Moved far enough (%.1f km > %.1f km), re-filtering airspaces",
                            distanceKm, reFilterDistanceKm))

                        // Query nearby features using Hilbert index
                        val nearbyFeatures = airspaceCache.queryNearbyFeatures(countryCode, center, filterRadiusMiles)
                        if (nearbyFeatures.isNotEmpty()) {
                            renderAirspaceFeatures(nearbyFeatures)
                        } else {
                            Log.d(TAG, "No nearby airspaces after re-filtering")
                        }

                        // Update the last check location
                        lastCheckLocation = center
                        return
                    } else {
                        Log.d(TAG, "Still within same airspace area, no reload needed")
                        return
                    }
                }
            }

            Log.d(TAG, "Loading airspaces for new country: $countryCode")

            // Clear all rendered airspaces when switching countries
            currentlyRenderedAirspaces.clear()

            // Try to load from cache first (airspaces cached for 30 days)
            var features = airspaceCache.getCachedFeatures(countryCode)

            if (features == null) {
                // Download from OpenAIP
                val url = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/${countryCode}_asp.ndgeojson"
                val ndGeoJsonString = GeoJsonUtils.downloadGeoJson(url)

                if (ndGeoJsonString != null) {
                    Log.d(TAG, "Downloaded airspace data for $countryCode (${ndGeoJsonString.length} bytes)")
                    // Cache the downloaded data
                    airspaceCache.cacheData(countryCode, ndGeoJsonString)
                    features = airspaceCache.getCachedFeatures(countryCode)
                } else {
                    Log.w(TAG, "Failed to download airspace data for $countryCode")
                    return
                }
            } else {
                Log.d(TAG, "Loaded cached airspaces for $countryCode (${features.size} features)")
            }

            if (features != null && features.isNotEmpty()) {
                // Query nearby features and render them
                val nearbyFeatures = airspaceCache.queryNearbyFeatures(countryCode, center, filterRadiusMiles)
                Log.d(TAG, "Filtered ${nearbyFeatures.size} nearby airspaces out of ${features.size} total")

                if (nearbyFeatures.isNotEmpty()) {
                    renderAirspaceFeatures(nearbyFeatures)
                }

                // Update current country and last location
                currentCountryCode = countryCode
                lastCheckLocation = center

            } else {
                Log.w(TAG, "No airspace data available for $countryCode")
            }

        } catch (e: CancellationException) {
            // Expected behavior when cancelling old requests due to debounce
            Log.d(TAG, "Airspace loading cancelled due to user interaction")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading airspace data", e)
        }
    }

    /**
     * Render airspace features to the map (incremental updates)
     */
    private fun renderAirspaceFeatures(features: List<OverlayFeature>) {
        mapView?.let { map ->
            Log.d(TAG, "Rendering ${features.size} airspace features (incremental update)")

            // Calculate incremental updates - add/remove only what's changed
            updateAirspaceOverlaysIncrementally(map, features)

            Log.d(TAG, "Successfully updated airspaces incrementally")
        } ?: Log.w(TAG, "Cannot render airspaces - no map view available")
    }

    /**
     * Update airspace overlays incrementally instead of clearing everything
     */
    private fun updateAirspaceOverlaysIncrementally(map: MapView, features: List<OverlayFeature>) {
        // Create a map of desired airspaces by ID
        val desiredAirspaceIds = features.mapNotNull { feature ->
            generateAirspaceId(feature) to feature
        }.toMap()

        Log.d(TAG, "Desired airspaces: ${desiredAirspaceIds.size}, Currently rendered: ${currentlyRenderedAirspaces.size}")

        // Find airspaces to remove (exist in current but not in desired)
        val airspacesToRemove = currentlyRenderedAirspaces.keys - desiredAirspaceIds.keys
        airspacesToRemove.forEach { airspaceId ->
            val polygon = currentlyRenderedAirspaces[airspaceId]
            if (polygon != null) {
                map.overlays.remove(polygon)
                currentlyRenderedAirspaces.remove(airspaceId)
                Log.v(TAG, "Removed airspace: $airspaceId")
            }
        }

        // Find airspaces to add (exist in desired but not in current)
        val airspacesToAdd = desiredAirspaceIds.keys - currentlyRenderedAirspaces.keys
        airspacesToAdd.forEach { airspaceId ->
            val feature = desiredAirspaceIds[airspaceId]
            if (feature != null) {
                // Create overlay for single airspace
                val overlays = GeoJsonUtils.addAirspaceFeaturesToMap(map, listOf(feature))
                if (overlays.isNotEmpty()) {
                    // Assume first overlay is the airspace polygon
                    currentlyRenderedAirspaces[airspaceId] = overlays.first()
                    Log.v(TAG, "Added airspace: $airspaceId")
                }
            }
        }

        // Airspaces to keep (don't need any action)

        if (airspacesToRemove.isNotEmpty() || airspacesToAdd.isNotEmpty()) {
            map.invalidate()
            Log.d(TAG, "Airspace update: +${airspacesToAdd.size} -${airspacesToRemove.size} =${currentlyRenderedAirspaces.size} total")
        } else {
            Log.d(TAG, "No airspace changes needed")
        }
    }

    /**
     * Generate a unique identifier for an airspace feature
     */
    private fun generateAirspaceId(feature: OverlayFeature): String {
        // Try to use airspace name first
        val properties = feature.feature["properties"] as? Map<*, *>
        val name = properties?.get("name") as? String ?: properties?.get("Name") as? String

        if (!name.isNullOrBlank()) {
            return "airspace_$name"
        }

        // Fallback: generate ID from first coordinate
        val geometry = feature.feature["geometry"] as? Map<*, *>
        val coordinates = geometry?.get("coordinates") as? List<*>
        val outerRing = coordinates?.get(0) as? List<List<Double>>

        return if (outerRing != null && outerRing.isNotEmpty()) {
            val firstCoord = outerRing[0]
            String.format("airspace_coord_%.6f_%.6f", firstCoord[1], firstCoord[0])
        } else {
            // Fallback to Hilbert index as unique identifier
            "airspace_hilbert_${feature.hilbertIndex}"
        }
    }

    /**
     * Manage airspaces based on viewport changes
     * Only removes airspaces that are truly out of view
     */
    private fun manageViewportAirspaces(viewport: BoundingBox) {
        if (currentlyRenderedAirspaces.isEmpty()) {
            Log.d(TAG, "No airspaces to manage for viewport")
            return
        }

        // Expand viewport slightly for hysteresis (don't remove airspaces right at the edge)
        val expandedViewport = BoundingBox(
            viewport.latNorth + 0.01, // Add ~1km buffer
            viewport.lonEast + 0.01,
            viewport.latSouth - 0.01,
            viewport.lonWest - 0.01
        )

        Log.d(TAG, "Managing ${currentlyRenderedAirspaces.size} airspaces for viewport: $expandedViewport")

        // Find airspaces to remove (completely outside viewport)
        val airspacesToRemove = mutableListOf<String>()
        currentlyRenderedAirspaces.forEach { (airspaceId, polygon) ->
            if (!isPolygonInViewport(polygon, expandedViewport)) {
                airspacesToRemove.add(airspaceId)
            }
        }

        // If we still have too many airspaces, remove the farthest from center
        if (currentlyRenderedAirspaces.size - airspacesToRemove.size > MAX_VISIBLE_AIRSPACES) {
            val remainingAirspaces = currentlyRenderedAirspaces.keys - airspacesToRemove
            if (remainingAirspaces.size > MAX_VISIBLE_AIRSPACES) {
                val center = mapView?.mapCenter as? GeoPoint ?: return
                val airspacesByDistance = remainingAirspaces.sortedBy { airspaceId ->
                    val polygon = currentlyRenderedAirspaces[airspaceId] ?: return@sortedBy Double.MAX_VALUE
                    getDistanceFromCenter(polygon, center)
                }

                // Keep only the closest MAX_VISIBLE_AIRSPACES
                val extraToRemove = airspacesByDistance.drop(MAX_VISIBLE_AIRSPACES)
                airspacesToRemove.addAll(extraToRemove)
            }
        }

        // Remove the determined airspaces
        if (airspacesToRemove.isNotEmpty()) {
            mapView?.let { map ->
                airspacesToRemove.forEach { airspaceId ->
                    val polygon = currentlyRenderedAirspaces[airspaceId]
                    if (polygon != null) {
                        map.overlays.remove(polygon)
                        currentlyRenderedAirspaces.remove(airspaceId)
                        Log.v(TAG, "Removed out-of-view airspace: $airspaceId")
                    }
                }

                if (airspacesToRemove.isNotEmpty()) {
                    map.invalidate()
                    Log.d(TAG, "Viewport cleanup: removed ${airspacesToRemove.size}, remaining: ${currentlyRenderedAirspaces.size}")
                }
            }
        } else {
            Log.d(TAG, "All ${currentlyRenderedAirspaces.size} airspaces still in viewport")
        }
    }

    /**
     * Check if a polygon is visible in the current viewport
     */
    private fun isPolygonInViewport(polygon: org.osmdroid.views.overlay.Polygon, viewport: BoundingBox): Boolean {
        // Check if any vertex of the polygon is within the viewport
        @Suppress("DEPRECATION")
        polygon.points?.forEach { point ->
            if (point != null && viewport.contains(point)) {
                return true
            }
        }
        return false
    }

    /**
     * Get approximate distance from polygon center to screen center
     */
    private fun getDistanceFromCenter(polygon: org.osmdroid.views.overlay.Polygon, center: GeoPoint): Double {
        // Use polygon center or first point as approximation
        @Suppress("DEPRECATION")
        val polygonCenter = polygon.points?.firstOrNull() ?: return Double.MAX_VALUE
        return center.distanceToAsDouble(polygonCenter)
    }

    /**
     * Get airspace cache statistics for debugging
     */
    fun getCacheStats() = airspaceCache.getCacheStats()

    /**
     * Example: Overlay lifecycle callbacks (for demonstration)
     * If you wanted real-time visibility events, you could use this approach
     */
    fun createVisibilityAwarePolygon(feature: OverlayFeature): org.osmdroid.views.overlay.Polygon {
        val polygon = org.osmdroid.views.overlay.Polygon().apply {
            // Store the airspace ID and original feature for callbacks
            title = generateAirspaceId(feature)
            relatedObject = feature
        }

        // TODO: Override draw() method to detect visibility changes
        // This would be called whenever MapView attempts to draw the polygon
        /*
        polygon.setDrawCallback { canvas, mapView ->
            val boundingBox = mapView.getBoundingBox()
            val polygonBounds = polygon.bounds

            if (polygonBounds != null) {
                if (boundingBox.intersects(polygonBounds)) {
                    // Polygon is entering view
                    onAirspaceVisible(feature)
                } else {
                    // Polygon is leaving view
                    onAirspaceHidden(feature)
                }
            }
        }
        */

        return polygon
    }

    /**
     * Example callbacks for airspace visibility events
     */
    private fun onAirspaceVisible(feature: OverlayFeature) {
        Log.v(TAG, "Airspace entered view: ${generateAirspaceId(feature)}")
        // Add custom logic here: preload airspace data, update cache, trigger events, etc.
    }

    fun onAirspaceHidden(feature: OverlayFeature) {
        Log.v(TAG, "Airspace left view: ${generateAirspaceId(feature)}")
        // Add custom logic here: free memory, cancel operations, cache data, etc.
    }
}
