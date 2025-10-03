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
    private val reFilterDistanceKm = 80.0

    // Intelligent limits to prevent memory pressure crashes
    private val maxTotalAirspaces = 150       // Hard limit to prevent ANR
    private val maxAirspacesPerZone = 50      // Limit per zone loading
    private val maxViewportAirspaces = 100    // Most critical - what's actually visible

    // More conservative zone-to-radius mapping
    // Reduced radii and added proper limits to prevent memory explosion
    private val zoneFilterRadii = mapOf(
        ViewportLoadingManager.LoadingZone.VIEWPORT_VISIBLE to 15.0,   // Even smaller for memory safety
        ViewportLoadingManager.LoadingZone.NEAR_VIEWPORT to 50.0,      // Reduced - still good for panning
        ViewportLoadingManager.LoadingZone.FAR_VIEWPORT to 100.0,      // Reduced - cache farther away opportunistically
        ViewportLoadingManager.LoadingZone.OFFSCREEN to 0.0            // Never load
    )

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
     * Load airspace data for a specific location using viewport-zone intelligence
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
                // Use viewport-zone intelligence to load the right amount of data
                val nearbyFeatures = loadZoneSpecificFeatures(countryCode, center, features)

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
     * Load airspace features using viewport-zone intelligence instead of fixed radius
     * ENFORCES HARD MEMORY LIMITS to prevent ANR crashes
     */
    private fun loadZoneSpecificFeatures(countryCode: String, center: GeoPoint, allFeatures: List<OverlayFeature>): List<OverlayFeature> {
        // Determine current viewport for zone calculations
        val viewport = mapView?.boundingBox ?: return emptyList()

        // Calculate which zones we need to query
        val activeZones = determineActiveLoadingZones(viewport)

        // Query data for each active zone with appropriate radius, enforcing per-zone limits
        val allZoneFeatures = mutableListOf<OverlayFeature>()
        var totalAdded = 0

        for (zone in activeZones) {
            val radius = zoneFilterRadii[zone] ?: continue
            if (radius <= 0.0) continue // Skip off-screen zones

            Log.d(TAG, "Querying zone $zone with radius ${radius}mi from center (limit: $maxAirspacesPerZone)")
            val zoneFeatures = airspaceCache.queryNearbyFeatures(countryCode, center, radius)

            // Enforce per-zone limit to prevent memory explosion
            val limitedZoneFeatures = if (zoneFeatures.size > maxAirspacesPerZone) {
                Log.w(TAG, "Zone $zone exceeded limit (${zoneFeatures.size} > $maxAirspacesPerZone), trimming")
                zoneFeatures.take(maxAirspacesPerZone)
            } else {
                zoneFeatures
            }

            // Check if adding these would exceed total limit
            if (totalAdded + limitedZoneFeatures.size > maxTotalAirspaces) {
                val remainingSlots = maxTotalAirspaces - totalAdded
                if (remainingSlots <= 0) break // No more room

                Log.w(TAG, "Zone $zone would exceed total limit (${limitedZoneFeatures.size} would make ${totalAdded + limitedZoneFeatures.size} > $maxTotalAirspaces), taking $remainingSlots")
                allZoneFeatures.addAll(limitedZoneFeatures.take(remainingSlots))
                totalAdded += remainingSlots
                break // Stop loading more zones
            } else {
                allZoneFeatures.addAll(limitedZoneFeatures)
                totalAdded += limitedZoneFeatures.size
            }

            Log.d(TAG, "Zone $zone contributed ${limitedZoneFeatures.size} features (total so far: $totalAdded)")
        }

        // Remove duplicates within the allocated limit
        val deduplicatedFeatures = allZoneFeatures.distinctBy { generateAirspaceId(it) }

        // Final hard limit enforcement
        val finalFeatures = if (deduplicatedFeatures.size > maxTotalAirspaces) {
            Log.w(TAG, "Hard limit exceeded (${deduplicatedFeatures.size} > $maxTotalAirspaces), enforcing limit")
            deduplicatedFeatures.take(maxTotalAirspaces)
        } else {
            deduplicatedFeatures
        }

        Log.d(TAG, "Final airspace load: ${finalFeatures.size} features (total airspace limit: $maxTotalAirspaces)")

        if (finalFeatures.size >= maxTotalAirspaces) {
            Log.w(TAG, "⚠️ WARNING: Reached maximum airspace capacity (${maxTotalAirspaces}). Consider zooming in to reduce airspace count.")
        }

        return finalFeatures
    }

    /**
     * Determine which loading zones should be active based on current state
     */
    private fun determineActiveLoadingZones(viewport: BoundingBox): List<ViewportLoadingManager.LoadingZone> {
        // For initial implementation, load from all zones except OFFSCREEN
        // This ensures complete coverage while building toward more intelligent zone selection
        return listOf(
            ViewportLoadingManager.LoadingZone.VIEWPORT_VISIBLE,
            ViewportLoadingManager.LoadingZone.NEAR_VIEWPORT,
            ViewportLoadingManager.LoadingZone.FAR_VIEWPORT
        )
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
        if (isPointInPolygon(viewportCenter, points)) {
            return true
        }

        return false
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
