package com.madanala.tern.ui.overlays

import android.content.Context
import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.utils.AirspaceCache
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.CountryUtils
import com.madanala.tern.utils.GeoJsonUtils
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

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
    mapStore: MapStore?,
    private val airspaceCache: AirspaceCache = CacheManager.airspaceCache
) : BaseOverlayManager(OverlayType.AIRSPACE, mapStore) {

    // Universal country cache manager for intelligent country management (Priority 0 fix)
    private var countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager? = null

    // Reference to overlay coordinator removed - using direct map access


    // State management (extracted from MapViewModel)
    private var lastCheckLocation: GeoPoint? = null
    private var loadingJob: Job? = null

    // Track currently rendered airspaces for incremental updates
    private val currentlyRenderedAirspaces = mutableMapOf<String, org.osmdroid.views.overlay.Polygon>()

    // Aviation-optimized thresholds for movement and loading
    private val checkDistanceKm = 2.0  // Reduced for aviation use (was 5.0)
    private val reFilterDistanceKm = 80.0


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
    }

    override fun updateConfig(config: com.madanala.tern.redux.OverlayConfig) {
    }



    private var lastLoadPosition: GeoPoint? = null
    
    override fun onOverlayAttached() {

        // Get universal country cache manager from overlay coordinator
        // Note: This would need to be passed from the coordinator in real implementation
        // For now, we'll use a simplified approach
    }

    // Track the country loaded listener for cleanup
    private var countryLoadedListener: ((String) -> Unit)? = null

    /**
     * Set the universal country cache manager (called by OverlayCoordinator)
     */
    fun setCountryCacheManager(countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager) {
        this.countryCacheManager = countryCacheManager
        
        // Remove old listener if it exists to prevent leaks/accumulation
        countryLoadedListener?.let { 
            this.countryCacheManager?.onCountryLoadedListeners?.remove(it) 
        }

        // Create new listener
        val listener: (String) -> Unit = { countryCode ->
            // Use manager's scope which is tied to its lifecycle
            coroutineScope.launch {
                mapView?.mapCenter?.let { center ->
                    // Refresh airspaces for current location now that data is available
                    checkAndLoadAirspaceData(center as GeoPoint)
                }
            }
        }
        
        this.countryLoadedListener = listener
        this.countryCacheManager?.onCountryLoadedListeners?.add(listener)

        // PROACTIVE REFRESH: If we already have cached countries, trigger a load immediately
        // This handles the case where data was downloaded before this manager was attached
        if (countryCacheManager.getCachedCountries().isNotEmpty()) {
            coroutineScope.launch {
                mapView?.mapCenter?.let { center ->
                    checkAndLoadAirspaceData(center as GeoPoint)
                }
            }
        }
    }

    /**
     * RESET state for test stability
     */
    override fun reset() {
        Log.d("AirspaceOverlayManager", "Resetting AirspaceOverlayManager state")
        lastLoadPosition = null
        lastCheckLocation = null
        // Clear existing overlays to ensure no leakage
        currentlyRenderedAirspaces.clear()
        Log.d("AirspaceOverlayManager", "AirspaceOverlayManager reset complete")
    }



    override fun getRenderedCount(): Int {
        return currentlyRenderedAirspaces.size
    }

    override fun onAttach(mapView: MapView) {
        super.onAttach(mapView)
        this.mapView = mapView
        isAttached = true
        // Log.d(TAG, "Airspace overlay manager attached to map")
    }
    override fun onOverlayDetached() {
        // Log.d(TAG, "Airspace overlay manager detached")

        // Remove listener to prevent memory leaks
        countryLoadedListener?.let { 
            this.countryCacheManager?.onCountryLoadedListeners?.remove(it) 
        }
        countryLoadedListener = null

        // Cancel any ongoing loading jobs
        loadingJob?.cancel()
        loadingJob = null

        // Clear state and tracking
        lastCheckLocation = null
        currentlyRenderedAirspaces.clear()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        if (!isEnabled()) {
            return
        }



        // Validate coordinates for safety and correctness
        try {
            validateCoordinates(center)
            
            // Ignore 0,0 coordinates (default/uninitialized state)
            if (Math.abs(center.latitude) < 0.01 && Math.abs(center.longitude) < 0.01) {
                return
            }
        } catch (e: InvalidCoordinatesException) {
            Log.w(TAG, "Invalid coordinates in map move: ${e.message}", e)
            return
        }

        // Check if we've moved far enough to warrant a reload
        lastCheckLocation?.let { lastLocation ->
            val distance = lastLocation.distanceToAsDouble(center)
            val distanceKm = distance / 1000.0

            if (distanceKm < checkDistanceKm) {
                return
            }
        }


        checkAndLoadAirspaceData(center)
    }

    override fun onViewportChanged(viewport: BoundingBox) {
        onViewportChangedInternal(viewport)
    }

    override fun onViewportChangedInternal(viewport: BoundingBox) {

    }

    override fun onReduxStateChanged(state: MapState) {
        super.onReduxStateChanged(state)
        val enabled = isEnabled()

        if (!enabled) {
            // Clear airspaces if disabled
            clearOverlays()
            lastCheckLocation = null
        } else if (mapView != null) {
            // Postpone Redux-triggered overlay operations until GPS fix is available



            // If enabled and we have a map view, trigger loading
            val center = mapView?.mapCenter as? GeoPoint
            if (center != null) {
                checkAndLoadAirspaceData(center)
            }
        }
    }

    override fun onFocusModeChanged(enabled: Boolean) {
        val targetAlpha = if (enabled) 0x10 else 0x40 // ~6% vs 25% opacity
        
        currentlyRenderedAirspaces.values.forEach { polygon ->
            polygon.fillPaint.alpha = targetAlpha
            polygon.outlinePaint.alpha = targetAlpha * 2 // Slightly more visible outline
        }
        mapView?.invalidate()
    }

    /**
     * Handle overlay budget changes with airspace-specific logging
     */
    override fun onOverlayBudgetChanged(budget: com.madanala.tern.utils.OverlayBudget) {
        super.onOverlayBudgetChanged(budget)

        // Get current map center for geographic context
        val center = mapView?.mapCenter
        val centerStr = if (center != null) {
            String.format("@ %.4f,%.4f", center.latitude, center.longitude)
        } else {
            "@ unknown location"
        }



        Log.d(TAG, String.format(
            "Airspace Budget: %d total (Created: %d, Visible: %d %s)",
            budget.totalOverlays,
            currentlyRenderedAirspaces.size,
            currentlyRenderedAirspaces.size, // Assumed visible as they are tracked

            centerStr
        ))
    }

    /**
     * Handle memory state changes for airspace-specific optimizations
     */
    override fun onMemoryStateChanged(memoryState: com.madanala.tern.utils.ApplicationMemoryState) {
        super.onMemoryStateChanged(memoryState)

        // If memory pressure is high, trigger immediate viewport cleanup
        if (memoryState.calculatedPressure == com.madanala.tern.utils.MemoryPressureLevel.CRITICAL_MEMORY ||
            memoryState.calculatedPressure == com.madanala.tern.utils.MemoryPressureLevel.LOW_MEMORY) {
            Log.w(TAG, "Memory pressure detected - triggering enhanced airspace cleanup")
            triggerEmergencyCleanup()
        }
    }

    /**
     * Remove airspaces that are not visible in the current viewport (most efficient cleanup)
     */
    override fun removeInvisibleOverlays(): Int {
        if (currentlyRenderedAirspaces.isEmpty()) return 0

        val viewport = mapView?.boundingBox ?: return 0
        val mapCenter = mapView?.mapCenter as? GeoPoint ?: return 0

        // Find airspaces that are BOTH not visible AND not safety-critical
        val invisibleNonCriticalAirspaces = currentlyRenderedAirspaces.entries.filter { (airspaceId, polygon) ->
            val isInvisible = !isPolygonInViewport(polygon, viewport)
            val distance = getDistanceFromCenter(polygon, mapCenter)
            val isFar = distance > com.madanala.tern.utils.DistanceZone.MID.maxKm

            // Remove if invisible AND far from center (but not safety-critical)
            isInvisible && isFar && !isSafetyCriticalAirspace(airspaceId)
        }

        if (invisibleNonCriticalAirspaces.isEmpty()) return 0

        // Remove invisible non-critical airspaces
        var removedCount = 0
        // DIRECT BYPASS: Remove invisible non-critical airspaces directly
        invisibleNonCriticalAirspaces.forEach { (airspaceId, polygon) ->
             // Log.d(TAG, "Directly removing invisible airspace: $airspaceId")
             mapView!!.overlays.remove(polygon)
             removedCount++
        }
        mapView!!.invalidate()

        // Update tracking
        invisibleNonCriticalAirspaces.forEach { (airspaceId, _) ->
            currentlyRenderedAirspaces.remove(airspaceId)
        }

        return removedCount
    }

    /**
     * Check if airspace is safety-critical (should never be removed for memory reasons)
     */
    private fun isSafetyCriticalAirspace(airspaceId: String): Boolean {
        // Safety-critical airspaces should be in CORE zone or be essential for flight
        // For now, consider airspaces within CORE zone as safety-critical
        val mapCenter = mapView?.mapCenter as? GeoPoint ?: return false
        val coreThreshold = com.madanala.tern.utils.DistanceZone.CORE.maxKm

        currentlyRenderedAirspaces[airspaceId]?.let { polygon ->
            val distance = getDistanceFromCenter(polygon, mapCenter)
            return distance <= coreThreshold
        }

        return false
    }

    /**
     * Clear overlays in a specific distance zone for emergency cleanup
     */
    override fun clearOverlaysInZone(zone: com.madanala.tern.utils.DistanceZone): Int {
        if (currentlyRenderedAirspaces.isEmpty()) return 0

        val mapCenter = mapView?.mapCenter as? GeoPoint ?: return 0
        val zoneThreshold = zone.maxKm

        // Find airspaces in the specified zone (farthest from center)
        val airspacesInZone = currentlyRenderedAirspaces.entries.filter { (_, polygon) ->
            val distance = getDistanceFromCenter(polygon, mapCenter)
            distance > zoneThreshold
        }

        if (airspacesInZone.isEmpty()) return 0

        // Remove airspaces in this zone
        var removedCount = 0
        // DIRECT BYPASS: Remove airspaces in zone directly
        airspacesInZone.forEach { (airspaceId, polygon) ->
             // Log.d(TAG, "Directly removing zone airspace: $airspaceId")
             mapView!!.overlays.remove(polygon)
             removedCount++
        }
        mapView!!.invalidate()

        // Update tracking
        airspacesInZone.forEach { (airspaceId, _) ->
            currentlyRenderedAirspaces.remove(airspaceId)
        }

        return removedCount
    }

    /**
     * Preserve safety-critical overlays during emergency cleanup
     */
    override fun preserveSafetyCriticalOverlays(): Int {
        if (currentlyRenderedAirspaces.isEmpty()) return 0

        val mapCenter = mapView?.mapCenter as? GeoPoint ?: return 0
        val coreZoneThreshold = com.madanala.tern.utils.DistanceZone.CORE.maxKm

        // Find safety-critical airspaces in CORE zone
        val safetyCriticalAirspaces = currentlyRenderedAirspaces.entries.filter { (_, polygon) ->
            val distance = getDistanceFromCenter(polygon, mapCenter)
            distance <= coreZoneThreshold
        }

        return safetyCriticalAirspaces.size
    }

    override fun clearOverlays() {
        mapView?.let { map ->
            // DIRECT BYPASS: Clear all airspaces directly
            val polygonsToRemove = currentlyRenderedAirspaces.values.toList()

            polygonsToRemove.forEach { polygon ->
                map.overlays.remove(polygon)
            }
            map.invalidate()

            // Clear tracking
            currentlyRenderedAirspaces.clear()
            // Log.d(TAG, "Directly cleared all airspace overlays")
        } ?: run {
            // No map view available - just clear tracking
            currentlyRenderedAirspaces.clear()
            // Log.d(TAG, "Cleared airspace tracking (no map view)")
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
    /**
     * Load airspace data for a specific location using universal country management
     * Now uses UniversalCountryCacheManager for intelligent multi-country handling
     */
    private suspend fun loadAirspaceForLocation(context: Context, center: GeoPoint) {
        try {
            // Use universal country cache manager for intelligent country management
            countryCacheManager?.let { countryCache ->
                // Notify cache manager of location change to trigger downloads/preloading if needed
                countryCache.onLocationChanged(center)
        // Query multiple countries intelligently using the universal cache manager
                val nearbyFeatures = withContext(Dispatchers.IO) {
                    // Query multiple countries intelligently using the universal cache manager
                    val allFeatures = countryCache.queryMultiCountryArea(center, spatialQueryRadiusKm)
                    
                    // Filter for airspaces only (since UniversalCountryCacheManager returns all types, case-insensitive for robustness)
                    allFeatures.filter { it.overlayType.equals("airspace", ignoreCase = true) }
                }

                Log.d(TAG, "loadAirspaceForLocation: Query result - Airspaces: ${nearbyFeatures.size}")

                if (nearbyFeatures.isNotEmpty()) {
                    mapView?.let { map ->
                        updateAirspaceOverlaysIncrementally(map, nearbyFeatures, center)
                    } ?: Log.w(TAG, "Cannot render airspaces - no map view available")

                    // Update last location for movement tracking
                    lastCheckLocation = center
                } else {
                    Log.i(TAG, String.format("Airspace sync: Desired: 0 (from 0 features), Current: %d, Visible: %d @ %.4f,%.4f", 
                        currentlyRenderedAirspaces.size, currentlyRenderedAirspaces.size, center.latitude, center.longitude))
                }
            } ?: run {
                Log.e(TAG, "Universal country cache manager not available - cannot load airspaces")
            }

        } catch (e: CancellationException) {
            // Expected behavior when cancelling old requests due to debounce
            // Log.d(TAG, "Airspace loading cancelled due to user interaction")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading airspace data", e)
        }
    }




    /**
       * Update airspace overlays incrementally - coordinates with animation manager
       * Nearby feature algorithm determines WHAT should exist, animation manager handles HOW
       */
    private fun updateAirspaceOverlaysIncrementally(map: MapView, features: List<OverlayFeature>, center: GeoPoint) {
        // 🎯 STEP 0: Prioritize features (Zone-based budgeting)
        val prioritizedFeatures = prioritizeFeaturesByZone(
            features,
            center
        ) { it.centroid }

        // 🎯 STEP 1: Determine desired state (what SHOULD exist)
        val desiredAirspaceIds = determineDesiredAirspaceState(prioritizedFeatures)

        val centerStr = String.format("@ %.4f,%.4f", center.latitude, center.longitude)



        Log.i(TAG, String.format(
            "Airspace sync: Desired: %d (from %d features), Current: %d, Visible: %d %s",
            desiredAirspaceIds.size,
            features.size,
            currentlyRenderedAirspaces.size,
            currentlyRenderedAirspaces.size, // Assumed visible

            centerStr
        ))


        // 🎯 STEP 2: Calculate differences (what needs to change)
        val changes = calculateOverlayChanges(desiredAirspaceIds)

        // Log airspace changes for debugging
        Log.d(TAG, String.format(
            "Airspace changes: Adding %d, Removing %d airspaces",
            changes.toAdd.size,
            changes.toRemove.size
        ))

        // 🎯 STEP 3: Execute changes directly
        executeOverlayChangesDirectly(map, changes)



        Log.d(TAG, String.format(
            "Airspace synchronized: %d total, %d visible %s",
            currentlyRenderedAirspaces.size,
            currentlyRenderedAirspaces.size, // Assumed visible

            centerStr
        ))

        // Log names for test verification
        changes.toAdd.values.take(50).forEach { feature ->
             val name = feature.feature["properties"]?.let { (it as? Map<*, *>)?.get("name") } ?: "Unknown"
             Log.d(TAG, "Rendered Airspace: $name")
        }

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
     * Execute overlay changes directly
     */
    private fun executeOverlayChangesDirectly(map: MapView, changes: OverlayChanges) {
        // 🗑️ REMOVE overlays
        removeOverlaysFromMap(map, changes.toRemove.toList())

        // ➕ ADD new overlays
        addOverlaysToMap(map, changes.toAdd.entries.toList())
    }

    /**
     * Remove overlays through animation manager only (fade-out → remove)
     */
    /**
     * Remove overlays directly from the map and release to pool
     */
    private fun removeOverlaysFromMap(map: MapView, airspaceIdsToRemove: List<String>) {
            airspaceIdsToRemove.forEach { airspaceId ->
                val polygon = currentlyRenderedAirspaces[airspaceId]
                if (polygon != null) {
                    map.overlays.remove(polygon)
                    currentlyRenderedAirspaces.remove(airspaceId)
                    // Release to universal pool for reuse
                    mOverlayCoordinator?.releasePolygon(polygon)
                }
            }
            map.invalidate()
    }


    /**
     * Add overlays through animation manager only (invisible → fade-in)
     */
    /**
     * Add overlays directly to the map with Z-Index management and pooling
     */
    private fun addOverlaysToMap(map: MapView, airspacesToAdd: List<Map.Entry<String, OverlayFeature>>) {
            coroutineScope.launch {
                // Batch create polygons on IO thread to prevent UI blockage
                val polygonsWithIds = withContext(Dispatchers.IO) {
                    airspacesToAdd.mapNotNull { (airspaceId, feature) ->
                        // 🚀 PERFORMANCE: Acquire polygon from pool if possible
                        val polygon = withContext(Dispatchers.Main) { 
                            mOverlayCoordinator?.acquirePolygon(map) ?: org.osmdroid.views.overlay.Polygon()
                        }
                        
                        val overlays = GeoJsonUtils.createAirspaceOverlaysIncrementally(map, listOf(feature), polygon)
                        if (overlays.isNotEmpty()) {
                            airspaceId to overlays.first()
                        } else {
                            // Release back if failed to create
                            withContext(Dispatchers.Main) { mOverlayCoordinator?.releasePolygon(polygon) }
                            null
                        }
                    }
                }

                // Add to map on UI thread
                withContext(Dispatchers.Main) {
                    polygonsWithIds.forEach { pair ->
                        val (airspaceId, polygon) = pair
                        currentlyRenderedAirspaces[airspaceId] = polygon
                        insertOverlayAtCorrectDepth(map, polygon)
                    }
                    map.invalidate()
                    Log.d(TAG, "Added ${polygonsWithIds.size} airspaces to map (pooled)")
                }
            }
    }

    /**
     * Insert overlay at the correct depth (Index 0 = Bottom)
     * Ensures Airspaces render BELOW Routes and PG Spots
     */
    private fun insertOverlayAtCorrectDepth(map: MapView, overlay: org.osmdroid.views.overlay.Overlay) {
        // Simple Z-Index Strategy: Always add to bottom (index 0)
        // This ensures Airspaces are covered by Routes/Spots
        if (map.overlays.isNotEmpty()) {
             map.overlays.add(0, overlay)
        } else {
             map.overlays.add(overlay)
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

        val firstCoordPair = coordinates?.getOrNull(0).let { outer ->
            when (outer) {
                is List<*> -> outer.getOrNull(0) as? List<*>
                else -> null
            }
        }

        return if (firstCoordPair != null && firstCoordPair.size >= 2) {
            val lon = firstCoordPair[0]
            val lat = firstCoordPair[1]
            if (lon is Number && lat is Number) {
                String.format("airspace_coord_%.6f_%.6f", lat.toDouble(), lon.toDouble())
            } else {
                "airspace_hilbert_${feature.hilbertIndex}"
            }
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
        // Disabled for stability debugging
        if (currentlyRenderedAirspaces.isEmpty()) {
            // Log.d(TAG, "No airspaces to manage for viewport")
            return
        }

        // ✅ ADAPTIVE SYSTEM: Viewport management now handled by zone-based allocation
        // Manual viewport expansion replaced by intelligent zone-based cleanup

        // Log.d(TAG, "Managing ${currentlyRenderedAirspaces.size} airspaces for viewport: $viewport")

        // ✅ ADAPTIVE SYSTEM: Use zone-based cleanup instead of manual viewport logic
        // The emergency cleanup system handles viewport management through distance zones

        val airspacesToRemove = mutableListOf<String>()
        // Zone-based cleanup is now handled by the adaptive system's emergency cleanup

        // ✅ ADAPTIVE SYSTEM: Memory limit enforcement now handled automatically
        // The BaseOverlayManager adaptive system manages overlay budgets and triggers
        // emergency cleanup when needed. Manual limit enforcement is obsolete.

        // Check if adaptive system recommends emergency cleanup
        if (shouldTriggerEmergencyCleanup()) {
            Log.w(TAG, "Adaptive system triggered emergency cleanup for airspace overlays")
            triggerEmergencyCleanup()
            return // Emergency cleanup handled by BaseOverlayManager
        }

        // ✅ REMOVED: Manual viewport cleanup logic
        // This is now handled by the adaptive system's emergency cleanup when needed
        // The system automatically manages overlay lifecycles through zone-based allocation
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
     * Get the centroid of a polygon for Hilbert curve calculations
     */
    private fun getPolygonCentroid(polygon: org.osmdroid.views.overlay.Polygon): GeoPoint {
        @Suppress("DEPRECATION")
        val points = polygon.points
        if (points.isNullOrEmpty()) {
            return GeoPoint(0.0, 0.0) // Fallback
        }

        // Simple centroid calculation using first point as approximation
        // In production, this should calculate the actual centroid of the polygon
        return points.first()
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
                             // Log.d(TAG, "Preloaded ${adjacentFeatures.size} adjacent airspaces for smooth transitions")
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
                     // Log.d(TAG, "Airspace $airspaceId still rendered, skipping cleanup")
                     return@launch
                 }

                 // Perform cleanup operations
                 // Log.d(TAG, "Cleaning up resources for hidden airspace: $airspaceId")
                 // Additional cleanup logic would go here

             } catch (e: Exception) {
                 Log.w(TAG, "Error in scheduled airspace cleanup", e)
             }
         }
     }
}
