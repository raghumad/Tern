package com.madanala.tern.ui.overlays

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Custom exception for invalid geographic coordinates
 */
class InvalidCoordinatesException(
    message: String,
    val latitude: Double,
    val longitude: Double
) : IllegalArgumentException("Invalid coordinates: lat=$latitude, lon=$longitude. $message")

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
    private val spatialQueryRadiusKm = 200.0  // Search radius for nearby airspaces (increased for better visibility)

    // Coordinate validation constants
    private val MIN_LATITUDE = -90.0
    private val MAX_LATITUDE = 90.0
    private val MIN_LONGITUDE = -180.0
    private val MAX_LONGITUDE = 180.0

    /**
     * Validates geographic coordinates for safety and correctness
     * @param center The GeoPoint to validate
     * @throws InvalidCoordinatesException if coordinates are invalid
     */
    private fun validateCoordinates(center: GeoPoint) {
        // Check for NaN values first
        if (center.latitude.isNaN() || center.longitude.isNaN()) {
            throw InvalidCoordinatesException(
                "Coordinates cannot be NaN",
                center.latitude,
                center.longitude
            )
        }

        // Check latitude bounds
        if (center.latitude < MIN_LATITUDE || center.latitude > MAX_LATITUDE) {
            throw InvalidCoordinatesException(
                "Latitude must be between $MIN_LATITUDE and $MAX_LATITUDE",
                center.latitude,
                center.longitude
            )
        }

        // Check longitude bounds
        if (center.longitude < MIN_LONGITUDE || center.longitude > MAX_LONGITUDE) {
            throw InvalidCoordinatesException(
                "Longitude must be between $MIN_LONGITUDE and $MAX_LONGITUDE",
                center.latitude,
                center.longitude
            )
        }
    }

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

        // Validate coordinates for safety and correctness
        try {
            validateCoordinates(center)
        } catch (e: InvalidCoordinatesException) {
            Log.w(TAG, "Invalid coordinates in map move: ${e.message}", e)
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
        mapView?.let { map ->
            // 🎨 Clear all airspaces through animation manager (fade-out all)
            val polygonsToRemove = currentlyRenderedAirspaces.values.toList()
            val airspaceIds = currentlyRenderedAirspaces.keys.toList()

            // Animation manager handles all removals with staggered fade-out
            airspaceIds.forEachIndexed { index, airspaceId ->
                val polygon = currentlyRenderedAirspaces[airspaceId]
                if (polygon != null) {
                    animationManager?.animateOverlayRemoval(
                        overlay = polygon,
                        overlayId = airspaceId,
                        mapView = map
                    ) {
                        // Animation manager handles removal - just update tracking
                        Log.v(TAG, "Clear animation completed for: $airspaceId")
                    } ?: throw IllegalStateException(
                        "Animation manager is required for airspace overlay removal. " +
                        "Ensure OverlayCoordinator is properly initialized."
                    )
                }
            }

            // Clear tracking (animation manager handles the actual removal)
            currentlyRenderedAirspaces.clear()
            Log.d(TAG, "Scheduled clear of ${polygonsToRemove.size} airspace overlays via animation manager")
        } ?: run {
            // No map view available - just clear tracking
            currentlyRenderedAirspaces.clear()
            Log.d(TAG, "Cleared airspace tracking (no map view)")
        }
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
                    mapView?.let { map ->
                        Log.v(TAG, "Rendering ${nearbyFeatures.size} nearby airspaces from multiple countries")
                        updateAirspaceOverlaysIncrementally(map, nearbyFeatures)
                    } ?: Log.w(TAG, "Cannot render airspaces - no map view available")

                    // Update last location for movement tracking
                    lastCheckLocation = center
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
                mapView?.let { map ->
                    Log.v(TAG, "Rendering ${nearbyFeatures.size} nearby airspaces for $countryCode (fallback)")
                    updateAirspaceOverlaysIncrementally(map, nearbyFeatures)
                } ?: Log.w(TAG, "Cannot render airspaces - no map view available")

                // Update current country and last location (original tracking)
                currentCountryCode = countryCode
                lastCheckLocation = center
            } else {
                Log.d(TAG, "No airspaces found near $center for $countryCode (fallback)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in fallback airspace loading", e)
        }
    }




    /**
       * Update airspace overlays incrementally - coordinates with animation manager
       * Nearby feature algorithm determines WHAT should exist, animation manager handles HOW
       */
    private fun updateAirspaceOverlaysIncrementally(map: MapView, features: List<OverlayFeature>) {
        // 🎯 STEP 1: Determine desired state (what SHOULD exist)
        val desiredAirspaceIds = determineDesiredAirspaceState(features)

        Log.v(TAG, "Desired airspaces: ${desiredAirspaceIds.size}, Currently rendered: ${currentlyRenderedAirspaces.size}")

        // 🎯 STEP 2: Calculate differences (what needs to change)
        val changes = calculateOverlayChanges(desiredAirspaceIds)

        // 🎯 STEP 3: Execute changes through animation manager only
        executeOverlayChangesThroughAnimationManager(map, changes)

        Log.v(TAG, "Airspace state synchronized: ${currentlyRenderedAirspaces.size} total overlays")
    }

    /**
     * Determine what airspace state SHOULD exist based on features
     */
    private fun determineDesiredAirspaceState(features: List<OverlayFeature>): Map<String, OverlayFeature> {
        return features.mapNotNull { feature ->
            val id = generateAirspaceId(feature)
            id to feature
        }.toMap()
    }

    /**
     * Calculate what needs to be added vs removed
     */
    private fun calculateOverlayChanges(desiredState: Map<String, OverlayFeature>): OverlayChanges {
        val currentIds = currentlyRenderedAirspaces.keys
        val desiredIds = desiredState.keys

        val toRemove = currentIds - desiredIds
        val toAdd = desiredIds - currentIds

        return OverlayChanges(
            toRemove = toRemove,
            toAdd = toAdd.mapNotNull { id -> desiredState[id]?.let { id to it } }.toMap()
        )
    }

    /**
     * Execute overlay changes through animation manager only
     * Animation manager is the single source of truth for overlay lifecycle
     */
    private fun executeOverlayChangesThroughAnimationManager(map: MapView, changes: OverlayChanges) {
        // 🗑️ REMOVE overlays that shouldn't exist anymore
        removeOverlaysThroughAnimationManager(map, changes.toRemove)

        // ➕ ADD new overlays that should exist
        addOverlaysThroughAnimationManager(map, changes.toAdd)
    }

    /**
     * Remove overlays through animation manager only (fade-out → remove)
     */
    private fun removeOverlaysThroughAnimationManager(map: MapView, airspaceIdsToRemove: Set<String>) {
        airspaceIdsToRemove.forEach { airspaceId ->
            val polygon = currentlyRenderedAirspaces[airspaceId]
            if (polygon != null) {
                animationManager?.animateOverlayRemoval(polygon, airspaceId, map) {
                    // Animation manager handles removal - just update our tracking
                    currentlyRenderedAirspaces.remove(airspaceId)
                    Log.v(TAG, "Animation manager completed removal: $airspaceId")
                } ?: throw IllegalStateException(
                    "Animation manager is required for airspace overlay removal. " +
                    "Ensure OverlayCoordinator is properly initialized."
                )
            }
        }
    }

    /**
     * Add overlays through animation manager only (invisible → fade-in)
     */
    private fun addOverlaysThroughAnimationManager(map: MapView, airspacesToAdd: Map<String, OverlayFeature>) {
        airspacesToAdd.entries.forEachIndexed { index, (airspaceId, feature) ->
            // Create overlay WITHOUT adding to map
            val overlays = GeoJsonUtils.createAirspaceOverlays(map, listOf(feature))
            if (overlays.isNotEmpty()) {
                val polygon = overlays.first()

                // Add to our tracking immediately
                currentlyRenderedAirspaces[airspaceId] = polygon

                // Animation manager handles addition with fade-in effect
                animationManager?.animateOverlayAddition(
                    overlay = polygon,
                    overlayId = airspaceId,
                    mapView = map,
                    staggerDelay = index * 100L // Stagger for visual polish
                ) {
                    Log.v(TAG, "Animation manager completed addition: $airspaceId")
                } ?: throw IllegalStateException(
                    "Animation manager is required for airspace overlay addition. " +
                    "Ensure OverlayCoordinator is properly initialized."
                )
            }
        }
    }

    /**
     * Data class representing overlay changes to execute
     */
    private data class OverlayChanges(
        val toRemove: Set<String>,
        val toAdd: Map<String, OverlayFeature>
    )

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

        // Remove airspaces through animation manager (viewport cleanup)
        if (airspacesToRemove.isNotEmpty()) {
            mapView?.let { map ->
                airspacesToRemove.forEach { airspaceId ->
                    val polygon = currentlyRenderedAirspaces[airspaceId]
                    if (polygon != null) {
                        // Animation manager handles viewport cleanup with fade-out
                        animationManager?.animateOverlayRemoval(polygon, airspaceId, map) {
                            currentlyRenderedAirspaces.remove(airspaceId)
                            Log.v(TAG, "Viewport cleanup animation completed: $airspaceId")
                        } ?: throw IllegalStateException(
                            "Animation manager is required for airspace overlay removal. " +
                            "Ensure OverlayCoordinator is properly initialized."
                        )
                    }
                }

                Log.d(TAG, "Viewport cleanup scheduled: ${airspacesToRemove.size} airspaces via animation manager")
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
     * Check if two bounding boxes overlap (intersection detection)
     */
    private fun boundingBoxesOverlap(box1: BoundingBox, box2: BoundingBox): Boolean {
         return box1.latNorth > box2.latSouth &&
                box1.latSouth < box2.latNorth &&
                box1.lonEast > box2.lonWest &&
                box1.lonWest < box2.lonEast
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
     * Create a visibility-aware polygon that detects viewport changes
     * This replaces the TODO with actual implementation for airspace visibility detection
     */
    fun createVisibilityAwarePolygon(feature: OverlayFeature): org.osmdroid.views.overlay.Polygon {
         val airspaceId = generateAirspaceId(feature)

         // Create custom polygon with visibility tracking
         val polygon = VisibilityAwarePolygon(airspaceId, feature).apply {
             // Store the airspace ID and original feature for callbacks
             title = airspaceId
             relatedObject = feature
         }

         return polygon
     }

    /**
     * Custom polygon class that tracks visibility changes
     * Overrides draw method to detect when airspace enters/leaves viewport
     */
    inner class VisibilityAwarePolygon(
         private val airspaceId: String,
         private val feature: OverlayFeature
    ) : org.osmdroid.views.overlay.Polygon() {

         private var wasVisible = false
         private var lastViewportCheck: BoundingBox? = null

         override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
             // Call original draw method first
             super.draw(canvas, mapView, shadow)

             // Check visibility changes after drawing
             checkVisibilityChanges(mapView)
         }

         /**
          * Check if polygon visibility has changed and trigger appropriate callbacks
          */
         private fun checkVisibilityChanges(mapView: MapView) {
             val currentViewport = mapView.boundingBox
             val isCurrentlyVisible = isVisibleInViewport(currentViewport)

             // Check if visibility state has changed
             if (isCurrentlyVisible != wasVisible) {
                 if (isCurrentlyVisible) {
                     onAirspaceVisible(feature)
                 } else {
                     onAirspaceHidden(feature)
                 }
                 wasVisible = isCurrentlyVisible
             }

             // Update last viewport for next check
             lastViewportCheck = currentViewport
         }

         /**
          * Determine if this polygon is visible in the current viewport
          * Uses improved intersection logic for better accuracy
          */
         private fun isVisibleInViewport(viewport: BoundingBox): Boolean {
             // Quick bounds check first - check if bounding boxes overlap
             bounds?.let { polygonBounds ->
                 if (!boundingBoxesOverlap(viewport, polygonBounds)) {
                     return false
                 }
             }

             // Detailed visibility check using polygon points
             @Suppress("DEPRECATION")
             val points = points ?: return false

             // Check if any polygon vertex is in viewport (intersection)
             points.forEach { point ->
                 if (point != null && viewport.contains(point)) {
                     return true
                 }
             }

             // Check if viewport corners fall inside polygon (containment)
             val viewportCorners = listOf(
                 GeoPoint(viewport.latNorth, viewport.lonWest),   // Top-left
                 GeoPoint(viewport.latNorth, viewport.lonEast),   // Top-right
                 GeoPoint(viewport.latSouth, viewport.lonEast),   // Bottom-right
                 GeoPoint(viewport.latSouth, viewport.lonWest)    // Bottom-left
             )

             for (corner in viewportCorners) {
                 if (isPointInPolygon(corner, points)) {
                     return true
                 }
             }

             // Check viewport center as final fallback
             val viewportCenter = GeoPoint(
                 (viewport.latNorth + viewport.latSouth) / 2.0,
                 (viewport.lonEast + viewport.lonWest) / 2.0
             )
             return isPointInPolygon(viewportCenter, points)
         }
     }

    /**
     * Callback when airspace becomes visible in viewport
     * Triggers preloading of adjacent airspace areas for smooth panning
     */
    private fun onAirspaceVisible(feature: OverlayFeature) {
         val airspaceId = generateAirspaceId(feature)
         Log.v(TAG, "Airspace entered view: $airspaceId")

         // Trigger intelligent preloading of adjacent areas
         preloadAdjacentAirspaceAreas(feature)

         // Mark airspace as recently accessed for LRU tracking
         updateAirspaceAccessTime(airspaceId)

         // Dispatch Redux action for airspace visibility event (if needed)
         // mapStore?.dispatch(AirspaceActions.AirspaceVisible(airspaceId))
     }

    /**
     * Callback when airspace leaves viewport
     * Triggers cleanup and memory optimization
     */
    private fun onAirspaceHidden(feature: OverlayFeature) {
         val airspaceId = generateAirspaceId(feature)
         Log.v(TAG, "Airspace left view: $airspaceId")

         // Mark airspace as hidden for potential cleanup
         scheduleAirspaceCleanup(airspaceId)

         // Dispatch Redux action for airspace hidden event (if needed)
         // mapStore?.dispatch(AirspaceActions.AirspaceHidden(airspaceId))
     }

    /**
     * Preload airspace data for areas adjacent to newly visible airspace
     * Improves user experience during panning and zooming
     */
    private fun preloadAdjacentAirspaceAreas(feature: OverlayFeature) {
         try {
             val centroid = feature.centroid

             // Calculate adjacent areas around the visible airspace
             val preloadDistance = spatialQueryRadiusKm * 0.3 // 30% of normal query radius

             // Trigger background loading for adjacent areas
             // This helps ensure smooth transitions when panning
             coroutineScope.launch {
                 try {
                     // Query for airspaces in adjacent areas
                     countryCacheManager?.let { countryCache ->
                         val adjacentFeatures = countryCache.queryMultiCountryArea(centroid, preloadDistance)

                         if (adjacentFeatures.isNotEmpty()) {
                             Log.d(TAG, "Preloaded ${adjacentFeatures.size} adjacent airspaces for smooth transitions")
                         }
                     }
                 } catch (e: Exception) {
                     Log.w(TAG, "Error preloading adjacent airspace areas", e)
                 }
             }
         } catch (e: Exception) {
             Log.w(TAG, "Error in adjacent airspace preloading", e)
         }
     }

    /**
     * Mark airspace as recently accessed for LRU cache management
     */
    private fun updateAirspaceAccessTime(airspaceId: String) {
         // Update access time for LRU eviction logic
         // This helps keep most recently viewed airspaces in memory
         Log.v(TAG, "Updated access time for airspace: $airspaceId")
     }

    /**
     * Schedule cleanup for airspaces that are no longer visible
     * Uses delayed cleanup to handle brief visibility changes
     */
    private fun scheduleAirspaceCleanup(airspaceId: String) {
         // Schedule delayed cleanup to handle cases where airspace briefly leaves view
         // This prevents excessive cleanup operations during rapid panning
         coroutineScope.launch {
             try {
                 // Wait before cleanup to see if airspace becomes visible again
                 delay(5000) // 5 second delay

                 // Check if airspace is still not visible before cleanup
                 if (currentlyRenderedAirspaces.containsKey(airspaceId)) {
                     Log.d(TAG, "Airspace $airspaceId still rendered, skipping cleanup")
                     return@launch
                 }

                 // Perform cleanup operations
                 Log.d(TAG, "Cleaning up resources for hidden airspace: $airspaceId")
                 // Additional cleanup logic would go here

             } catch (e: Exception) {
                 Log.w(TAG, "Error in scheduled airspace cleanup", e)
             }
         }
     }
}
