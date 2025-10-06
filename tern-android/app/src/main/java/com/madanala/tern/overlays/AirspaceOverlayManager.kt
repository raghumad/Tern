package com.madanala.tern.overlays

import android.content.Context
import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.utils.CacheManager
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
    mapStore: MapStore?
) : BaseOverlayManager(OverlayType.AIRSPACE, mapStore) {

    // Use singleton cache instance to prevent duplicate downloads
    private val airspaceCache = CacheManager.airspaceCache

    // Universal country cache manager for intelligent country management (Priority 0 fix)
    private var countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager? = null

    // State management (extracted from MapViewModel)
    private var currentCountryCode: String? = null
    private var lastCheckLocation: GeoPoint? = null
    private var loadingJob: Job? = null

    // Track currently rendered airspaces for incremental updates
    private val currentlyRenderedAirspaces = mutableMapOf<String, org.osmdroid.views.overlay.Polygon>()

    // Aviation-optimized thresholds for movement and loading
    private val checkDistanceKm = 2.0  // Reduced for aviation use (was 5.0)
    private val reFilterDistanceKm = 80.0

    // Intelligent limits to prevent memory pressure crashes
    private val maxTotalAirspaces = 150       // Hard limit to prevent ANR
    private val maxViewportAirspaces = 100    // Most critical - what's actually visible

    // Spatial-first architecture: always use spatial queries with configurable search radius
    private val spatialQueryRadiusKm = 100.0  // Search radius for nearby airspaces (was FAR_VIEWPORT)

    override fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "AirspaceOverlayManager setEnabled: $enabled")
    }

    override fun updateConfig(config: com.madanala.tern.redux.OverlayConfig) {
        Log.d(TAG, "AirspaceOverlayManager updateConfig: $config")
    }

    override fun onOverlayAttached() {
        Log.d(TAG, "Airspace overlay manager attached")

        // Get universal country cache manager from overlay coordinator
        // Note: This would need to be passed from the coordinator in real implementation
        // For now, we'll use a simplified approach
    }

    /**
     * Set the universal country cache manager (called by OverlayCoordinator)
     */
    fun setCountryCacheManager(countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager) {
        this.countryCacheManager = countryCacheManager
        Log.d(TAG, "Universal country cache manager connected")
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

        // Postpone overlay operations until GPS fix is available
        if (!hasValidGPSFix) {
            Log.d(TAG, "No GPS fix yet, postponing airspace loading until GPS coordinates are available")
            return
        }

        // Additional coordinate validation for safety
        if (center.latitude < -90.0 || center.latitude > 90.0 || center.longitude < -180.0 || center.longitude > 180.0) {
            Log.w(TAG, "Coordinates out of valid range: lat=${center.latitude}, lon=${center.longitude}")
            return
        }

        // Additional validation for reasonable coordinate ranges
        if (center.latitude < -90.0 || center.latitude > 90.0 || center.longitude < -180.0 || center.longitude > 180.0) {
            Log.w(TAG, "Coordinates out of valid range: lat=${center.latitude}, lon=${center.longitude}")
            return
        }

        // Check if we've moved far enough to warrant a reload
        lastCheckLocation?.let { lastLocation ->
            val distance = lastLocation.distanceToAsDouble(center)
            val distanceKm = distance / 1000.0

            if (distanceKm < checkDistanceKm) {
                Log.v(TAG, String.format("Not moved far enough (%.1f km < %.1f km), skipping reload",
                    distanceKm, checkDistanceKm))
                return
            }
        }

        Log.d(TAG, "Map moved significantly, checking airspace state")
        checkAndLoadAirspaceData(center)
    }

    override fun onViewportChanged(viewport: BoundingBox) {
        Log.d(TAG, "Viewport changed: $viewport")
        onViewportChangedInternal(viewport)
    }

    override fun onViewportChangedInternal(viewport: BoundingBox) {
        Log.d(TAG, "Viewport changed internal: $viewport")
        manageViewportAirspaces(viewport)
    }

    override fun onReduxStateChanged(state: MapState) {
        val enabled = isEnabled()
        Log.v(TAG, "Redux state changed, airspaces enabled: $enabled")

        if (!enabled) {
            // Clear airspaces if disabled
            clearOverlays()
            currentCountryCode = null
            lastCheckLocation = null
        } else if (mapView != null) {
            // Postpone Redux-triggered overlay operations until GPS fix is available
            if (!hasValidGPSFix) {
                Log.d(TAG, "Postponing Redux-triggered airspace loading until GPS fix")
                return
            }

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
     * Load airspace data for a specific location using universal country management
     * Now uses UniversalCountryCacheManager for intelligent multi-country handling
     */
    private suspend fun loadAirspaceForLocation(context: Context, center: GeoPoint) {
        try {
            Log.v(TAG, "Loading airspaces for location: ${center.latitude}, ${center.longitude}")

            // Use universal country cache manager for intelligent country management
            countryCacheManager?.let { countryCache ->
                // Query multiple countries intelligently using the universal cache manager
                val nearbyFeatures = countryCache.queryMultiCountryArea(center, spatialQueryRadiusKm)

                if (nearbyFeatures.isNotEmpty()) {
                    renderAirspaceFeatures(nearbyFeatures)

                    // Update last location for movement tracking
                    lastCheckLocation = center

                    Log.v(TAG, "Rendered ${nearbyFeatures.size} nearby airspaces from multiple countries")
                } else {
                    Log.d(TAG, "No airspaces found near $center in cached countries")

                    // Fallback to single country approach if universal cache not available
                    loadSingleCountryAirspace(context, center)
                }
            } ?: run {
                // Fallback if universal cache manager not available
                Log.w(TAG, "Universal country cache manager not available, using fallback")
                loadSingleCountryAirspace(context, center)
            }

        } catch (e: CancellationException) {
            // Expected behavior when cancelling old requests due to debounce
            Log.d(TAG, "Airspace loading cancelled due to user interaction")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading airspace data", e)
        }
    }

    /**
     * Fallback method for single country loading (original logic)
     */
    private suspend fun loadSingleCountryAirspace(context: Context, center: GeoPoint) {
        try {
            // Get country code for current location (original approach)
            val countryCode = CountryUtils.getCountryCodeFromGeoPoint(context, center)
            if (countryCode == null) {
                Log.w(TAG, "Could not determine country code for location: $center")
                return
            }

            Log.v(TAG, "Country code (fallback): $countryCode")

            // Check if country data is cached (original spatial-first architecture)
            if (!airspaceCache.isCached(countryCode)) {
                // Download and cache using spatial indexing (original approach)
                val url = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/${countryCode}_asp.ndgeojson"
                val ndGeoJsonString = GeoJsonUtils.downloadGeoJson(url)

                if (ndGeoJsonString != null) {
                    Log.d(TAG, "Downloaded airspace data for $countryCode (${ndGeoJsonString.length} bytes)")
                    // Cache with Hilbert spatial indexing (original approach)
                    airspaceCache.cacheData(countryCode, ndGeoJsonString)
                } else {
                    Log.w(TAG, "Failed to download airspace data for $countryCode")
                    return
                }
            } else {
                Log.v(TAG, "Using cached airspace data for $countryCode (fallback)")
            }

            // Always use spatial query - never load entire countries (original approach)
            val nearbyFeatures = airspaceCache.queryNearbyFeatures(countryCode, center, spatialQueryRadiusKm)

            if (nearbyFeatures.isNotEmpty()) {
                renderAirspaceFeatures(nearbyFeatures)

                // Update current country and last location (original tracking)
                currentCountryCode = countryCode
                lastCheckLocation = center

                Log.v(TAG, "Rendered ${nearbyFeatures.size} nearby airspaces for $countryCode (fallback)")
            } else {
                Log.d(TAG, "No airspaces found near $center for $countryCode (fallback)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in fallback airspace loading", e)
        }
    }



    /**
     * Render airspace features to the map (incremental updates)
     */
    private fun renderAirspaceFeatures(features: List<OverlayFeature>) {
        mapView?.let { map ->
            Log.v(TAG, "Rendering ${features.size} airspace features (incremental update)")

            // Calculate incremental updates - add/remove only what's changed
            updateAirspaceOverlaysIncrementally(map, features)

            Log.v(TAG, "Successfully updated airspaces incrementally")
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

        Log.v(TAG, "Desired airspaces: ${desiredAirspaceIds.size}, Currently rendered: ${currentlyRenderedAirspaces.size}")

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
            Log.v(TAG, "Airspace update: +${airspacesToAdd.size} -${airspacesToRemove.size} =${currentlyRenderedAirspaces.size} total")
        } else {
            Log.v(TAG, "No airspace changes needed")
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

        // ENFORCE HARD MEMORY LIMITS to prevent ANR crashes
        val predictedFinalCount = currentlyRenderedAirspaces.size - airspacesToRemove.size

        // If we would still exceed the hard limit after viewport cleanup, remove more aggressively
        if (predictedFinalCount > maxTotalAirspaces) {
            Log.w(TAG, "Memory pressure detected: ${predictedFinalCount} > ${maxTotalAirspaces} (hard limit)")
            val remainingAirspaces = currentlyRenderedAirspaces.keys - airspacesToRemove
            val center = mapView?.mapCenter as? GeoPoint ?: return

            // Sort by distance from center and keep only the closest ones within limit
            val airspacesByDistance = remainingAirspaces.sortedBy { airspaceId ->
                val polygon = currentlyRenderedAirspaces[airspaceId] ?: return@sortedBy Double.MAX_VALUE
                getDistanceFromCenter(polygon, center)
            }

            // Keep only the closest maxTotalAirspaces (hard limit)
            val extraToRemove = airspacesByDistance.drop(maxTotalAirspaces)
            airspacesToRemove.addAll(extraToRemove)
            Log.w(TAG, "Aggressive cleanup: removed ${extraToRemove.size} airspaces to reach ${maxTotalAirspaces} limit")
        }
        // Check for viewport-specific limits too
        else if (predictedFinalCount > maxViewportAirspaces) {
            val remainingAirspaces = currentlyRenderedAirspaces.keys - airspacesToRemove
            val center = mapView?.mapCenter as? GeoPoint ?: return

            // Enforce viewport limit by removing farthest airspaces
            val airspacesByDistance = remainingAirspaces.sortedBy { airspaceId ->
                val polygon = currentlyRenderedAirspaces[airspaceId] ?: return@sortedBy Double.MAX_VALUE
                getDistanceFromCenter(polygon, center)
            }

            // Keep only the maxViewportAirspaces closest ones
            val viewportToRemove = airspacesByDistance.drop(maxViewportAirspaces)
            airspacesToRemove.addAll(viewportToRemove)
            Log.d(TAG, "Viewport limit enforcement: kept ${maxViewportAirspaces} closest airspaces")
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
     * Improved algorithm that considers polygon-to-viewport relationships more robustly
     */
    private fun isPolygonInViewport(polygon: org.osmdroid.views.overlay.Polygon, viewport: BoundingBox): Boolean {
        @Suppress("DEPRECATION")
        val points = polygon.points ?: return false

        // Count vertices inside viewport for better accuracy
        var verticesInside = 0
        var totalVertices = 0

        points.forEach { point ->
            if (point != null) {
                totalVertices++
                if (viewport.contains(point)) {
                    verticesInside++
                }
            }
        }

        // Consider partially visible if:
        // 1. Any vertex is inside viewport (intersection)
        if (verticesInside > 0) return true

        // 2. Polygon spans viewport boundaries (viewport is completely inside polygon)
        // 3. Check viewport corners to see if they fall inside polygon
        val viewportCorners = listOf(
            GeoPoint(viewport.latNorth, viewport.lonWest),   // Top-left
            GeoPoint(viewport.latNorth, viewport.lonEast),   // Top-right
            GeoPoint(viewport.latSouth, viewport.lonEast),   // Bottom-right
            GeoPoint(viewport.latSouth, viewport.lonWest)    // Bottom-left
        )

        // If any viewport corner is inside polygon, it's visible
        for (corner in viewportCorners) {
            if (isPointInPolygon(corner, points)) {
                return true
            }
        }

        // For complex polygons, check viewport center as fallback
        val viewportCenter = GeoPoint(
            (viewport.latNorth + viewport.latSouth) / 2.0,
            (viewport.lonEast + viewport.lonWest) / 2.0
        )
        return isPointInPolygon(viewportCenter, points)
    }

    /**
     * Check if a point is inside a polygon using ray casting algorithm
     */
    private fun isPointInPolygon(point: GeoPoint, polygonPoints: List<GeoPoint>): Boolean {
        // Ray casting algorithm: count how many times a ray from point intersects polygon edges
        var inside = false
        val x = point.longitude
        val y = point.latitude

        var j = polygonPoints.lastIndex
        for (i in polygonPoints.indices) {
            val xi = polygonPoints[i].longitude
            val yi = polygonPoints[i].latitude
            val xj = polygonPoints[j].longitude
            val yj = polygonPoints[j].latitude

            // Check if point crosses edge
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }

        return inside
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
