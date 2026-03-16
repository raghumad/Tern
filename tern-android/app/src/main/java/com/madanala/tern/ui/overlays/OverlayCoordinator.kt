package com.madanala.tern.ui.overlays

import android.content.Context
import android.os.Looper
import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.utils.CacheManager

import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import kotlinx.coroutines.*
import com.madanala.tern.utils.normalizePrecision
import kotlin.math.log2

/**
 * OverlayCoordinator: Unified management system for map overlay types.
 *
 * Coordinates lifecycle and state management for airspace and PG spot overlays.
 * Provides clean API for overlay management and handles Redux state synchronization.
 *
 * Architecture: One coordinator manages overlay managers through clean interfaces.
 * Now supports Z-Index layering using FolderOverlay.
 */
class OverlayCoordinator {
    private val TAG = "OverlayCoordinator"

    // Registry of active overlay managers by type
    private val activeManagers = mutableMapOf<OverlayType, OverlayManager>()

    // Redux store for state management
    private var mapStore: MapStore? = null

    // Store reference for cleanup
    private var mapView: MapView? = null

    // Z-Index Layers
    // Airspace Layer removed - Airspaces now render directly

    private val pgSpotLayer = FolderOverlay()
    private val routeLayer = FolderOverlay()

    // Universal country cache manager for all overlay types (Priority 0 fix)
    private var countryCacheManager: com.madanala.tern.utils.UniversalCountryCacheManager? = null

    // Universal overlay animation manager for smooth transitions
    private val overlayAnimationManager = OverlayAnimationManager()

    // Batch overlay operations for Hilbert curve ordering
    private val pendingAdditions = mutableListOf<OverlayWithInfo>()
    private val pendingRemovals = mutableListOf<OverlayWithInfo>()

    // Coroutine scope for batch operations
    private val batchAnimationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Data class to hold overlay information for batch operations
    data class OverlayWithInfo(
        val overlay: Any,
        val overlayId: String,
        val centroid: org.osmdroid.util.GeoPoint,
        val type: OverlayType // Added type for layer targeting
    )

    /**
     * Universal Overlay Pool (SSOT for reusable map objects)
     * Follows the "Source of Truth" skill by being the single owner of "spare" overlay objects.
     */
    inner class UniversalOverlayPool {
        private val markerPool = mutableListOf<org.osmdroid.views.overlay.Marker>()
        private val polygonPool = mutableListOf<org.osmdroid.views.overlay.Polygon>()
        private val MAX_POOL_SIZE = 100

        fun acquireMarker(mapView: MapView): org.osmdroid.views.overlay.Marker {
            return if (markerPool.isNotEmpty()) {
                markerPool.removeAt(markerPool.size - 1).apply {
                    // Reset state for reuse
                    alpha = 1.0f
                    icon = null
                    title = null
                    snippet = null
                }
            } else {
                org.osmdroid.views.overlay.Marker(mapView)
            }
        }

        fun releaseMarker(marker: org.osmdroid.views.overlay.Marker) {
            if (markerPool.size < MAX_POOL_SIZE) {
                markerPool.add(marker)
            }
        }

        fun acquirePolygon(mapView: MapView): org.osmdroid.views.overlay.Polygon {
            return if (polygonPool.isNotEmpty()) {
                polygonPool.removeAt(polygonPool.size - 1).apply {
                    // Reset points for reuse
                    points.clear()
                }
            } else {
                org.osmdroid.views.overlay.Polygon()
            }
        }

        fun releasePolygon(polygon: org.osmdroid.views.overlay.Polygon) {
            if (polygonPool.size < MAX_POOL_SIZE) {
                polygonPool.add(polygon)
            }
        }

        fun getPoolStats(): Map<String, Int> = mapOf(
            "markers_available" to markerPool.size,
            "polygons_available" to polygonPool.size
        )
    }

    private val overlayPool = UniversalOverlayPool()

    /**
     * Reports global active overlay usage across all managers
     */
    fun reportGlobalOverlayUsage() {
        val stats = activeManagers.mapValues { it.value.getRenderedCount() }
        val total = stats.values.sum()
        val poolStats = overlayPool.getPoolStats()
        
        Log.i(TAG, "Global Overlay Usage: $total total active (Airspace: ${stats[OverlayType.AIRSPACE] ?: 0}, " +
                "PGSpot: ${stats[OverlayType.PG_SPOTS] ?: 0}, Routes: ${stats[OverlayType.ROUTES] ?: 0}) " +
                "Pool: Markers=${poolStats["markers_available"]}, Polygons=${poolStats["polygons_available"]}")
    }

    // Data class for overlays with Hilbert curve values
    data class OverlayWithHilbert(
        val overlay: Any,
        val overlayId: String,
        val centroid: org.osmdroid.util.GeoPoint,
        val type: OverlayType,
        val hilbertValue: Long,
        val distanceFromCenter: Double
    ) : Comparable<OverlayWithHilbert> {
        override fun compareTo(other: OverlayWithHilbert): Int {
            val hilbertComparison = hilbertValue.compareTo(other.hilbertValue)
            return if (hilbertComparison != 0) {
                hilbertComparison
            } else {
                distanceFromCenter.compareTo(other.distanceFromCenter)
            }
        }
    }

    /**
     * Pooling accessors for managers
     */
    fun acquireMarker(mapView: MapView): org.osmdroid.views.overlay.Marker = overlayPool.acquireMarker(mapView)
    fun releaseMarker(marker: org.osmdroid.views.overlay.Marker) = overlayPool.releaseMarker(marker)
    fun acquirePolygon(mapView: MapView): org.osmdroid.views.overlay.Polygon = overlayPool.acquirePolygon(mapView)
    fun releasePolygon(polygon: org.osmdroid.views.overlay.Polygon) = overlayPool.releasePolygon(polygon)

    /**
      * Initialize coordinator with Redux store
      */
    fun initialize(mapStore: MapStore?, mapView: MapView, context: Context) {
        this.mapStore = mapStore
        this.mapView = mapView
        
        // Schedule periodic budget reporting
        batchAnimationScope.launch {
            while (isActive) {
                delay(10000) // Every 10 seconds
                reportGlobalOverlayUsage()
            }
        }
        // 1. Airspaces (Bottom) - Handled directly by AirspaceOverlayManager (No FolderOverlay)

        
        // 2. PG Spots (Middle)
        pgSpotLayer.name = "PG Spot Layer"
        if (!mapView.overlays.contains(pgSpotLayer)) {
            mapView.overlays.add(pgSpotLayer)
        }
        
        // 3. Routes (Top)
        routeLayer.name = "Route Layer"
        if (!mapView.overlays.contains(routeLayer)) {
            mapView.overlays.add(routeLayer)
        }

        // Initialize universal country cache manager for all overlay types (Priority 0 fix)
        countryCacheManager = com.madanala.tern.utils.UniversalCountryCacheManager(context)
        
        // Set up callback to refresh overlays when country data is loaded
        countryCacheManager?.onCountryLoadedListeners?.add { countryCode ->
            // Log.d(TAG, "Country loaded: $countryCode - refreshing overlays")
            mapView?.let { map ->
                val center = map.mapCenter as? org.osmdroid.util.GeoPoint
                if (center != null) {
                    val zoom = map.zoomLevelDouble
                    activeManagers.values.forEach { manager ->
                        manager.performMapMove(center, zoom)
                    }
                }
            }
        }
        
        // Log.d(TAG, "Universal country cache manager initialized for all overlay types")

        // Trigger performance debugger initialization for development monitoring
        // PerformanceDebugger.triggerTestEvents() removed as it's no longer available

        // Start observing Redux state if available
        if (mapStore == null) {
            // Log.d(TAG, "No Redux store - overlay managers will use default configuration")
        } else {
            startStateObservation()
        }
    }

    /**
     * Add an overlay manager to the coordinator
     * Managers are registered by their overlay type and connected to universal country cache
     */
    fun addOverlayManager(manager: OverlayManager) {
        val overlayType = manager.overlayType

        if (activeManagers.containsKey(overlayType)) {
            removeOverlayManager(overlayType)
        }

        activeManagers[overlayType] = manager
        manager.initialize(mapView!!)
        
        // Inject coordinator for pooling and Hilbert integration
        manager.setOverlayCoordinator(this)

        // Connect overlay manager to animation manager for smooth transitions
        if (manager is com.madanala.tern.ui.overlays.BaseOverlayManager) {
            manager.animationManager = overlayAnimationManager
        }

        // Connect overlay managers to universal country cache manager (Priority 0 integration)
        countryCacheManager?.let { countryCache ->
            when (manager) {
                is com.madanala.tern.ui.overlays.AirspaceOverlayManager -> {
                    manager.setCountryCacheManager(countryCache)
                    // Coordinator decoupling: Airspaces manage their own rendering now
                }
                is com.madanala.tern.ui.overlays.PGSpotOverlayManager -> {
                    manager.setCountryCacheManager(countryCache)
                    manager.setOverlayCoordinator(this) // Connect for Hilbert ordering
                    // Log.d(TAG, "✅ Connected PGSpotOverlayManager to universal country cache and coordinator")
                }
                is com.madanala.tern.ui.overlays.RouteOverlayManager -> {
                    // RouteOverlayManager doesn't need country cache but needs coordinator for lifecycle
                    manager.setOverlayCoordinator(this) // Connect for Hilbert ordering and lifecycle management
                    // Log.d(TAG, "✅ Connected RouteOverlayManager to coordinator (routes don't need country cache)")
                }
                else -> {
                    // Handle other overlay manager types that don't need country cache
                    // Log.d(TAG, "Connected ${manager.overlayType} overlay manager (no country cache needed)")
                }
            }
        } ?: run {
            // If no country cache manager, still connect RouteOverlayManager to coordinator
            if (manager is com.madanala.tern.ui.overlays.RouteOverlayManager) {
                manager.setOverlayCoordinator(this)
                // Log.d(TAG, "✅ Connected RouteOverlayManager to coordinator (no country cache available)")
            }
            Log.w(TAG, "Country cache manager not available")
        }

    }

    /**
     * Get the current count of rendered overlays of a specific type.
     * Used for test synchronization.
     */
    fun getRenderedOverlayCount(type: OverlayType): Int {
        return activeManagers[type]?.getRenderedCount() ?: 0
    }

    /**
     * Remove overlay manager by type
     */
    fun removeOverlayManager(overlayType: OverlayType) {
        activeManagers[overlayType]?.let { manager ->
            manager.onDetach()
            activeManagers.remove(overlayType)
            // Log.d(TAG, "Removed overlay manager: $overlayType")
        }
    }

    /**
     * Get overlay manager by type
     */
    fun getOverlayManager(overlayType: OverlayType): OverlayManager? {
        return activeManagers[overlayType]
    }

    /**
     * Check if overlay type is currently managed
     */
    fun hasOverlayType(overlayType: OverlayType): Boolean {
        return activeManagers.containsKey(overlayType)
    }

    /**
     * Get all currently active overlay types
     */
    fun getActiveOverlayTypes(): Set<OverlayType> {
        return activeManagers.keys.toSet()
    }

    /**
     * Notify all overlays of map movement for data loading
     * Now routes through UniversalCountryCacheManager for intelligent country management
     */
    fun onMapMoved(centerLat: Double, centerLng: Double, zoom: Double) {
        val centerPoint = org.osmdroid.util.GeoPoint(centerLat, centerLng).normalizePrecision()

        // Route through Universal Country Cache Manager for intelligent country management (Priority 0)
        countryCacheManager?.onLocationChanged(centerPoint)

        // Continue with existing overlay manager notifications
        activeManagers.values.forEach { manager ->
            if (manager is com.madanala.tern.ui.overlays.BaseOverlayManager) {
                manager.scheduleMapMove(centerPoint, zoom)
            } else {
                manager.performMapMove(centerPoint, zoom)
            }
        }
    }

    /**
     * Notify all overlays of viewport changes for view management
     */
    fun onViewportChanged(viewport: BoundingBox) {
        // Notify all overlay managers of viewport changes
        activeManagers.values.forEach { manager ->
            manager.onViewportChanged(viewport)
        }
    }

    /**
     * Configure overlay settings through Redux actions
     */
    fun updateOverlayConfig(overlayType: OverlayType, config: OverlayConfig) {
        getOverlayManager(overlayType)?.let { manager ->
            manager.updateConfig(config)
            // Log.d(TAG, "Updated config for $overlayType: $config")
        } ?: Log.w(TAG, "No manager found for $overlayType to update config")
    }

    /**
     * Get current config for overlay type
     */
    fun getOverlayConfig(overlayType: OverlayType): OverlayConfig? {
        return getOverlayManager(overlayType)?.getCurrentConfig()
    }

    /**
      * Get universal country cache manager for overlay managers that need country data
      */
    fun getCountryCacheManager(): com.madanala.tern.utils.UniversalCountryCacheManager? {
        return countryCacheManager
    }

    /**
      * Get the universal overlay animation manager for smooth transitions
      */
    fun getAnimationManager(): OverlayAnimationManager {
        return overlayAnimationManager
    }

    /**
     * Add overlay to batch for ordered addition (center to outside)
     * Overlays will be added in Hilbert curve order when flushPendingAdditions() is called
     */
    fun addOverlayToBatch(overlay: Any, overlayId: String, centroid: org.osmdroid.util.GeoPoint, type: OverlayType) {
        synchronized(pendingAdditions) {
            pendingAdditions.add(OverlayWithInfo(overlay, overlayId, centroid, type))
        }
    }

    /**
     * Add overlay to batch for ordered removal (outside to center)
     * Overlays will be removed in reverse Hilbert curve order when flushPendingRemovals() is called
     */
    fun removeOverlayFromBatch(overlay: Any, overlayId: String, centroid: org.osmdroid.util.GeoPoint, type: OverlayType) {
        synchronized(pendingRemovals) {
            pendingRemovals.add(OverlayWithInfo(overlay, overlayId, centroid, type))
        }
    }

    // ... (removeOverlayFromBatch)

    fun flushPendingAdditions(): Int {
        synchronized(pendingAdditions) {
            if (pendingAdditions.isEmpty()) {
                return 0
            }

            val additionsToProcess = pendingAdditions.toList()
            pendingAdditions.clear()

            // Sort by Hilbert curve order (center to outside)
            val sortedAdditions = sortOverlaysByHilbertOrder(additionsToProcess, isAddition = true)

            // Process in sorted order with staggered animation
            processBatchWithStaggeredAnimation(sortedAdditions, isAddition = true)

            return sortedAdditions.size
        }
    }

    /**
     * Process pending overlay removals in reverse Hilbert curve order (outside to center)
     * Returns the number of overlays processed
     */
    fun flushPendingRemovals(): Int {
        synchronized(pendingRemovals) {
            if (pendingRemovals.isEmpty()) return 0

            val removalsToProcess = pendingRemovals.toList()
            pendingRemovals.clear()

            // Sort by reverse Hilbert curve order (outside to center)
            val sortedRemovals = sortOverlaysByHilbertOrder(removalsToProcess, isAddition = false)

            // Process in sorted order with staggered animation
            processBatchWithStaggeredAnimation(sortedRemovals, isAddition = false)

            return sortedRemovals.size
        }
    }

    /**
     * Calculate distance from center point for comparison with Hilbert ordering
     */
    private fun calculateDistanceFromCenter(point: org.osmdroid.util.GeoPoint, center: org.osmdroid.util.GeoPoint): Double {
        return center.distanceToAsDouble(point) / 1000.0 // Convert to kilometers
    }

    /**
     * Sort overlays by Hilbert curve order for visually beautiful addition/removal
     */
    private fun sortOverlaysByHilbertOrder(
        overlays: List<OverlayWithInfo>,
        isAddition: Boolean
    ): List<OverlayWithInfo> {
        if (overlays.isEmpty()) return overlays

        val center = mapView?.mapCenter
        val zoomLevel = mapView?.zoomLevelDouble ?: 10.0

        if (center == null) {
            Log.w(TAG, "No map center available for Hilbert sorting, using original order")
            return overlays
        }

        return try {
            // Create overlays with Hilbert values using existing MapOverlayCacheUtils
            val centerGeoPoint = center as org.osmdroid.util.GeoPoint

            // Create overlays with Hilbert values
            val overlaysWithHilbert = overlays.map { overlayInfo ->
                val hilbertValue = com.madanala.tern.utils.MapOverlayCacheUtils.computeHilbertIndexRelativeToCenter(
                    overlayInfo.centroid, centerGeoPoint, 16
                )
                val distanceFromCenter = calculateDistanceFromCenter(overlayInfo.centroid, centerGeoPoint)

                OverlayWithHilbert(
                    overlayInfo.overlay,
                    overlayInfo.overlayId,
                    overlayInfo.centroid,
                    overlayInfo.type,
                    hilbertValue,
                    distanceFromCenter
                )
            }

            // Sort by Hilbert value for additions (center to outside)
            // Sort by reverse Hilbert value for removals (outside to center)
            val sortedOverlays = if (isAddition) {
                overlaysWithHilbert.sortedBy { it.hilbertValue }
            } else {
                overlaysWithHilbert.sortedByDescending { it.hilbertValue }
            }

            sortedOverlays.map { overlayWithHilbert ->
                OverlayWithInfo(overlayWithHilbert.overlay, overlayWithHilbert.overlayId, overlayWithHilbert.centroid, overlayWithHilbert.type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating Hilbert values, using original order", e)
            overlays
        }
    }

    companion object {
        var ANIMATIONS_ENABLED = false
    }

    /**
      * Process batch of overlays with staggered animation for smooth visual effect
      */
    private fun processBatchWithStaggeredAnimation(
        overlays: List<OverlayWithInfo>,
        isAddition: Boolean
    ) {
        if (overlays.isEmpty() || mapView == null) return

        if (!ANIMATIONS_ENABLED) {
            // Synchronous processing for tests
            overlays.forEach { overlayInfo ->
                if (isAddition) {
                    if (overlayInfo.overlay is org.osmdroid.views.overlay.Overlay) {
                        getLayerForType(overlayInfo.type).add(overlayInfo.overlay)
                    }
                } else {
                    if (overlayInfo.overlay is org.osmdroid.views.overlay.Overlay) {
                        getLayerForType(overlayInfo.type).remove(overlayInfo.overlay)
                    }
                }
            }
            mapView?.invalidate()
            return
        }

        val staggerDelayMs = 100L // 100ms between each overlay

        overlays.forEachIndexed { index, overlayInfo ->
            val delay = index * staggerDelayMs

            batchAnimationScope.launch {
                try {
                    delay(delay)

                    if (isAddition) {
                        try {
                            overlayAnimationManager.animateOverlayAddition(
                                overlay = overlayInfo.overlay,
                                overlayId = overlayInfo.overlayId,
                                mapView = mapView!!,
                                type = overlayInfo.type
                            ) {
                                // Animation completed
                            }
                        } catch (e: Exception) {
                            // Fallback: Add directly if animation fails
                            try {
                                if (overlayInfo.overlay is org.osmdroid.views.overlay.Overlay) {
                                    val layer = getLayerForType(overlayInfo.type)
                                    layer.add(overlayInfo.overlay)
                                    mapView!!.invalidate()
                                }
                            } catch (fallbackException: Exception) {
                                Log.e(TAG, "Fallback addition failed for ${overlayInfo.overlayId}", fallbackException)
                            }
                        }
                    } else {
                        overlayAnimationManager.animateOverlayRemoval(
                            overlay = overlayInfo.overlay,
                            overlayId = overlayInfo.overlayId,
                            mapView = mapView!!,
                            type = overlayInfo.type
                        ) {
                            // Removal completed
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in batch processing for ${overlayInfo.overlayId}", e)
                }
            }
        }
    }

    private fun getLayerForType(type: OverlayType): FolderOverlay {
        return when (type) {
            OverlayType.AIRSPACE -> throw IllegalStateException("Airspaces do not use FolderOverlay")

            OverlayType.PG_SPOTS -> pgSpotLayer
            OverlayType.ROUTES -> routeLayer
        }
    }

    /**
     * Get current cached countries for debugging
     */
    fun getCachedCountries(): Set<String> {
        return countryCacheManager?.getCachedCountries() ?: emptySet()
    }

    /**
     * Trigger performance dashboard for testing (development only)
     */
    fun triggerPerformanceDashboard() {
        try {
            // com.madanala.tern.utils.PerformanceDebugger.triggerTestEvents()
            // Log.d(TAG, "Performance dashboard triggered for testing")
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering performance dashboard", e)
        }
    }

    /**
     * Force refresh data for all overlays
     */
    fun refreshAllOverlays() {
        activeManagers.values.forEach { manager ->
            manager.clearOverlays()
        }
        // Log.d(TAG, "Refreshed all overlays (${activeManagers.size} managers)")
    }

    /**
     * Get performance statistics from all overlays and viewport loading manager
     */
    fun getPerformanceStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        activeManagers.forEach { (type, manager) ->
            stats[type.name] = manager.getPerformanceStats()
        }
        stats["total_active_managers"] = activeManagers.size

        // Add Hilbert batch statistics
        synchronized(pendingAdditions) {
            stats["pending_additions"] = pendingAdditions.size
        }
        synchronized(pendingRemovals) {
            stats["pending_removals"] = pendingRemovals.size
        }

        return stats
    }

    /**
     * Get current batch statistics for debugging Hilbert ordering
     */
    fun getBatchStats(): Map<String, Int> {
        return synchronized(pendingAdditions to pendingRemovals) {
            mapOf(
                "pending_additions" to pendingAdditions.size,
                "pending_removals" to pendingRemovals.size
            )
        }
    }


    /**
     * Start observing Redux state for overlay configuration changes
     */
    private fun startStateObservation() {
        // Note: In real implementation, you might use collectAsState() in a Composable
        // or set up observers in the coordinator lifecycle
        // Log.d(TAG, "Started Redux state observation")
    }

    /**
     * Handle Redux state updates for all managed overlays
     */
    fun onReduxStateChanged(state: MapState) {
        activeManagers.values.forEach { manager ->
            manager.onReduxStateChanged(state)
        }
    }

    /**
     * RESET state for test stability
     */
    fun reset() {
        Log.d(TAG, "Resetting OverlayCoordinator state")
        countryCacheManager?.reset()
        
        // Reset individual managers to clear their load positions/states between tests
        activeManagers.values.forEach { 
            it.reset()
        }
        
        refreshAllOverlays()
        synchronized(pendingAdditions) {
            pendingAdditions.clear()
        }
        synchronized(pendingRemovals) {
            pendingRemovals.clear()
        }
    }

    /**
      * Clean shutdown of all overlays
      */
    fun shutdown() {
        // Log.d(TAG, "Shutting down OverlayCoordinator (${activeManagers.size} managers)")

        // Shutdown animation manager first
        overlayAnimationManager.shutdown()

        // Shutdown batch animation scope
        batchAnimationScope.cancel()

        // Shutdown universal country cache manager (Priority 0)
        countryCacheManager?.shutdown()
        countryCacheManager = null

        // Clear pending batches
        synchronized(pendingAdditions) {
            pendingAdditions.clear()
        }
        synchronized(pendingRemovals) {
            pendingRemovals.clear()
        }

        // Shutdown all managers
        activeManagers.values.forEach { manager ->
            try {
                manager.onDetach()
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down manager ${manager.overlayType}", e)
            }
        }

        activeManagers.clear()
        mapStore = null
        mapView = null

        // Log.d(TAG, "OverlayCoordinator shutdown complete")
    }

    /**
      * Universal overlay animation manager that provides smooth transitions for all overlay types
      * Works with Polygons, Markers, and any other overlay type while preserving visual properties
      */
    inner class OverlayAnimationManager {
        private val animationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        /**
          * Animate overlay addition with smooth fade-in while preserving all visual properties
          */
        fun animateOverlayAddition(
            overlay: Any,
            overlayId: String,
            mapView: MapView,
            staggerDelay: Long = 0L,
            type: OverlayType,
            onComplete: (() -> Unit)? = null
        ) {
            animationScope.launch {
                try {
                    // Apply stagger delay for multiple overlays
                    if (staggerDelay > 0) {
                        delay(staggerDelay)
                    }

                    val layer = getLayerForType(type)

                    when (overlay) {
                        is org.osmdroid.views.overlay.Polygon -> animatePolygonAddition(overlay, layer, mapView)
                        is org.osmdroid.views.overlay.Marker -> animateMarkerAddition(overlay, layer, mapView)
                        else -> {
                            // Unknown overlay type - add immediately
                            if (overlay is org.osmdroid.views.overlay.Overlay) {
                                layer.add(overlay)
                                mapView.invalidate()
                            }
                        }
                    }

                    onComplete?.invoke()

                } catch (e: Exception) {
                    // Animation failed - throw exception since animation manager is now mandatory
                    throw IllegalStateException(
                        "Animation manager failed for overlay $overlayId. " +
                        "Animation manager is required for overlay operations.", e
                    )
                }
            }
        }

        /**
          * Animate overlay removal with smooth fade-out while preserving all visual properties
          */
        fun animateOverlayRemoval(
            overlay: Any,
            overlayId: String,
            mapView: MapView,
            type: OverlayType,
            onComplete: (() -> Unit)? = null
        ) {
            animationScope.launch {
                try {
                    val layer = getLayerForType(type)
                    
                    when (overlay) {
                        is org.osmdroid.views.overlay.Polygon -> animatePolygonRemoval(overlay, layer, mapView)
                        is org.osmdroid.views.overlay.Marker -> animateMarkerRemoval(overlay, layer, mapView)
                        else -> {
                            // Unknown overlay type - remove immediately
                            if (overlay is org.osmdroid.views.overlay.Overlay) {
                                layer.remove(overlay)
                                mapView.invalidate()
                            }
                        }
                    }

                    onComplete?.invoke()

                } catch (e: Exception) {
                    // Animation failed - throw exception since animation manager is now mandatory
                    throw IllegalStateException(
                        "Animation manager failed for overlay $overlayId. " +
                        "Animation manager is required for overlay operations.", e
                    )
                }
            }
        }

        /**
           * Animate polygon addition with correct fade-in behavior
           * Fade-in: Add with 0 alpha → Animate to target alpha (preserving original alpha values)
           */
        private suspend fun animatePolygonAddition(polygon: org.osmdroid.views.overlay.Polygon, layer: FolderOverlay, mapView: MapView) {
            // Store original colors to preserve intended final appearance
            val originalFillColor = polygon.fillPaint.color
            val originalOutlineColor = polygon.outlinePaint.color

            // Extract the intended target alphas from original colors (preserve designer's intent)
            val targetFillAlpha = android.graphics.Color.alpha(originalFillColor)   // ✅ Use original alpha
            val targetOutlineAlpha = android.graphics.Color.alpha(originalOutlineColor) // ✅ Use original alpha

            // Start with 0 alpha (invisible) - both paint alpha and color alpha
            polygon.fillPaint.alpha = 0
            polygon.outlinePaint.alpha = 0

            // Temporarily set colors to full opacity versions for animation
            // (We'll restore original colors at the end)
            polygon.fillPaint.color = android.graphics.Color.argb(targetFillAlpha,   // Use target alpha
                android.graphics.Color.red(originalFillColor),
                android.graphics.Color.green(originalFillColor),
                android.graphics.Color.blue(originalFillColor))
            polygon.outlinePaint.color = android.graphics.Color.argb(targetOutlineAlpha, // Use target alpha
                android.graphics.Color.red(originalOutlineColor),
                android.graphics.Color.green(originalOutlineColor),
                android.graphics.Color.blue(originalOutlineColor))

            // Add to layer (overlay is invisible at this point)
            layer.add(polygon)

            // Animate from 0 to target alpha
            val animationDuration = 500L
            val steps = 20
            val stepDelay = animationDuration / steps

            for (step in 1..steps) {
                val fadeAlpha = (targetFillAlpha * step) / steps
                polygon.fillPaint.alpha = fadeAlpha
                polygon.outlinePaint.alpha = fadeAlpha
                mapView.invalidate()
                delay(stepDelay)
            }

            // FINAL: Set to target alphas and restore original colors (preserves exact intended appearance)
            polygon.fillPaint.alpha = targetFillAlpha      // ✅ Use original target alpha
            polygon.outlinePaint.alpha = targetOutlineAlpha // ✅ Use original target alpha
            polygon.fillPaint.color = originalFillColor     // ✅ Restore exact original color
            polygon.outlinePaint.color = originalOutlineColor // ✅ Restore exact original color

        }

        /**
           * Animate polygon removal with correct fade-out behavior
           * Fade-out: Animate from current alpha to 0 → Remove overlay
           */
        private suspend fun animatePolygonRemoval(polygon: org.osmdroid.views.overlay.Polygon, layer: FolderOverlay, mapView: MapView) {
            // Store current alphas (fade from actual current alpha, not hardcoded 255)
            val currentFillAlpha = polygon.fillPaint.alpha      // ✅ Use actual current alpha
            val currentOutlineAlpha = polygon.outlinePaint.alpha // ✅ Use actual current alpha

            // Animate paint alpha to 0 over 500ms
            val animationDuration = 500L
            val steps = 20
            val stepDelay = animationDuration / steps

            for (step in steps downTo 0) {
                val fadeAlpha = (currentFillAlpha * step) / steps  // ✅ Fade from current alpha to 0
                polygon.fillPaint.alpha = fadeAlpha
                polygon.outlinePaint.alpha = fadeAlpha
                mapView.invalidate()
                delay(stepDelay)
            }

            // Remove from layer after animation completes
            layer.remove(polygon)
            // 🚀 PERFORMANCE: Release to universal pool for reuse
            overlayPool.releasePolygon(polygon)
        }

        /**
           * Animate marker addition with correct fade-in behavior
           * Fade-in: Add with 0 alpha → Animate to 1.0 alpha
           */
        private suspend fun animateMarkerAddition(marker: org.osmdroid.views.overlay.Marker, layer: FolderOverlay, mapView: MapView) {
            try {
                // Ensure main thread execution for UI operations
                withContext(Dispatchers.Main) {
                    // Add marker to layer
                    layer.add(marker)

                    // Set to full visibility
                    marker.alpha = 1.0f

                    // Refresh map
                    mapView.invalidate()
                }
            } catch (e: Exception) {
                // Emergency fallback
                try {
                    layer.add(marker)
                    marker.alpha = 1.0f
                    mapView.invalidate()
                } catch (fallbackException: Exception) {
                    Log.e("OverlayAnimationManager", "Failed to add marker", fallbackException)
                }
            }
        }

        /**
           * Animate marker removal with correct fade-out behavior
           * Fade-out: Animate to 0 alpha → Remove overlay
           */
        private suspend fun animateMarkerRemoval(marker: org.osmdroid.views.overlay.Marker, layer: FolderOverlay, mapView: MapView) {
            val currentAlpha = marker.alpha

            // Animate from current alpha to 0 over 500ms
            val animationDuration = 500L
            val steps = 20
            val stepDelay = animationDuration / steps

            for (step in steps downTo 0) {
                val fadeAlpha = (currentAlpha * step) / steps
                marker.alpha = fadeAlpha
                mapView.invalidate()
                delay(stepDelay)
            }

            // Remove from layer after animation completes
            layer.remove(marker)
            // 🚀 PERFORMANCE: Release to universal pool for reuse
            overlayPool.releaseMarker(marker)
        }

        /**
           * Shutdown animation manager and cancel all ongoing animations
           */
        fun shutdown() {
            animationScope.cancel()
        }
    }
}
